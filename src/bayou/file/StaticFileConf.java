package bayou.file;

import _bayou._http._HttpUtil;
import bayou.http.HttpEntity;
import bayou.mime.ContentType;
import bayou.mime.FileSuffixToContentType;
import bayou.mime.HeaderMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

/**
 * Configuration of a single file in a StaticHandler.
 * <p>
 *     A <code>StaticFileConf</code> contains file metadata like {@link #fileSize},
 *     and config methods like {@link #exclude(boolean)}.
 *     It is created with default config values, then
 *     passed to StaticHandler's
 *     <a href="StaticHandler.html#confMod">confMod</a> to be modified.
 * </p>
 * <p>
 *     Example usage:
 * </p>
 * <pre>
 *     new StaticHandler("/uri", "/dir", conf-&gt;
 *     {
 *         if(conf.fileSize&lt;5000)
 *             conf.cache(true); // cache the file in memory if it's small enough
 *
 *         conf.header("Cache-Control", "public");
 *     });
 * </pre>
 */
public class StaticFileConf
{
    /**
     * The path of the file.
     */
    public final Path filePath;
    /**
     * The size of the file.
     */
    public final long fileSize;
    /**
     * When the file was created.
     */
    public final Instant fileCreationTime;
    /**
     * When the file was last modified.
     */
    public final Instant fileLastModified;

    /**
     * Create a StaticFileConf with default values.
     */
    // why public - maybe user need it for unit testing
    public StaticFileConf(Path filePath) throws Exception
    {
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class); // throws
        // follow sym link. attrs is of the target file.
        // shouldn't throw NoSuchFileException, because `file` is from FileMonitor

        this.filePath = filePath;
        this.fileSize = attrs.size();
        this.fileCreationTime = attrs.creationTime().toInstant();
        this.fileLastModified = attrs.lastModifiedTime().toInstant();

        // init values

        isIndexFile = filePath.endsWith("index.html"); // case-insensitive on windows

        contentType = FileSuffixToContentType.getGlobalInstance().find(filePath.toString());

        gzip = fileSize>1024 && contentType!=null &&
            ( contentType.type().equals("text") || contentType.types().equals("application/javascript"));

