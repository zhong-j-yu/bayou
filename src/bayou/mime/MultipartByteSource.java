package bayou.mime;

import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._CharDef;
import _bayou._tmp._StreamIter;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.util.End;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Generates a multipart body.
 * <p>
 *     This class converts a sequence of {@link MultipartPart parts}
 *     to a sequence of bytes in the "multipart" format.
 *     See <a href="https://www.ietf.org/rfc/rfc2046.txt">RFC2046</a>.
 * </p>
 */
public class MultipartByteSource implements ByteSource
{
    _StreamIter<? extends MultipartPart> parts; // null when exhausted

    CharSequence boundary;
    String _delimiter;  // CRLF--boundary
    // the delimiter must not appear in a part body. we can easily check that
    // by wrapping part body with DelimitedByteSource and check for DELIM; if DELIM detected, read error.
    // we don't do that. for user supplied boundary, we assume user knows it doesn't appear in bodies.
    // for our randomly generated boundary, the chance of match is 1/10^20, too low to be concerned.

    // produced bytes are *almost* in the form of
    //     1*( CRLF--boundary CRLF *part-head CRLF part-body ) CRLF--boundary--
    // except the 1st CRLF is omitted per rfc2046, and then we append a last CRLF after the whole thing.
    // this is also what browsers do - there's no leading CRLF, but there's a trailing CRLF.
    //
    // there should be at least 1 part. otherwise we produce --boundary--CRLF. which is not really legit.

    //  for the 0th delimiter, omit the leading CRLF
    int delimiterCount;

    String delimiter()
    {
        return delimiterCount++==0 ? _delimiter.substring(2) : _delimiter;
    }


    ByteSource currPartBody;

    boolean closed;

    /**
     * Create a multipart body from the parts.
     *
     * <p>
     *     A random "boundary" is generated for this multipart body.
     * </p>
     */
    // auto generate a random boundary
    public MultipartByteSource(Stream<? extends MultipartPart> parts)
    {
        this(parts, "----------"+ createRandomBoundary(22));
    }

    /**
     * Create a multipart body from the parts, using the "boundary".
     */
    // caller can supply a boundary. probably uncommon.
    // caller must make sure it's a valid boundary per rfc2046, and it doesn't appear in bodies of parts
    public MultipartByteSource(Stream<? extends MultipartPart> parts, CharSequence boundary)
    {
        this.parts = new _StreamIter<>(parts);

        this.boundary = boundary;  // no check? trust caller.
        this._delimiter = "\r\n--" + boundary;
    }

    /**
     * Get the "boundary" of this multipart body.
     */
    public CharSequence getBoundary()
    {
        return boundary;
    }

    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        if(parts==null)
        {
            return _Util.EOF;
        }

        if(currPartBody!=null)
        {
            return currPartBody.read().catch_(End.class, end -> {
                currPartBody.close();
                currPartBody = null;
                return beginNextPart(); // throws
            });
        }
        else
        {
            try
            {
                return Result.success(beginNextPart());
            }
            catch (Exception e)
            {
                return Result.failure(e);
            }
        }
    }

    private ByteBuffer beginNextPart() throws Exception
    {
        MultipartPart part = parts.next();
        if(part == null)
        {
            parts.close();
            parts=null;
            String close_delimiter = delimiter() + "--\r\n";
            return _ByteBufferUtil.wrapLatin1(close_delimiter);
        }
        else
        {
            CharSequence delimCrlfHead;
            try
            {
                delimCrlfHead = buildDelimCRLFHead(delimiter(), part.headers());
            }
            catch (Exception e)
            {
                // corrupt headers; could be injected by malicious client
                close();  // unrecoverable.
                throw e;
            }

            currPartBody = part.body();

            return _ByteBufferUtil.wrapLatin1(delimCrlfHead);
        }
    }

    static CharSequence buildDelimCRLFHead(String delimiter, Map<String,String> headers) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        sb.append(delimiter).append("\r\n");
        for(Map.Entry<String,String> nv : headers.entrySet())
        {
            // note: header name/value must be octets, 0x00-FF
            String name = nv.getKey();
            if(!_CharDef.check(name, _CharDef.Rfc822.fieldNameChars))
                throw new IllegalArgumentException("invalid header name: "+name);

            String value = nv.getValue();
            if(!_CharDef.check(value, _CharDef.Rfc822.fieldBodyCharsX))
                throw new IllegalArgumentException("invalid header value: "+name+": "+value);

            sb.append(name).append(": ").append(value).append("\r\n");
        }
        sb.append("\r\n");
        return sb;
    }

    @Override
    public long skip(long n) throws IllegalArgumentException, IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        if(currPartBody!=null)
            return currPartBody.skip(n); // checks `n`
        else
            return 0;
    }

    @Override
    public Async<Void> close()
    {
        if(closed)
            return Async.VOID;
        closed = true;

        if(currPartBody!=null)
        {
            currPartBody.close();
            currPartBody = null;
        }

        if(parts!=null)
        {
            parts.close();
            parts = null;
        }
        return Async.VOID;
    }

    /**
     * Create a random "boundary" of the specified length for a multipart body.
     */
    public static CharSequence createRandomBoundary(int length)
    {
        final String boundaryChars = _CharDef.a_zA_Z0_9;

        char[] chars = new char[length];
        Random random = ThreadLocalRandom.current();
        for(int i=0; i<length; i++)
            chars[i] = boundaryChars.charAt( random.nextInt(boundaryChars.length()) );
        return new String(chars);
        // well, what if the document contains generated char sequence based on the same
        // pseudo random algorithm? it'll be more likely that doc contains the boundary.
    }

}
