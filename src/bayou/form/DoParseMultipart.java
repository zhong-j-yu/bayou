package bayou.form;

import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.bytes.ByteSource;
import bayou.mime.*;
import bayou.util.OverLimitException;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Map;

import bayou.mime.MultipartPart;

class DoParseMultipart
{
    Charset charset;

    int maxEntryKeyBytes;

    int maxParamEntries;
    long maxParamValueTotalBytes;

    int maxFileEntries;
    int maxFileNameBytes;
    long maxFileSize;
    Path tmpFileDir;

    MultipartParser mpp;

    FormData formData;

    int numParamEntries;
    int numFileEntries;

    long numParamValueTotalBytes;
    byte[] bytes;
    int ib;

    DoParseMultipart(FormParser conf, String action, String boundary, ByteSource body)
    {
        this.charset = conf.charset;

        this.maxEntryKeyBytes = conf.maxEntryKeyBytes;

        this.maxParamEntries = conf.maxParamEntries;
        this.maxParamValueTotalBytes = conf.maxParamValueTotalBytes;

        this.maxFileEntries = conf.maxFileEntries;
        this.maxFileNameBytes = conf.maxFileNameBytes;
        this.maxFileSize = conf.maxFileSize;

        this.tmpFileDir = conf.tmpFileDir;

        mpp = new MultipartParser(body, boundary);
        // typical part headers
        //    Content-Disposition: form-data; name="files"; filename="file1.txt"
        //    Content-Type: text/plain
        int maxHeaderValueBytes = conf.maxEntryKeyBytes + conf.maxFileNameBytes + 128;
        mpp.maxHeaderNameBytes(64);
        mpp.maxHeaderValueBytes(maxHeaderValueBytes);
        mpp.maxHeadTotalBytes(3 * maxHeaderValueBytes);

        formData = new FormData("POST", action, charset, FormData.ENC_MULTIPART);

    }


    Async<FormData> parse()
    {
        // one possible error is OverLimitException/maxHeaderValueBytes,
        // which is very likely because the filename is too long, which is probably innocent mistake.
        // other OverLimitException/ParseException are probably from rogue clients.

        return AsyncIterator.<MultipartPart>forEach_(mpp::getNextPart, this::handleNextPart)
            .map(v ->
            {
                FormData result = formData;
                formData = null;
                return result;
            })
            .finally_(() ->
            {
                mpp.close();

                if (formData != null)
                    formData.deleteFiles();
            });
    }

    Async<Void> handleNextPart(MultipartPart part) throws OverLimitException, ParseException
    {
        Map<String,String> headers = part.headers();
        String hvContentDisposition = headers.get(Headers.Content_Disposition);
        String hvContentType = headers.get(Headers.Content_Type);

        if(hvContentDisposition==null)
            throw new ParseException("missing Content-Disposition header", 0);
        TokenParams csvElement = TokenParams.parse(hvContentDisposition); // no throw
        if(!csvElement.token().equalsIgnoreCase("form-data"))
            throw new ParseException("invalid Content-Disposition header", 0);

        String entryName = csvElement.params().get("name");  // Latin1
        if(entryName==null)  // empty name is ok?
            throw new ParseException("invalid Content-Disposition header", 0);
        if(entryName.length()> maxEntryKeyBytes)
            throw new OverLimitException("maxEntryKeyBytes", maxEntryKeyBytes);
        entryName = convertCharset(entryName);

        String filename = csvElement.params().get("filename"); // Latin1
        if(filename==null) // this should be a param part. it should not have Content-Type
        {
            if(hvContentType!=null) // we don't know how to make sense of this
                throw new ParseException("part with Content-Type but without filename", 0);

            numParamEntries++;
            if(numParamEntries>maxParamEntries)
                throw new OverLimitException("maxParamEntries", maxParamEntries);

            return partBodyToParamValue(entryName, part.body());
        }

        // file entry.

        if(filename.isEmpty())
            return Async.VOID;
        // this is a spurious file, browser sends it because the <input type="file"> is not filled.
        // html5 spec:
        //     If there are no selected files, then append an entry to the form data set with the name as the name,
        //     the empty string as the value, and application/octet-stream as the type.
        // skip it. the part body is not read, that's ok.
        // we only check filename="". no real file can have an empty name, I assume.

        numFileEntries++;
        if(numFileEntries>maxFileEntries)
            throw new OverLimitException("maxFileEntries", maxFileEntries);

        if(filename.length()>maxFileNameBytes) // not likely, since mpp limited header value bytes
            throw new OverLimitException("maxFileNameBytes", maxFileNameBytes);
        filename = convertCharset(filename);

        ContentType contentType = null;
        if(hvContentType!=null)
        {
            try
            {
                contentType = ContentType.parse(hvContentType);
            }
            catch (IllegalArgumentException e)
            {
                throw new ParseException("invalid Content-Type header", 0);
            }
        }

        return partBodyToFormFile(entryName, filename, contentType, part.body());
    }

    // what we really get are actually bytes, not chars. now convert them to chars.
    String convertCharset(String latin1Chars)
    {
        byte[] bytes = new byte[latin1Chars.length()];
        for(int i=0; i<latin1Chars.length(); i++)
            bytes[i] = (byte)latin1Chars.charAt(i);
        return new String(bytes, charset);  // malformed bytes are ok
    }





    Async<Void> partBodyToParamValue(final String entryName, final ByteSource partBody)
    {
        return AsyncIterator.forEach(partBody::read, this::saveParamValueBytes)
            .then(v -> {
                String value = bytes == null ? "" :
                    new String(bytes, 0, ib, charset); // ok if bytes are malformed
                ib = 0;
                formData.param(entryName, value);
                return Async.VOID;
            });
        // no need to close partBody
    }
    void saveParamValueBytes(ByteBuffer bb) throws Exception
    {
        int L = bb.remaining();
        numParamValueTotalBytes += L;
        if(numParamValueTotalBytes >maxParamValueTotalBytes)
            throw new OverLimitException("maxParamValueTotalBytes", maxParamValueTotalBytes);

        if(bytes ==null)
            bytes = new byte[Math.max(64, L)];
        else if(ib + L > bytes.length)
            bytes = Arrays.copyOf(bytes, Math.max(ib + L, bytes.length*3/2));

        bb.get(bytes, ib, L);
        ib += L;
    }






    Async<Void> partBodyToFormFile(final String entryName, String filename, ContentType contentType, ByteSource partBody)
    {
        bytes = null; // we probably won't be needing it. it can be large, so free it now.

        return new DoSaveToTmpFile(filename, contentType, maxFileSize, tmpFileDir, partBody)
            .start()
            .then(formDataFile ->
            {
                formData.file(entryName, formDataFile);
                return Async.VOID;
            });
    }

}
