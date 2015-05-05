package bayou.form;

import _bayou._str._CharDef;
import _bayou._str._HexUtil;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class DoGenUrlEncoded
{
    byte[] bytes = new byte[64];
    int ib;

    void append(int c)
    {
        if(ib==bytes.length)
            bytes = Arrays.copyOf(bytes, ib*3/2);
        bytes[ib++] = (byte)c;
    }

    DoGenUrlEncoded(Charset charset, Map<String,List<String>> parameters, Map<String,List<FormDataFile>> files)
    {
        for(Map.Entry<String,List<String>> x : parameters.entrySet())
        {
            String name = x.getKey();
            byte[] nameBytes = name.getBytes(charset);
            for(String value : x.getValue())
                addPair(nameBytes, value, charset);
        }

        // files: only fileName is encoded
        for(Map.Entry<String,List<FormDataFile>> x : files.entrySet())
        {
            String name = x.getKey();
            byte[] nameBytes = name.getBytes(charset);
            for(FormDataFile fdf : x.getValue())
                addPair(nameBytes, fdf.fileName, charset);
        }
    }

    void addPair(byte[] nameBytes, String value, Charset charset)
    {
        byte[] valueBytes = value.getBytes(charset);
        if(ib!=0)
            append('&');
        escape(nameBytes);
        append('=');
        escape(valueBytes);
    }

    void escape(byte[] bytes)
    {
        for(byte b : bytes)
        {
            int u = 0xff & b;
            if(_CharDef.check(u, _CharDef.Html.safeQueryChars))
                append( u );
            else if(u==' ')
                append('+');
            else
            {
                append('%');
                append(_HexUtil.int2hex[ u >> 4   ]);
                append(_HexUtil.int2hex[ u & 0x0f ]);
            }
        }
    }

}
