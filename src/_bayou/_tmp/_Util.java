package _bayou._tmp;

import _bayou._log._Logger;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.util.End;
import bayou.util.function.BiFunctionX;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class _Util
{
    // used by ByteSource.read() impl code.
    // we don't want to define it in ByteSource interface; caller should not depend on it.
    public static final Async<ByteBuffer> EOF = End.async();

    public static boolean unchecked(Throwable throwable)
    {
        if(throwable==null)
            throw new NullPointerException();
        return (throwable instanceof RuntimeException) || (throwable instanceof Error);
    }

    // only used during dev process. otherwise there should be no usage of this method.
    synchronized static public void debug(Object... args)
    {
        System.out.print("#=#=#=#= ");
        for(Object arg : args)
        {
            if(arg instanceof Integer || arg instanceof Long)
                System.out.printf("%,d", arg);
            else
                System.out.print(arg);
            System.out.print(" ");
        }
        System.out.println();
    }

    static int osType; // 1=Windows, 2=Others
    public static boolean isWindows()
    {
        int _osType = osType;
        if(_osType==0)
        {
            String osName = System.getProperty("os.name");
            osType = _osType = (osName!=null && osName.startsWith("Windows")) ? 1 : 2;
        }
        return _osType==1;
    }

    public static void require(boolean condition, String conditionString) throws IllegalArgumentException
    {
        if(!condition)
            throw new IllegalArgumentException("required: "+conditionString);
    }

    @SuppressWarnings("unchecked")
    public static <T> T cast(Object obj)
    {
        return (T)obj;
    }

    public static <T> ArrayList<T> toArrayList(Stream<T> stream)
    {
        ArrayList<T> list = new ArrayList<>();
        stream.forEach(list::add);
        return list;
    }

    public static Object[] toArray(Stream<?> stream)
    {
        ArrayList<?> list = toArrayList(stream);
        return list.toArray();
    }

    public static <T> List<T> asReadOnlyList(Object... array)
    {
        return new _Array2ReadOnlyList<>(array);
    }

    // a validator
    // Objects::requireNonNull does not work; so we need this validator constant. for boxed primitives mostly.
    public static final Consumer<Object> requireNonNull =
        obj->{ if(obj==null) throw new NullPointerException(); };


    // depends on if error is a checked exception
    //     checked: network error etc. unsurprising. can be frequent. by default don't print it.
    //   unchecked: more serious error. shouldn't happen; need to be investigated; print by default.
    public static void logErrorOrDebug(_Logger logger, Throwable error)
    {
        _Logger._Level level = unchecked(error) ? _Logger._Level.ERROR : _Logger._Level.DEBUG;
        logger.log(level, "%s", error);
    }

    public static void logUnexpected(_Logger logger, Throwable t) // not supposed to happen, should print by default
    {
        logger.error("Unexpected error: %s", t);
    }

    public static void closeNoThrow(AutoCloseable closeable, _Logger logger)
    {
        try
        {
            closeable.close();
        }
        catch(Exception e)
        {
            logUnexpected(logger, e);
        }
    }



    // ref contains hash of the msg and a unique id, , e.g. "84ef2ff0#3"
    // typically, app logs the msg with the ref, then show the ref to end user.
    // when user reports an error with the ref, dev can easily locate the log entry.
    // the hash is to hide the real msg, but easy to know different refs are from the same msg.
    static public String msgRef(String msg)
    {
        return hash(msg)+"#"+msgId.getAndIncrement();
    }
    static AtomicInteger msgId = new AtomicInteger(0);
    // the salt is different from VM to VM, so hash for same msg will be different from VM to VM.
    // we could let dev config msgHashSalt to have more consistent hash across vms
    static byte[] msgHashSalt = new byte[16];
    static { ThreadLocalRandom.current().nextBytes(msgHashSalt); }
    static String hash(String msg)
    {
        return _CryptoUtil.md5(msgHashSalt, 4, msg);
        // 4 bytes is large enough for our purpose. we don't give client a huge string.
        // collision is unlikely, and collision is fine.
    }



    public static void printStackTrace(Exception e, Consumer<CharSequence> out)
    {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        out.accept(sw.toString());
    }

    public static void deleteDir(Path dir) throws Exception
    {
        if(!Files.exists(dir))
            return;

        if(!Files.isDirectory(dir)) // uh?
        {
            Files.delete(dir); // throws
            return;
        }

        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if(exc!=null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 1024, visitor);
    }

    public static RuntimeException sneakyThrow(Throwable t)
    {
        throw _Util.<RuntimeException>sneakyThrow0(t);
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow0(Throwable t) throws T
    {
        throw (T)t;
    }

    public static int digits(char[] chars, int start, int end)
    {
        int x=0;
        for(int i=start; i<end; i++)
        {
            char d = chars[i];
            if(d<'0'||d>'9') return -1;
            x = x*10 + (d-'0');
        }
        return x;
    }

    public static boolean booleanProp(boolean defaultValue, String propName)
    {
        String s = System.getProperty(propName);
        if(s==null)
            return defaultValue;
        return "true".equalsIgnoreCase(s);
    }


    public static long bytes2long(byte[] bb, int p, int L)
    {
        long x = 0;
        while(p<L)
        {
            x <<= 8;
            x  |= bb[p] & 0xffL;
            p++;
        }
        return x;
    }

}
