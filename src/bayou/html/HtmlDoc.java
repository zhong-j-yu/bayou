package bayou.html;

import bayou.http.HttpResponseImpl;
import bayou.http.HttpStatus;
import bayou.mime.ContentType;
import bayou.text.TextDoc;
import bayou.text.TextHttpEntity;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Html document.
 */
public interface HtmlDoc extends TextDoc
{

    /**
     * Get the content type of this document, by default, "text/html;charset=UTF-8".
     * <p>
     *     If {@link #getCharset()} is overridden (and this method is not),
     *     this method will return a content type with the charset parameter provided by {@link #getCharset()}.
     * </p>
     */
    @Override
    default public ContentType getContentType()
    {
        Charset charset = getCharset();
        if(charset== StandardCharsets.UTF_8)
            return ContentType.text_html_UTF_8;
        else // subclass overrides getCharset() but not getContentType()
            return new ContentType("text", "html", "charset", charset.name());
    }

    /**
     * Get the charset of this document, by default "UTF-8".
     */
    @Override
    default public Charset getCharset()
    {
        return StandardCharsets.UTF_8;
    }

    /**
     * Create an http response serving this document.
     */
    default HttpResponseImpl toResponse(int statusCode)
    {
        return new HttpResponseImpl(HttpStatus.of(statusCode), new TextHttpEntity(this));
    }
    // maybe move this method to TextDoc. later.

}
