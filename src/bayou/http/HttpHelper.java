package bayou.http;

import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.FiberLocal;
import bayou.bytes.ByteSource;
import bayou.mime.ContentType;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

class HttpHelper
{
    static final FiberLocal<HttpRequest> fiberLocalRequest = new FiberLocal<>();


    // msg charset: "ISO-8859-1"
    static HttpResponseImpl simpleResp(HttpStatus status, String msg)
    {
        SimpleTextEntity entity = new SimpleTextEntity(msg);
        return new HttpResponseImpl(status, entity);
    }

    // return an id for the error; id can be sent to client for diagnosis purpose.
    public static String logErrorWithId(Throwable error)
    {
        String id = _Util.msgRef(error.toString());
        HttpServer.logger.error("[error id: %s] %s", id, error);
        return id;
    }

    static class SimpleTextEntity implements HttpEntity
    {
        byte[] msg;
        SimpleTextEntity(String msg)
        {
            this.msg = msg.getBytes(StandardCharsets.ISO_8859_1);
        }
        @Override public ContentType contentType()
        {
            return ContentType.text_plain_ISO88591;
        }
        @Override public Long contentLength()
        {
            return (long)msg.length;
        }
        @Override public ByteSource body()
        {
            return new ByteSource()
            {
                boolean end;
                @Override public Async<ByteBuffer> read()
                {
                    if(end)
                        return _Util.EOF;
                    end =true;
                    return Result.success(ByteBuffer.wrap(msg));
                }
                @Override public Async<Void> close(){ return Async.VOID; }
            };
        }
    }



}
