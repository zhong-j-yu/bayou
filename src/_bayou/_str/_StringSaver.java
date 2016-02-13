package _bayou._str;

import _bayou._tmp._Array2ReadOnlyList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

// save some Strings
public class _StringSaver
{
    String[] array;
    int arrayX;

    int charCount = 0;

    public _StringSaver(int initialCapacity)
    {
        assert initialCapacity>0;
        array = new String[initialCapacity];
    }

    // for method chaining
    public _StringSaver append(String str)
    {
        if(arrayX<array.length)
        {
            array[arrayX++] = str;
        }
        else
        {
            array = Arrays.copyOf(array, array.length*2);
            array[arrayX++] = str;
        }

        charCount += str.length();
        return this;
    }

    public byte[] toLatin1Bytes()
    {
        byte[] bytes = new byte[charCount];
        int j=0;

        for(int x=0; x<arrayX; x++)
        {
            String str = array[x];
            for (int i = 0; i < str.length(); i++)
                bytes[j++] = (byte) str.charAt(i);
        }
        return bytes;
    }

    public List<String> toList()
    {
        return new _Array2ReadOnlyList<String>(array, 0, arrayX);
    }

}
