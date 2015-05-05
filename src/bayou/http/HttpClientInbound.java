package bayou.http;

import _bayou._http._HttpDate;
import _bayou._http._HttpUtil;
import _bayou._str._StrUtil;
import _bayou._tmp.*;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.mime.ContentType;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

class HttpClientInbound
{
    final HttpClientConnection conn;
    final TcpConnection tcpConn;

    HttpClientInbound(HttpClientConnection conn)
    {
        this.conn = conn;
        this.tcpConn = conn.tcpConn;
    }

    final Object lock(){ return this; }
    // we don't really worry about lock contention. usually all actions are done on the selector flow.

    boolean closeCalled;

    // <<
    Exception error; // persistent, unrecoverable. inbound is corrupt.
    //TBA: we may send this exception to multiple consumers. problematic if it's mutable.
    //
    Async<Void> prevBodyEof = Async.VOID;
    //
    Promise<HttpResponse> promise;
    boolean isLastResponse;
    // >>
    // <<fields>> are modified by read-parse flow only. app flow may read them.

    boolean isLastResponse()
    {
        synchronized (lock())
        {
            return isLastResponse;
        }
    }


    void close()
    {
        Promise<HttpResponse> _promise;
        boolean closeTcpConn;
        synchronized (lock())
        {
            if(closeCalled)
                return;
            closeCalled = true;

            _promise = promise; // to cancel it

            closeTcpConn = (error==null && promise==null); // standby state.
            // if error!=null, tcpConn_close() was called
            // if promise!=null, tcpConn_close() will be triggered upon its completion
        }

        if(_promise!=null)
            _promise.cancel(new AsynchronousCloseException()); // most likely cancels tcpConn.awaitReadable()

        if(closeTcpConn)
            conn.tcpConn_close(true);
    }


    Async<HttpResponse> receiveNextResponse()
    {
        synchronized (lock())
        {
            if(closeCalled)
                return Result.failure(new AsynchronousCloseException());

            if(error !=null)
                return Result.failure(error);

            if(promise!=null) // programming error
                throw new IllegalStateException("a previous response is pending");

            Result<Void> prevEof = prevBodyEof.pollResult();
            if(prevEof==null)
                throw new IllegalStateException("previous response body is not drained");
            if(prevEof.isFailure())
                return Result.failure(new Exception("previous response body is corrupt", prevEof.getException()));

            if(reqInfo==null)
            {
                reqInfo = conn.outbound.pollReqInfo();
                if(reqInfo==null)
                    throw new IllegalStateException("try to receive response without a corresponding request");
            }
            // otherwise, the prev response is 1xx, therefore the corresponding request didn't change.

            promise = new Promise<>();
        }

        // read and parse in tcpConn.executor. (usually same as the current executor)
        tcpConn.getExecutor().execute(this::to_awaitReadable);
        // to_awaitReadable() because at this time most likely no server data has arrived

        return promise;
    }



    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    // read and parse next response. in tcpConn.executor
    // end result: either success with a response, or failure with an error.

    HttpClientOutbound.ReqInfo reqInfo;
    ImplRespHeadParser parser;
    ArrayList<CharSequence> toDump;

    void to_awaitReadable()
    {
        Async<Void> awaitReadableAsync = tcpConn.awaitReadable(false); // no timeout here; rely on cancel.
        promise.onCancel(awaitReadableAsync::cancel);
        awaitReadableAsync.onCompletion(onReadable);
    }

    Consumer<Result<Void>> onReadable = result ->
    {
        try
        {
            result.getOrThrow(); // throws if failure

            readAndParseE();
        }
        catch (Exception e)
        {
            setError(e);
        }

    };

    void setError(Exception e) // from reading or parsing
    {
        Promise<HttpResponse> _promise;
        synchronized (lock())
        {
            assert error ==null;
            assert promise!=null;
            // `closeCalled` can be true or false here

            error = e;

            _promise = promise;
            promise = null;
        }

        reqInfo = null;
        parser = null;

        String errMsg = "<ERROR> "+e+"\r\n\r\n";
        if(toDump==null)
        {
            if(conn.conf.trafficDumpWrapper!=null)
                conn.conf.trafficDumpWrapper.print(errMsg);
        }
        else
        {
            toDump.add(errMsg);
            conn.conf.trafficDumpWrapper.print(toDump);
            toDump=null;
        }

        _promise.fail(e);

        conn.tcpConn_close(false);
    }


