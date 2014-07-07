package _bayou._tmp;

import _bayou._log._Logger;

import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class _StreamIter<T> implements Consumer<T>
{
    final Stream<T> stream;
    final Spliterator<T> iter;

    public _StreamIter(Stream<T> stream)
    {
        this.stream = stream;
        this.iter = stream.spliterator();
    }

    T element;
    @Override
    public void accept(T t)
    {
        element = t;
    }

    // return null if no more
    public T next()
    {
        if(!iter.tryAdvance(this))
        {
            // close(); // unnecessary. user should call close()
            return null;
        }

        if(element==null)
            throw new NullPointerException("stream contains null element");

        T tmp = element;
        element = null;
        return tmp;
    }

    public void close()
    {
        try
        {
            stream.close();
        }
        catch (Exception e)
        {
            _Logger.of(_StreamIter.class)
                .error("Exception from Stream.close(), stream=%s, exception=%s", stream, e);
        }
    }
}
