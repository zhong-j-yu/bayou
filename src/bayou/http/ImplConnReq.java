package bayou.http;

import _bayou._tmp._Array2ReadOnlyList;
import _bayou._tmp._HttpUtil;
import _bayou._tmp._JobTimeout;
import _bayou._tmp._StrUtil;
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
        Async<Void> awaitReadable = hConn.nbConn.awaitReadable(/*accepting*/true);
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
            bb = hConn.nbConn.read();
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
            int fieldMax = hConn.conf.requestHeadFieldMaxLength;
            int totalMax = hConn.conf.requestHeadTotalMaxLength;
            request = new ImplHttpRequest();
            request.ip = hConn.nbConn.getPeerIp();
            request.isHttps = hConn.nbConn instanceof SslConnection;
            request.certs = certs();
            parser = new ImplReqHeadParser(fieldMax, totalMax, hConn.conf.supportedMethods, request);

            if(hConn.dump!=null)
            {
                toDump = new ArrayList<>();
                toDump.add(hConn.reqId());
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
            hConn.nbConn.unread(bb);

        switch(parser.state)
        {
            case END: // done. in most cases, head is contained in one packet, and parsed in one round.
                request.timeReceived = System.currentTimeMillis();
                return parse2();

            case ERROR: // syntax error; we'll try to write a response to client.
                HttpResponseImpl errorResponse = parser.errorResponse;  // not null
                return reqBad(errorResponse);

            // otherwise needs more bytes to complete the head. this is rare.
            default:
                // we can imm try read() again, but that's unlikely to succeed, a wasteful sys call.
                // more likely there is nothing to read yet, so we better await readable.
                awaitReadable();
                return Goto.NA;
        }
    }

    // head is syntactically correct. may still be "bad" request
    Goto parse2()
    {
        HeaderMap headers = request.headers;


        // we guarantee to app that Host header exists and is non-empty.
        String hvHost = headers.get(Headers.Host);

        if(hvHost==null)
            return reqBad(HttpStatus.c400_Bad_Request, "Host header is missing");
        // if request is HTTP/1.0, it's legal to not carry Host header. we reject that case as well,
        // speculating that nowadays 1.0 clients will send Host header too.
        // if that's a problem, we need to insert a surrogate Host header, defined by a conf variable

        if(hvHost.isEmpty())
            return reqBad(HttpStatus.c400_Bad_Request, "Host header is empty");
        // theoretically Host header can be empty.
        // for example, if the URI of a request is an URN, Host must exist and be empty.
        // we don't support such cases, which probably don't really exist.


        // request.uri is non empty, and contains only legal uri chars
        String uri = request.uri;
        // it can be in 4 forms. we only support one form: origin-form, abs-path[?query]
        if(uri.charAt(0)!='/') // not origin-form. not common
        {
            if(uri.equals("*"))
            {
                if(request.method.equals("OPTIONS")) // fine, we'll just respond right here. app won't get it.
                    return reqBad(HttpStatus.c200_OK, ""); // hackish. not really a bad request. conn will be closed
                else // "*" not valid for any other method
                    return reqBad(HttpStatus.c400_Bad_Request, "Request-URI is invalid");
            }
            else // since we don't allow CONNECT method, the only possible form here is absolute-form
            {
                // for example: http://host:port/path
                // we only support http/https scheme. reject anything else
                uri = extractOriginForm(uri, hvHost);
                if(uri==null) // parse error
                    return reqBad(HttpStatus.c400_Bad_Request, "Request-URI is invalid");
                request.uri = uri;
            }
        }
        if(!_HttpUtil.isOriginFormUri(uri))
            return reqBad(HttpStatus.c400_Bad_Request, "Request-URI is invalid");


        String hv; // var for header values

        ContentType contentType=null;
        if(null!=(hv=headers.get(Headers.Content_Type)))
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

        ImplHttpRequestEntity reqEntity = null;
        if(null!=(hv=headers.get(Headers.Transfer_Encoding)))
        {
            if(!_StrUtil.equalIgnoreCase(hv, "chunked"))
                return reqBad(HttpStatus.c501_Not_Implemented,
                        "Only `chunked` is understood for Transfer-Encoding header");

            //we have a chunked entity body
            //if Content-Length is present along with Transfer-Encoding, Content-Length is ignored
            request.stateBody = 1;
            request.entity = reqEntity = new ImplHttpRequestEntity(hConn, request, /*bodyLength*/null);
            // the size limit of the body will be checked when reading the body
        }
        else if(null!=(hv=headers.get(Headers.Content_Length)))
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
            request.stateBody = len>0? 1 : 3;  // special handling for len=0
            request.entity = reqEntity = new ImplHttpRequestEntity(hConn, request, new Long(len));

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
            request.stateBody = 3;
            request.entity = reqEntity = new ImplHttpRequestEntity(hConn, request, new Long(0));
        }


        // actually from here on, we at least can trust request framing, so further errors are less bad.

        if(reqEntity!=null)
        {
            if(null!=(hv=headers.get(Headers.Content_Encoding)))
                return reqBad(HttpStatus.c415_Unsupported_Media_Type, "Unsupported Content-Encoding: "+hv);
            // we may support gzip in future.
            // more generally, we can allow app to add custom decoders.
            // but in reality this header is not set by clients. so we don't need to care.

            reqEntity.contentType = contentType; // can be null

            // there shouldn't be other entity headers in request.
            // if they exist, they'll be in the request headers, but not entity properties.
        }

        if(null!=(hv=headers.get(Headers.Expect)))
        {
            if( _StrUtil.equalIgnoreCase(hv, "100-continue") )
            {
                if(request.stateBody==1)
                    request.state100 = 1;
                // otherwise, stateBody is 0 or 3. request expects 100-continue, but has no/empty body.
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


    // e.g.   http://abc.com:8080/path  =>  /path
    // absoluteForm:  http[s] :// host [:port] [path] [ ? query ]
    // not optimized. we don't expect this method to be called often.
    static String extractOriginForm(String absoluteForm, String host)
    {
        absoluteForm = absoluteForm.toLowerCase();
        host = host.toLowerCase();

        int iHost;
        if(absoluteForm.startsWith("http://"))
            iHost = 7;
        else if(absoluteForm.startsWith("https://"))
            iHost = 8;
        else
            return null;

        if(!absoluteForm.startsWith(host, iHost))
            return null;

        iHost += host.length();

        String rest = absoluteForm.substring(iHost);
        if(rest.isEmpty())  // example: http://abc.com
            return "/";
        // expect "/" or "?"
        char c0 = rest.charAt(0);
        if(c0=='/')
            return rest;
        else if(c0=='?')  // example: http://abc.com?query
            return "/"+rest;
        else
            return null;
    }

    List<X509Certificate> certs()
    {
        if(!(hConn.nbConn instanceof SslConnection))
            return Collections.emptyList();

        SSLSession session = ((SslConnection)hConn.nbConn).getSslSession();

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
