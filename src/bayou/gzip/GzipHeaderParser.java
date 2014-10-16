package bayou.gzip;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

// http://tools.ietf.org/html/rfc1952#page-5
class GzipHeaderParser
{
    enum State{ head, extra1, extra2, name, comment, crc, done }

    static State jump(State state, int flag)
    {
        switch(state)
        {
            case head    : if( (flag&(1<<2))!=0 ) return State.extra1;
            case extra2  : if( (flag&(1<<3))!=0 ) return State.name;
            case name    : if( (flag&(1<<4))!=0 ) return State.comment;
            case comment : if( (flag&(1<<1))!=0 ) return State.crc;
            case crc     : /*                 */  return State.done;
        }
        throw new AssertionError(); // should not reach here
    }

    State state = State.head;
    boolean done(){ return state==State.done; }

    byte[] buf = new byte[10];
    int x;
    int flag;
    CRC32 crc;



    void parse(ByteBuffer bb) throws ZipException
    {
        while(state!=State.done && bb.hasRemaining())
        {
            byte b = bb.get();
            if(crc!=null && state!=State.crc)
                crc.update(b);

            switch(state)
            {
                case head: // collect 10 bytes
                    buf[x++]=b;
                    if(x<10) break;
                    checkHead(); // throws
                    x=0;
                    state = jump(state, flag);
                    break;

                case extra1: // collect 2 bytes
                    buf[x++]=b;
                    if(x<2) break;
                    x = 256*(0xFF & buf[1]) + (0xFF & buf[0]); // x=XLEN
                    state = State.extra2;
                    if(x==0)
                        state = jump(state, flag);
                    break;

                case extra2: // collect XLEN bytes
                    --x;
                    if(x>0) break;
                    state = jump(state, flag);
                    break;

                // zero-terminated. we don't impose length limit.
                case name:
                case comment:
                    if(b!=0) break;
                    state = jump(state, flag);
                    break;

                case crc:  // collect 2 bytes
                    buf[x++]=b;
                    if(x<2) break;
                    checkCrc(); // throws
                    state=State.done;
                    break;

                default:
                    throw new AssertionError();
            }
        }
    }

    void checkHead() throws ZipException
    {
        if(buf[0]!=(byte) 31) throw new ZipException("Invalid ID1: "+buf[0]);
        if(buf[1]!=(byte)139) throw new ZipException("Invalid ID2: "+buf[1]);
        if(buf[2]!=(byte)  8) throw new ZipException("Invalid CM: " +buf[2]);

        flag = 0xFF & buf[3];
        if( (flag&0B1110_0000)!=0 ) throw new ZipException("reserved bits set in FLG: "+flag);

        if( (flag&(1<<1))!=0 )
        {
            crc = new CRC32();
            crc.update(buf, 0, 10);
        }
    }

    void checkCrc() throws ZipException
    {
        long v = crc.getValue();
        byte v0 = (byte)(v   );
        byte v1 = (byte)(v>>8);
        if(v0!=buf[0] || v1!=buf[1]) throw new ZipException("Invalid CRC16");
    }

}