    void success(HttpResponseImpl response, ImplHttpEntity entity)
    {
        boolean isLast = isLastResponse(reqInfo, response, entity);

        if(response.statusCode()/100!=1)
            reqInfo=null;
        // otherwise, keep reqInfo for next response

        parser = null;

        Promise<HttpResponse> _promise;
        synchronized (lock())
        {
            if(closeCalled)
            {
                setError(new AsynchronousCloseException());
                return;
            }

            isLastResponse = isLastResponse | isLast;

            _promise = promise;
            promise = null;

            prevBodyEof = entity!=null ? entity.body.awaitEof : Async.VOID;
        }
        _promise.succeed(response);

        if(toDump!=null)
        {
            conn.conf.trafficDumpWrapper.print(toDump);
            toDump = null;
        }

    }




    void readAndParseE() throws Exception
    {
        ByteBuffer bb = tcpConn.read(); // throws

        if(bb== TcpConnection.STALL) // not likely, conn should be readable here. maybe spuriously readable.
        {
            to_awaitReadable();
            return;
        }

        if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
            throw new IOException("EOF before response head is complete");

        // we have some bytes we can parse

        if(parser==null) // beginning bytes
        {
            parser = new ImplRespHeadParser(conn.conf.responseHeadFieldMaxLength, conn.conf.responseHeadTotalMaxLength);

            if(conn.conf.trafficDumpWrapper!=null)
            {
                toDump = new ArrayList<>();
                toDump.add("== response #"+tcpConn.getId()+"-"+reqInfo.id+" ==\r\n");
            }
        }

        int bb_pos0 = bb.position();
        parser.parse(bb);

        if(toDump!=null)
        {
            char[] chars = new char[bb.position()-bb_pos0];
            for(int i=0; i<chars.length; i++)
                chars[i] = (char) (0xff & bb.get(bb_pos0+i));
            toDump.add(new String(chars));
        }

        if(bb.hasRemaining())
            tcpConn.unread(bb);

        switch(parser.state)
        {
            case END: // done. in most cases, head is contained in one packet, and parsed in one round.
            {
                HttpResponseImpl response = parse2(); // throws
                success(response, (ImplHttpEntity)response.entity);
                return;
            }

            case ERROR: // syntax error
                throw new Exception("response head parse error: "+parser.errorMessage);

            // otherwise needs more bytes to complete the head. this is rare.
            default:
                to_awaitReadable(); // most likely not readable at this point
                return;
        }

    }

    HttpResponseImpl parse2() throws HttpResponseException
    {
        HttpResponseImpl response = parser.response;

        if(reqInfo.await100!=null)
        {
            int sc = response.statusCode();
            if(sc==100)
                reqInfo.await100.cancel(new TimeoutException("100 response received"));
            else if(sc>=200)
                reqInfo.await100.cancel(new _ControlException("final response received"));
        }

        response.entity = makeEntity(response, reqInfo.method, tcpConn); // throws

        // todo: response certs

        return response;
    }



    static boolean isLastResponse(HttpClientOutbound.ReqInfo reqInfo, HttpResponse response, ImplHttpEntity entity)
    {
        // 1xx response cannot be the last, regardless of other conditions.
        if(response.statusCode()/100==1)
            return false;

        if(reqInfo.isLastRequest)
            return true;

        String hvConnection = response.headers().get(Headers.Connection);
        if(_HttpUtil.containsToken(hvConnection, "close"))
            return true;

        if(response.httpVersion().equals("1.0")) // we need positive "Connection: keep-alive" header
            if(!_HttpUtil.containsToken(hvConnection, "keep-alive"))
                return true;

        if(entity!=null && (entity.body instanceof ImplHttpEntity.FinTerminatedBody))
            return true; // response body is terminated by FIN

        // if request body is not sent, due to Expect:100-continue failure,
        // the outbound is corrupt with exception, send(request) fails.
        // that condition is not reflected by flag `isLastResponse` here.

        return false;
    }