        etag = _HttpUtil.defaultEtag(fileLastModified, null) ;
    }




    boolean exclude = false;

    /**
     * Whether to exclude this file.
     * <p><code>
     *     default: false
     * </code></p>
     * <p>
     *     If excluded, this file will not be served by the StaticHandler;
     *     if a request URI maps to this file, a 404 response will be generated.
     * </p>
     * @return `this`
     */
    public StaticFileConf exclude(boolean exclude)
    {
        this.exclude=exclude;
        return this;
    }





    boolean gzip; // init by constructor
    // fileSize: a file can be too small to benefit from gzip. (tho not much harm if it's gzip-ed anyway)
    // if gzip=true, the gzip-ed content can be cached in memory or on disk, depending on `cache`.

    /**
     * Whether this file should be compressed by gzip.
     * <p><code>
     *     default: true iff fileSize&gt;1024 and the default contentType is text/* or application/javascript
     * </code></p>
     * <p>
     *     Do not pre-gzip the file; just store the original file under the directory,
     *     StaticHandler will take care of caching the compressed file on disk or in memory.
     * </p>
     * @return `this`
     */
    public StaticFileConf gzip(boolean gzip)
    {
        this.gzip=gzip;
        return this;
    }





    boolean cache = false;
    // if cache && gzip, only the gzip-ed content is cached, original content is not.
    //    if client doesn't accept gzip (e.g. apache ab) we'll read disk for original content.

    /**
     * Whether to cache the file content in memory.
     * <p><code>
     *     default: false
     * </code></p>
     * @return `this`
     */
    public StaticFileConf cache(boolean cache)
    {
        this.cache=cache;
        return this;
    }





    boolean isIndexFile; // init by constructor

    /**
     * Whether this is the index file for the parent directory.
     * <p><code>
     *     default: true iff filePath.endsWith("index.html")
     * </code></p>
     * <p>
     *     If a request URI maps to a directory, the index file (if there is one) will be served.
     * </p>
     * <p>
     *     If a directory contains more than one index files, one of them is used;
     *     but it is uncertain which one is picked.
     * </p>
     * @return `this`
     */
    public StaticFileConf isIndexFile(boolean isIndexFile)
    {
        this.isIndexFile=isIndexFile;
        return this;
    }






    Duration expiresRelative = Duration.ofSeconds(60); // a new Duration obj for each file. probably not big deal.
    Instant  expiresAbsolute = null;
    // Expires should be within a year, according to http spec.
    // we don't bother app to know that. the server will cut it off if it's too far in future.

    /**
     * The expiration time of this file.
     * <p><code>
     *     default: expiresAbsolute=null, expiresRelative=Duration.ofSeconds(60)
     * </code></p>
     * <p>
     *     <code>expiresAbsolute</code> can be a time in the past;
     *     <code>expiresRelative</code> can be a negative duration.
     * </p>
     * <p>
     *     The <code>Expires</code> response header
     *     can be specified either absolutely or relatively.
     *     If relatively, the header value will be <code>(responseTime+expiresRelative)</code>.
     * </p>
     * <p>
     *     If both <code>expiresAbsolute</code> and <code>expiresRelative</code> are null,
     *     there will be no <code>Expires</code> header.
     *     If both are non-null, this method throws <code>IllegalArgumentException</code>.
     * </p>
     * <p>
     *     Note: however, if the request URI is <a href="StaticHandler.html#tagged-uri">tagged</a>,
     *     the <code>Expires</code> response header could be set to a distant future,
     *     ignoring the settings here.
     * </p>
     * @return `this`
     */
    public StaticFileConf expires(Instant expiresAbsolute, Duration expiresRelative) throws IllegalArgumentException
    {
        if(expiresAbsolute!=null && expiresRelative!=null)
            throw new IllegalArgumentException("both expiresAbsolute and expiresRelative are non-null");
        // if both are non-null, we could pick expiresAbsolute as the superseding value.
        // however, that may not be obvious to someone reading the method invocation code. so disallow it.

        this.expiresAbsolute=expiresAbsolute;
        this.expiresRelative=expiresRelative;
        return this;
    }
    // we can add two convenience methods if necessary
    //     expires(Instant  expiresAbsolute)
    //     expires(Duration expiresRelative)





    ContentType contentType; // init by constructor
    // if content type is unknown, it's probably better to leave it as null, instead of some
    // surrogate value like "application/octet-stream". let the client decide based on whatever heuristics.

    /**
     * The content type of this file.
     * <p><code>
     *     default: FileSuffixToContentType.getGlobalInstance().find(filePath.toString())
     * </code></p>
     * <p>
     *     <code>null</code> is allowed, if the content type is unknown. The default value could be null.
     * </p>
     * @see bayou.mime.FileSuffixToContentType
     * @return `this`
     */
    public StaticFileConf contentType(ContentType contentType)
    {
        this.contentType=contentType;
        return this;
    }





    String etag; // init by constructor

    /**
     * The ETag of this file.
     * <p><code>
     *     default: a string representing fileLastModified in nano-second precision,
     *     e.g. "t-53319ee8-623a7c0"
     * </code></p>
     * <p>
     *     See {@link HttpEntity#etag()} for requirements for this ETag.
     * </p>
     * <p>
     *     If a response for this file uses <code>"Content-Encoding: gzip"</code>,
     *     its ETag will be this ETag appended with <code>".gzip"</code>.
     * </p>
     * @return `this`
     */
    public StaticFileConf etag(String etag) throws IllegalArgumentException
    {
        _HttpUtil.validateEtag(etag);
        this.etag=etag;
        return this;
    }





    /**
     * Additional response headers for this file.
     * <p>
     *     This map is initially empty; <code>confMod</code> can populate it,
     *     e.g. with <code>Cache-Control, Content-Language</code>.
     * </p>
     * @see #header(String, String)
     */
    public final HeaderMap headers = new HeaderMap();
    // app can read/write. better use header(n,v) for write

    /**
     * Add a {@link #headers response header}.
     * <p>
     *     For example: <code>conf.header("Cache-Control", "public");</code>
     * </p>
     * @return `this`
     */
    public StaticFileConf header(String name, String value) throws IllegalArgumentException
    {
        // if value=null, we could remove the header from `this.headers`.
        // however, the user may think it means the header does not exist in response. e.g. header("Etag", null).
        // to avoid confusion, we do not support remove in this method.

        _HttpUtil.checkHeader(name, value);
        headers.put(name, value);
        return this;
    }



    // getters. not important to apps ==================================================================
    // do not give them javadoc. leave them blank on the method summary table


    public boolean get_exclude(){ return exclude; }

    public boolean get_gzip(){ return gzip; }

    public boolean get_cache(){ return cache; }

    public boolean get_isIndexFile(){ return isIndexFile; }

    public Duration get_expiresRelative(){ return expiresRelative; }
    public Instant  get_expiresAbsolute(){ return expiresAbsolute; }

    public ContentType get_contentType(){ return contentType; }

    public String get_etag(){ return etag; }


}
