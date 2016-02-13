package bayou.http;

import _bayou._http._HttpUtil;
import _bayou._tmp._Array2ReadOnlyList;
import _bayou._http._HttpHostPort;
import _bayou._tmp._JobTimeout;
import _bayou._str._StrUtil;
import bayou.async.Async;
import bayou.mime.ContentType;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static bayou.http.ImplConn.Goto;

// read and parse request head
class ImplConnReq
{
    ImplConn hConn;

    _JobTimeout timeout;

    ImplHttpRequest request;
    ImplReqHeadParser parser;
    ArrayList<CharSequence> toDump;
    // above 3 fields are null if no bytes is read

    public ImplConnReq(ImplConn hConn)
    {
        this.hConn = hConn;

        // conn should be readable at this point. we are not in keep-alive waiting
    }

    Goto finish(Goto g)
    {
        if(timeout!=null)
            timeout.complete();

        return g;
    }

    Exception readError;
    Goto reqErr(Exception e)
    {
        readError = e;
        return finish(Goto.reqErr);
    }

    HttpResponseImpl errorResponse;
    Goto reqBad(HttpResponseImpl resp)
    {
        errorResponse=resp;
        return finish(Goto.reqBad);
    }
    Goto reqBad(HttpStatus status, String msg)
    {
        return reqBad(HttpHelper.simpleResp(status, msg));
    }

    void awaitReadable() // not commonly called
    {
        if(ImplConn.PREV_HEADERS) hConn.prevHeaders = null;
        Async<Void> awaitReadable = hConn.tcpConn.awaitReadable(/*accepting*/true);
        if(timeout==null)
            timeout = new _JobTimeout(hConn.conf.requestHeadTimeout, "HttpServerConf.requestHeadTimeout");
        timeout.setCurrAction(awaitReadable);
        awaitReadable.onCompletion(result -> {
            Exception error = result.getException();
            // if error is timeout, it is bad timeout reading request head, not benign timeout waiting for
            // next request (i.e. keep-alive timeout). this is true even if no byte is read for the request.
            if(error!=null)
                hConn.jump( reqErr(error) );
            else
                hConn.jump( read() );      // no recursion worry like  uponReadable-read-uponReadable...
        });
    }

    ImplConn.Goto read() // no throw
    {
        ByteBuffer bb;
        try
        {
            bb = hConn.tcpConn.read();
        }
        catch(Exception t)
        {
            // possible that no bytes have been read
            return reqErr(t);
        }

        if(bb== TcpConnection.STALL) // not likely, conn should be readable here. maybe spuriously readable.
        {
            awaitReadable();
            return Goto.NA;
        }

        if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY) // EOF before head is complete
        {
            // we don't care if FIN is received before CLOSE_NOTIFY on an SSL connection
            if(request==null)  // no bytes at all. normal condition.
                return finish(Goto.reqNone);
            else  // EOF in the middle of head. treat it as network error.
                return reqErr(new IOException("EOF before request head is complete"));
        }

        // we have some bytes we can parse

        if(request==null) // beginning bytes
        {
            request = new ImplHttpRequest();
            request.ip = hConn.tcpConn.getPeerIp();
            request.isHttps = hConn.tcpConn instanceof SslConnection;
            request.certs = certs();
            parser = new ImplReqHeadParser(request);

            if(hConn.dump!=null)
            {
                toDump = new ArrayList<>();
                toDump.add(hConn.reqId());
            }
        }

        int bb_pos0 = bb.position();
        parser.parse(bb, hConn.conf, hConn.prevHeaders);

        if(toDump!=null)
        {
            char[] chars = new char[bb.position()-bb_pos0];
            for(int i=0; i<chars.length; i++)
                chars[i] = (char) (0xff & bb.get(bb_pos0+i));
            toDump.add(new String(chars));
        }

        if(bb.hasRemaining())
            hConn.tcpConn.unread(bb);

        if(parser.state == ImplReqHeadParser.State.DONE)
        {
            if(parser.errorResponse==null)
            { // done. in most cases, head is contained in one packet, and parsed in one round.
                request.timeReceived = System.currentTimeMillis();
                return parse2();
            }
            else // syntax error; we'll try to write a response to client.
            {
                HttpResponseImpl errorResponse = parser.errorResponse;  // not null
                return reqBad(errorResponse);
            }
        }