    static ImplHttpEntity makeEntity(HttpResponseImpl response, String reqMethod, TcpConnection tcpConn)
        throws HttpResponseException
    {
        // no entity: 1xx, 204, 304, CONNECT-2xx
        int code = response.statusCode();
        if(code<200 || code==204 || code==304)
            return null;

        if(code/100==2 && reqMethod.equals("CONNECT"))
            return null;
        // in this case, some servers set Content-Length in response, to bypass some intermediaries.
        // the apparent response body is actually tunnel payload.

        boolean reqIsHead = reqMethod.equals("HEAD");

        HeaderMap headers = response.headers();
        String hv; // var for header values

        // we don't limit response body length here. a legit response can easily reach GBs.

        ImplHttpEntity entity;
        if(null!=(hv=headers.get(Headers.Transfer_Encoding)))
        {
            if(!_StrUtil.equalIgnoreCase(hv, "chunked"))
                throw new HttpResponseException("Unsupported Transfer-Encoding: "+hv, response);
            // later we may support "gzip, chunked".
            // that would require we set request headers of "TE: gzip" and "Connection: TE"

            //we have a chunked entity body
            //if Content-Length is present along with Transfer-Encoding, Content-Length is ignored (must be removed)
            headers.remove(Headers.Content_Length);

            entity = new ImplHttpEntity(tcpConn, reqIsHead, true, null);
        }
        else if(null!=(hv=headers.get(Headers.Content_Length)))
        {
            long len;
            try
            {   len = Long.parseLong(hv, 10); }
            catch (NumberFormatException e)
            {   throw new HttpResponseException("Bad Content-Length: "+hv, response); }

            if(len<0)  // 0 is ok
                throw new HttpResponseException("Bad Content-Length: "+hv, response);

            //we have a plain entity body with known length
            entity = new ImplHttpEntity(tcpConn, reqIsHead, false, new Long(len));
        }
        else // body ends with FIN.
        {
            entity = new ImplHttpEntity(tcpConn, reqIsHead, false, null);
        }

        // entity metadata
        if(null!=(hv=headers.get(Headers.Content_Type)))
        {
            try
            {
                entity.contentType = ContentType.parse(hv); // throws
            }
            catch (Exception e) // parse error
            {
                // tolerate
            }
        }

        entity.contentEncoding = headers.get(Headers.Content_Encoding);
        // todo: validate?
        // todo: auto-decode gzip?

        if(null!=(hv=headers.get(Headers.Last_Modified)))
        {
            entity.lastModified = _HttpDate.parse(hv);
        }

        if(null!=(hv=headers.get(Headers.Expires)))
        {
            entity.expires = _HttpDate.parse(hv);
        }

        if(null!=(hv=headers.get(Headers.ETag)))
        {
            entity.etagIsWeak = hv.startsWith("W/");
            entity.etag = parseEtag(hv, entity.etagIsWeak?2:0, hv.length()); // null if fail
        }

        return entity;
    }

    static String parseEtag(String string, int start, int end)
    {
        // we follow rfc2616, treat entity-tag as quoted-string; "\x" will be unescaped.
        // if that is not desired, user may consult ETag header directly.

        if(end-start<2)
            return null;
        if(string.charAt(start)!='"' || string.charAt(end-1)!='"' )
            return null;

        boolean esc = false;
        StringBuilder sb = new StringBuilder(end-start-2);
        for(int i=start+1; i<end-1; i++)
        {
            char ch = string.charAt(i);
            if(esc)
            {
                sb.append(ch);
                esc=false;
            }
            else if(ch=='\\')
                esc = true;
            else
                sb.append(ch);
            // no error for unescaped " inside? e.g. ETag: "abc"xyz"
        }
        if(esc)
            return null;
        return sb.toString();
    }
}
