package _bayou._tmp;

import _bayou._log._Logger;
import _bayou._str._StrUtil;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class _ByteBufferUtil
{
    // similar to dest.put(src), but it's ok if src has more bytes than room in dest.
    // if src.remaining()>dest.remaining(), only dest.remaining() bytes are copied.
    // return number of bytes copied
    public static int putSome(ByteBuffer dest, ByteBuffer src)
    {
        int srcBytes = src.remaining();
        int destRoom = dest.remaining();
        int extra = srcBytes - destRoom;
        if(extra<=0)
        {
            dest.put(src);
            return srcBytes;
        }
        else
        {
            src.limit(src.limit()-extra);
            dest.put(src);
            src.limit(src.limit()+extra);
            return destRoom;
        }
    }

    public static ByteBuffer copyOf(ByteBuffer origin)
    {
        ByteBuffer bbNew = ByteBuffer.allocate(origin.remaining());

        int origin_pos = origin.position();
        bbNew.put(origin);
        origin.position(origin_pos);

        bbNew.flip();
        return bbNew;
    }

    // return origin[0,len),   origin.position+=len
    public static ByteBuffer slice(ByteBuffer origin, int len)
    {
        int p0 = origin.position();
        int p1 = p0 + len;
        int p2 = origin.limit();

        origin.limit(p1);
        ByteBuffer slice = origin.slice();
        origin.position(p1);
        origin.limit(p2);

        return slice;
    }

    // if origin remaining/capacity < fullFactor, return a copy (to save memory; origin is to be discarded)
    public static ByteBuffer shrink(ByteBuffer origin, double fullFactor)
    {
        if( origin.remaining()*1.0 >= origin.capacity()*fullFactor )
            return origin;  // it's full enough

        return copyOf(origin);
    }

    public static ByteBuffer wrapLatin1(CharSequence chars)
    {
        return ByteBuffer.wrap(_StrUtil.latin1Bytes(chars));
    }


    public static String toLatin1String(ByteBuffer bb)
    {
        byte[] bytes = new byte[bb.remaining()];
        int pos0 = bb.position();
        bb.get(bytes);
        bb.position(pos0);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

















    // WARN: very dangerous. used internally by trusted code. don't expose this util to end user.
    //       after dealloc(bb), bb MUST NOT be used. it should be imm de-referenced. otherwise, chaos!
    // currently only support java.nio.DirectByteBuffer.
    public static void dealloc(ByteBuffer bb)
    {
        Dealloc.dealloc(bb);
    }

    // for direct byte buffer in OpenJDK impl, we want to do, through reflection
    //     java.nio.DirectByteBuffer dbb = (cast)bb;
    //     sun.misc.Cleaner cleaner = dbb.cleaner();
    //     if(cleaner!=null) cleaner.clean();
    // usually cleaner is invoked automatically by GC after buffer is no longer referenced.
    // here we invoke it forcefully; if buffer is still referenced and used, it's gonna be chaos.
    static class Dealloc
    {
        static Class<?> class_DirectByteBuffer;
        static Method method_cleaner;
        static Method method_clean;
        // to consider: static final MethodHandle are probably faster.

        static boolean failed;

        static
        {
            try
            {
                class_DirectByteBuffer = Class.forName("java.nio.DirectByteBuffer", false, null);
                Class<?> class_Cleaner = Class.forName("sun.misc.Cleaner");
                method_cleaner = class_DirectByteBuffer.getMethod("cleaner");
                if(class_Cleaner!=method_cleaner.getReturnType())
                    throw new Exception();
                method_clean = class_Cleaner.getMethod("clean");

                method_cleaner.setAccessible(true);
                method_clean.setAccessible(true);

                deallocE(ByteBuffer.allocateDirect(16)); // try it, may throw
            }
            catch(Exception|Error t)
            {
                failed = true;

                class_DirectByteBuffer=null;
                method_cleaner=null;
                method_clean=null;
            }
        }

        static void dealloc(ByteBuffer bb)
        {
            if(failed)
                return;
            try
            {
                deallocE(bb);
            }
            catch(Exception|Error t)
            {
                failed=true; // if failed once, stop all further attempts.
                // `failed` is not volatile. that's fine.

                // let's see why. error is odd since we've tested deallocE() in static{}
                _Logger.of(_ByteBufferUtil.class).error("Fail to dealloc ByteBuffer - %s", t);
            }
        }

        static void deallocE(ByteBuffer bb) throws Exception
        {
            if(!class_DirectByteBuffer.isInstance(bb)) // DirectByteBuffer dbb = (DirectByteBuffer)bb;
                return;
            Object cleaner = method_cleaner.invoke(bb); // cleaner=dbb.cleaner();
            if(cleaner==null)
                return;
            method_clean.invoke(cleaner); // cleaner.clean();
        }
    }
}