        // otherwise needs more bytes to complete the head. this is rare.
        // we can imm try read() again, but that's unlikely to succeed, a wasteful sys call.
        // more likely there is nothing to read yet, so we better await readable.
        awaitReadable();
        return Goto.NA;
    }

    // head is syntactically correct. may still be "bad" request
    Goto parse2()
    {
        HeaderMap headers = request.headers;
        String hv; // var for header values

        // scheme, host, request-target
        if(request.method.equals("CONNECT")) // usually false; CONNECT is disabled by default.
        {
            // request-target must be host:port, where host is domain, ipv4, or [ipv6].
            // port is not optional - there's no default port.
            String target = request.uri;
            _HttpHostPort hp = _HttpHostPort.parse(target);
            if(hp==null) // parse error
                return reqBad(HttpStatus.c400_Bad_Request, "Invalid Host: "+target);
            if(hp.port==-1) // port is mandatory for CONNECT
                return reqBad(HttpStatus.c400_Bad_Request, "Missing port in Host: "+target);
            target = hp.toString(-1); // reconstructed, normalized
            request.uri = target;
            headers.xPut(Headers.Host, target);
            // HTTP/1.1 client should send Host header identical to request-target for CONNECT.
            // here we simply add/override Host with request-target.
        }
        else // not CONNECT
        CHECK_REQ_TARGET:
        {
            hv = headers.xGet(Headers.Host); // may be null or empty

            if( _HttpUtil.isOriginFormUri(request.uri) && _HttpHostPort.isNormal(hv, request.isHttps) )
                break CHECK_REQ_TARGET; // all good, nothing to change. common case.

            RequestTarget rt = RequestTarget.of(request.isHttps, request.method, request.uri, hv);
            if(rt==null)
                return reqBad(HttpStatus.c400_Bad_Request, "Invalid request-target: "+request.uri);
            request.isHttps = rt.isHttps;
            request.uri = rt.reqUri;

            if(rt.host ==null)
                return reqBad(HttpStatus.c400_Bad_Request, "Host is missing");
            // if request is HTTP/1.0, it's legal to not carry Host header. we reject that case,
            // speculating that nowadays 1.0 clients will send Host header too.
            // if that's a problem, we need to insert a surrogate Host header, defined by a conf variable
            if(rt.host.isEmpty())
                return reqBad(HttpStatus.c400_Bad_Request, "Host is empty");
            // theoretically Host header can be empty.
            // for example, if the URI of a request is an URN, Host must exist and be empty.
            // we don't support such cases, which probably don't really exist.

            _HttpHostPort hp = _HttpHostPort.parse(rt.host);
            if(hp==null) // parse error
                return reqBad(HttpStatus.c400_Bad_Request, "Invalid Host: "+rt.host);
            int implicitPort = request.isHttps ? 443 : 80;
            String host = hp.toString(implicitPort); // reconstructed, normalized.
            headers.xPut(Headers.Host, host);
            // original Host header may be overridden if request-target is an absolute URI.
        }


        ContentType contentType=null;
        if(null!=(hv=headers.xGet(Headers.Content_Type)))
        {
            try
            {
                contentType = ContentType.parse(hv); // throws
            }
            catch (Exception e) // parse error
            {
                return reqBad(HttpStatus.c400_Bad_Request, "Bad Content-Type");
            }
        }

        ImplHttpEntity reqEntity = null;
        // CONNECT request body:
        //   in practice, some clients set Content-Length in CONNECT requests; the most probable intention is
        //     to workaround some intermediaries; the apparent request body is actually payload for tunneling.
        //   we pass it as a request entity to app. if app doesn't read it, the request body will be tunneled.
        if(null!=(hv=headers.xGet(Headers.Transfer_Encoding)))
        {
            if(!_StrUtil.equalIgnoreCase(hv, "chunked"))
                return reqBad(HttpStatus.c501_Not_Implemented,
                        "Only `chunked` is understood for Transfer-Encoding header");
            // later we may support "gzip, chunked"

            //we have a chunked entity body
            //if Content-Length is present along with Transfer-Encoding, Content-Length is ignored (must be removed)
            headers.xRemove(Headers.Content_Length);

            request.entity = reqEntity = new ImplHttpEntity(hConn, /*bodyLength*/null);
            // the size limit of the body will be checked when reading the body
        }
        else if(null!=(hv=headers.xGet(Headers.Content_Length)))
        {
            long len;
            try
            {   len = Long.parseLong(hv, 10); }
            catch (NumberFormatException e)
            {   return reqBad(HttpStatus.c400_Bad_Request, "Bad Content-Length"); }

            if(len<0)  // 0 is ok
                return reqBad(HttpStatus.c400_Bad_Request, "Bad Content-Length");

            if(len > hConn.conf.requestBodyMaxLength)
                return reqBad(HttpStatus.c413_Request_Entity_Too_Large,
                        "Request body length exceeds confRequestBodyMaxLength="+hConn.conf.requestBodyMaxLength);

            //we have a plain entity body with known length
            request.entity = reqEntity = new ImplHttpEntity(hConn, new Long(len));

            // if this is GET/HEAD/DELETE/etc, and the request contains a spurious Content-Length:0,
            // we assign a spurious entity. ideally we should not do that, but,
            // it doesn't matter:
            //   1. app probably will not check the entity anyway for GET etc
            //   2. in practice, clients don't send the spurious Content-Length:0
            // and we can't be sure that this is a spurious entity:
            //   1. we don't know the complete list of request methods that has no official
            //      defined semantics for the entity (so that we can fairly safely remove it)
            //   2. *all* requests are allowed to contain entities.
            //      client-server may have privately defined semantics for the entity.
        }
        else if(contentType!=null)
        {
            // request has no message body, but it has entity metadata. consider it has a body of 0-byte.
            // this is not likely. some may argue that the following is a valid request
            //     POST /action HTTP/1.1
            //     Content-Type: application/x-www-form-urlencoded
            // it's a form POST with no parameters, and the client omits to send Content-Length:0
            // this does not happen in practice. whatever. we consider it an entity just in case.
            request.entity = reqEntity = new ImplHttpEntity(hConn, new Long(0));
        }


        // actually from here on, we at least can trust request framing, so further errors are less bad.

        if(reqEntity!=null)
        {
            reqEntity.contentType = contentType; // can be null

            if(null!=(hv=headers.xGet(Headers.Content_Encoding)))
            {
                if(hConn.conf._requestEncodingReject)
                    return reqBad(HttpStatus.c415_Unsupported_Media_Type, "Unsupported Content-Encoding: " + hv);
                reqEntity.contentEncoding = hv.toLowerCase(); // not validated. usually a single token.
            }

            // there shouldn't be other entity headers in request.
            // if they exist, they'll be in the request headers, but not entity properties.
        }

        if(null!=(hv=headers.xGet(Headers.Expect)))
        {
            if( _StrUtil.equalIgnoreCase(hv, "100-continue") )
            {
                if(reqEntity!=null)
                    reqEntity.expect100(hConn, request);
                // otherwise, request expects 100-continue, but has no body.
                // that is very odd. maybe client screwed up. but we can't definitely call it wrong.
                // ignore the expectation; will not respond 100. client should be ok with that.
            }
            else // don't understand. could be comma-separated list containing 100-continue and other values.
            {
                return reqBad(HttpStatus.c417_Expectation_Failed,
                        "Only `100-continue` is understood for Expect header");
            }
        }

        // request is good as far as we know.
        request.fixForward(hConn.conf.xForwardLevel);
        request.seal();

        return finish(Goto.reqGood);
    }


    List<X509Certificate> certs()
    {
        if(!(hConn.tcpConn instanceof SslConnection))
            return Collections.emptyList();

        SSLSession session = ((SslConnection)hConn.tcpConn).getSslSession();

        Certificate[] array;
        try
        {
            array = session.getPeerCertificates();
        }
        catch (SSLPeerUnverifiedException e)
        {
            return Collections.emptyList();
        }
        for(Certificate cert : array)
            if(!(cert instanceof X509Certificate))
                return Collections.emptyList();

        return new _Array2ReadOnlyList<>(array);  // up cast
    }

}
