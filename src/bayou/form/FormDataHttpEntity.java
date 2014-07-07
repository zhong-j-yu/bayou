package bayou.form;

import bayou.bytes.ByteSource;
import bayou.bytes.SimpleByteSource;
import bayou.file.FileByteSource;
import bayou.http.HttpEntity;
import bayou.mime.*;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

class FormDataHttpEntity implements HttpEntity
{
    String boundary;
    ArrayList<MultipartPart> parts;

    FormDataHttpEntity(Map<String, List<String>> parameters, Map<String, List<FormDataFile>> files, Charset charset)
    {
        boundary = "----------"+ MultipartByteSource.createRandomBoundary(22).toString();

        parts = new ArrayList<>();

        for(Map.Entry<String,List<String>> x : parameters.entrySet())
        {
            String name = x.getKey();
            for(String value : x.getValue())
                parts.add( new ParamPart(name, value, charset) );
        }

        for(Map.Entry<String,List<FormDataFile>> x : files.entrySet())
        {
            String name = x.getKey();
            for(FormDataFile ff : x.getValue())
                parts.add( new FilePart(name, ff, charset));
        }
    }

    @Override
    public ByteSource body()
    {
        return new MultipartByteSource(parts.stream(), boundary);
    }

    @Override
    public Long contentLength()
    {
        return null;  // we could count it. later. may be necessary if server doesn't support chunked request.
    }

    @Override
    public ContentType contentType()
    {
        return new ContentType("multipart","form-data", "boundary", boundary);
    }




    // entry names and file names, after encoded to bytes, are in quoted strings.
    //     Content-Disposition: form-data; name="###"; filename="####"
    // not all bytes are ok. for now, CR LF are illegal bytes. (see _CharDef.Rfc822.fieldBodyCharsX)
    // the recipient may be stricter and reject bytes we deem ok.
    // since charset is ASCII-compatible, illegal byte (<0x80) means illegal char in original string.
    // we don't check bytes here; illegal bytes in entry/file names are highly unlikely.
    // later MultipartByteSource will check header values of Content-Disposition and fail on illegal bytes.
    static void quoted(StringBuilder sb, Charset charset, String name)
    {
        sb.append('"');
        for(byte b : name.getBytes(charset))
        {
            char c = (char)(0xff & b);
            if(c=='"' || c=='\\')
                sb.append('\\');
            sb.append( c );
        }
        sb.append('"');
    }

    static class ParamPart implements MultipartPart
    {
        String hvContentDisposition;
        byte[] valueBytes;

        ParamPart(String name, String value, Charset charset)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("form-data; name=");
            quoted(sb, charset, name);
            hvContentDisposition = sb.toString();

            valueBytes = value.getBytes(charset); // can be arbitrary bytes
        }

        @Override
        public Map<String, String> headers()
        {
            return Collections.singletonMap(Headers.Content_Disposition, hvContentDisposition);
        }

        @Override
        public ByteSource body()
        {
            return new SimpleByteSource(valueBytes);
        }
    }









    static class FilePart implements MultipartPart
    {
        String hvContentDisposition;
        String hvContentType;
        Path filePath;

        FilePart(String name, FormDataFile ff, Charset charset)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("form-data; name=");
            quoted(sb, charset, name);
            sb.append("; filename=");
            quoted(sb, charset, ff.fileName);
            hvContentDisposition = sb.toString();

            if(ff.contentType!=null)
                hvContentType = ff.contentType.toString();  // chars already checked
            else
                hvContentType = "application/octet-stream"; // to be safe, add a default one

            filePath = ff.localPath;
        }

        @Override
        public Map<String, String> headers()
        {
            LinkedHashMap<String,String> map = new LinkedHashMap<>(2, 0.5f);  // order may matter to receiver
            map.put(Headers.Content_Disposition, hvContentDisposition);
            map.put(Headers.Content_Type, hvContentType);
            return map;
        }

        @Override
        public ByteSource body()
        {
            return new FileByteSource(filePath);
        }
    }

}
