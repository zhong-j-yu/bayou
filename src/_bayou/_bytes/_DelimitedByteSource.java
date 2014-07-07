package _bayou._bytes;

import _bayou._async._Asyncs;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.bytes.PushbackByteSource;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

// detect delimiters in origin source.
// the delimiter is given as DELIM. when a region of origin bytes matches DELIM, DELIM is returned by this source.
// e.g. DELIM=[1234], origin bytes: [abc12] [34xyz], this bytes: [abc] DELIM [xyz]
// DELIM must be non-empty
// reader of this source should check result for DELIM.
// CAUTION: the same ByteBuffer DELIM is returned repeatedly, reader should not touch it, should not leak it to others
public class _DelimitedByteSource implements ByteSource
{
    PushbackByteSource origin;

    ByteBuffer DELIM;
    int DELIM_pos0, DELIM_lim0;

    byte[] delim;
    int[] kmpTable;
    // Knuth-Morris-Pratt
    // http://www.inf.fh-flensburg.de/lang/algorithmen/pattern/kmpen.htm

    public _DelimitedByteSource(ByteSource origin, ByteBuffer DELIM)
    {
        _Util.require(DELIM.hasRemaining(), "DELIM.hasRemaining()==true");

        this.origin = new PushbackByteSource(origin);

        this.DELIM = DELIM;
        this.DELIM_pos0 = DELIM.position();
        this.DELIM_lim0 = DELIM.limit();

        delim = new byte[DELIM.remaining()];
        int pos0 = DELIM.position();
        DELIM.get(delim);
        DELIM.position(pos0);

        kmpTable = kmpPreprocess(delim);
    }

    static int[] kmpPreprocess(byte[] p)
    {
        int m = p.length;
        int[] b = new int[m+1];

        int i=0, j=-1;
        b[i]=j;
        while (i<m)
        {
            while (j>=0 && p[i]!=p[j])
                j=b[j];
            i++;
            j++;
            b[i]=j;
        }
        return b;
    }
    // M: already matched.
    // define: byte[] t = p[0,M) + bb[0, length)
    // return first x, that t[x,) matches p[0,). can be partial match at end.
    // return t.length if no match at all.
    static int kmpSearch(int M, ByteBuffer bb, byte[] p, int[] b)
    {
        final int m = p.length;
        assert M < m;

        int i=0, j=M;
        while (bb.hasRemaining())
        {
            byte t_i = bb.get();
            while (j>=0 && t_i!=p[j])
                j=b[j];
            i++;
            j++;
            if (j==m)
                return M+i-j; // full match
        }
        return M+i-j; // partial match at end or no match
    }

    int matched;
    boolean closed;



    ArrayDeque<ByteBuffer> resultQueue = new ArrayDeque<>(3);
    boolean resultEof;


    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        if(!resultQueue.isEmpty())
            return Result.success(resultQueue.removeFirst());

        if(resultEof)
            return _Util.EOF;

        return _Asyncs.scan(origin::read,
            bb ->
            {
                process(bb);
                return resultQueue.pollFirst();
                // if resultQueue is empty, return null to continue loop
            },
            eof ->
            {
                resultEof = true;

                if (matched == 0)
                    throw eof;

                ByteBuffer bb = partialDelim(matched);
                matched = 0;
                return bb;
            });
    }

    // first n bytes of delim
    ByteBuffer partialDelim(int n)
    {
        // we must not allow user to touch the delim array
        byte[] copy = Arrays.copyOf(delim, n);
        return ByteBuffer.wrap(copy);
    }

    void process(ByteBuffer bb)
    {
        // bb may be empty
        int bb_pos0 = bb.position();
        int bb_len = bb.remaining();

        int iA = kmpSearch(matched, bb, delim, kmpTable);
        bb.position(bb_pos0);

        int n = matched + bb_len;
        int iB = iA + delim.length;
        int S = iA - matched;

        if( iA>0 && matched>0 )
            resultQueue.addLast(partialDelim(Math.min(matched, iA)));

        if(S>0)
        {
            if(S<bb_len)
                resultQueue.addLast(slice(bb, S));
            else// S==bb_len, iB>n
                resultQueue.addLast(bb); // this should be most common. bb handed over to user.
        }

        if(iB<=n)
        {
            // in case DELIM was touched by reader, restore its pointers
            DELIM.position(DELIM_pos0);
            DELIM.limit(DELIM_lim0);
            resultQueue.addLast(DELIM);
            matched = 0;
        }
        else // iB>n
            matched = n-iA;

        if(iB<n)
        {
            bb.position(bb.limit()-(n-iB));
            origin.unread(bb);
        }

    }

    static ByteBuffer slice(ByteBuffer origin, int len)
    {
        int p0 = origin.position();
        int p1 = p0 + len;
        int p2 = origin.limit();

        origin.limit(p1);
        ByteBuffer slice = origin.duplicate();
        origin.limit(p2);

        return slice;
    }


    // skip()
    // cannot skip without examining bytes for DELIM

    @Override
    public Async<Void> close()
    {
        if(closed)
            return Async.VOID;
        closed=true;

        resultQueue.clear();

        return origin.close();
    }


}
