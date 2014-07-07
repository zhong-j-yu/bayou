package _bayou._tmp;

import java.util.AbstractList;
import java.util.List;

// wrap an array as read-only list
public class _Array2ReadOnlyList<E> extends AbstractList<E>
{
    final Object[] sourceArray;
    final int offset, size;

    public _Array2ReadOnlyList(Object[] sourceArray)
    {
        this.sourceArray = sourceArray;
        this.offset = 0;
        this.size = sourceArray.length;
    }
    public _Array2ReadOnlyList(Object[] sourceArray, int offset, int size)
    {
        assert offset+size <= sourceArray.length;

        this.sourceArray = sourceArray;
        this.offset = offset;
        this.size = size;
    }

    public E get(int index)
    {
        if(index>=size)
            throw new IndexOutOfBoundsException();
        Object element = sourceArray[index+offset];
        return _Util.cast(element);
    }
    public int size()
    {
        return size;
    }
}
