package bayou.form;

import _bayou._str._HexUtil;
import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.bytes.ByteSource;
import bayou.util.OverLimitException;

import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Arrays;

class DoParseUrlEncoded
{
    static class Parser
    {
        final Charset charset;
        final int maxEntryKeyBytes;
        final int maxParamEntries;
        final long maxParamValueTotalBytes;

        int nv;  // [0] name [1] value
        int pct; // [0] none [1] % [2] %H
        int h1;

        byte[] buffer = new byte[100]; // can grow;
        int iBuffer;
        long valueTotalBytes;

        int entries;
        String currEntryName;
        final FormData formData;

        Parser(String method, String action, FormParser conf)
        {
            this.charset = conf.charset;
            this.maxEntryKeyBytes = conf.maxEntryKeyBytes;
            this.maxParamEntries = conf.maxParamEntries;
            this.maxParamValueTotalBytes = conf.maxParamValueTotalBytes;

            formData = new FormData(method, action, charset, FormData.ENC_URLENCODED);
        }

        FormData end() throws ParseException, OverLimitException
        {
            parse((byte)'&');
            return formData;
        }

        void parse(byte b) throws ParseException, OverLimitException
        {
            if(pct==0)
            {
                if(b=='%')  // ok to perform b==c, if c is ascii
                    pct=1;
                else if(b=='&')
                {
                    if(nv==1)
                    {
                        saveValue();
                        nv=0;
                    }
                    else if(iBuffer>0) // e.g.  ...&name&...
                    {
                        saveName();
                        saveValue();  // empty
                    }
                }
                else if(b=='=' && nv==0)
                {
                    saveName();  // can be empty
                    nv=1;
                }
                else if(b=='+')
                    save((byte)' ');
                else
                    save(b);
            }
            else // pct == 1 or 2
            {
                int h = _HexUtil.hex2int[ b & 0xFF ];
                if(h==-1)
                    throw new ParseException("HH expected after %",0);
                if(pct==1)
                {
                    h1 = h;
                    pct = 2;
                }
                else // pct==2
                {
                    byte hh = (byte)( h1<<4 | h );
                    save(hh);
                    pct=0;
                }
            }
        }

        void save(byte b) throws OverLimitException
        {
            if(nv==0)
            {
                if(iBuffer>= maxEntryKeyBytes)
                    throw new OverLimitException("maxEntryKeyBytes", maxEntryKeyBytes);
            }
            else
            {
                if(valueTotalBytes>=maxParamValueTotalBytes)
                    throw new OverLimitException("maxParamValueTotalBytes", maxParamValueTotalBytes);
                valueTotalBytes++;
            }

            if(iBuffer==buffer.length)
                buffer = Arrays.copyOf(buffer, buffer.length*3/2); // throws

            buffer[ iBuffer++ ] = b;
        }
        String str()
        {
            int len = iBuffer;
            iBuffer = 0;
            return new String(buffer, 0, len, charset); // ok if bytes are malformed
        }
        void saveName() throws OverLimitException
        {
            if(entries>=maxParamEntries)
                throw new OverLimitException("maxParamEntries", maxParamEntries);
            entries++;
            currEntryName = str();
        }
        void saveValue()
        {
            formData.param(currEntryName, str());
            currEntryName = null;
        }
    }


    static Async<FormData> parsePostBody(FormParser conf, String action, ByteSource body)
    {
        Parser parser = new Parser("POST", action, conf);

        return AsyncIterator.forEach(body::read,
            bb ->
            {
                while (bb.hasRemaining())
                    parser.parse(bb.get()); // throws
            })
            .map(v -> parser.end())
            .finally_(body::close);
    }

    // from uri query
    // uri may be /path[?query] or http[s]://host/path[?query]
    static FormData parseUri(FormParser conf, CharSequence uri) throws ParseException, OverLimitException
    {
        // query is everything after the 1st '?' in uri. query can be empty.
        int i = 0;
        for(; i<uri.length(); i++)
            if(uri.charAt(i)=='?')
                break;
        String action = uri.subSequence(0, i).toString();
        i++;

        Parser parser = new Parser("GET", action, conf);

        for(; i<uri.length(); i++)
            parser.parse((byte) uri.charAt(i));

        return parser.end();
    }

}
