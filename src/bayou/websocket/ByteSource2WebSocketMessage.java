package bayou.websocket;

import bayou.async.Async;
import bayou.bytes.ByteSource;

import java.nio.ByteBuffer;

class ByteSource2WebSocketMessage implements WebSocketMessage
{
    final boolean isText;
    final ByteSource bs;

    public ByteSource2WebSocketMessage(boolean text, ByteSource bs)
    {
        isText = text;
        this.bs = bs;
    }

    @Override
    public boolean isText()
    {
        return isText;
    }

    @Override
    public Async<ByteBuffer> read()
    {
        return bs.read();
    }

    @Override
    public long skip(long n) throws IllegalArgumentException
    {
        return bs.skip(n); // may throw
    }

    @Override
    public Async<Void> close()
    {
        return bs.close();
    }
}
