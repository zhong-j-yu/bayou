package bayou.file;

import _bayou._http._HttpDate;
import _bayou._http._HttpUtil;
import _bayou._log._Logger;
import _bayou._str._CharDef;
import _bayou._str._HexUtil;
import _bayou._tmp.*;
import bayou.bytes.ByteSource;
import bayou.bytes.ByteSource2InputStream;
import bayou.bytes.ByteSourceCache;
import bayou.gzip.GzipByteSource;
import bayou.http.*;
import bayou.mime.ContentType;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.util.function.ConsumerX;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static bayou.mime.Headers.Accept_Encoding;

/**
 * HttpHandler for static files.
 * <p>
 *     A <code>StaticHandler</code> maps a <code>uriPrefix</code> to a <code>dirPrefix</code>;
 *     a URI with the <code>uriPrefix</code> is mapped to a file with the <code>dirPrefix</code>.
 *     For example, if <code>uriPrefix="/uri", dirPrefix="/dir"</code>,
 *     the URI <code>"/uri/x/y.txt"</code> is mapped to the file <code>"/dir/x/y.txt"</code>.
 *     Each file can be configured individually by <code>confMod</code>.
 *     See constructor {@link #StaticHandler(String, String, ConsumerX) StaticHandler(uriPrefix, dirPrefix, confMod)}.
 * </p>
 * <p>
 *     URI encoding uses UTF-8 only; no other charset is supported.
 * </p>
 * <p>
 *     A <code>StaticHandler</code> can be used directly for an {@link HttpServer},
 *     if the server serves nothing but static files.
 *     More often, a <code>StaticHandler</code> is used in a parent handler that servers
 *     both static files and dynamic contents. For example
 * </p>
 * <pre>
 *     StaticHandler staticHandler = new StaticHandler("/uri", "/dir");
 *
 *     HttpHandler appHandler = request -&gt;
 *     {
 *         // try static files first
 *         HttpResponseImpl resp = staticHandler.handle(request);
 *         if(resp.statusCode()!=404) // 200, or other error codes
 *             return resp;
 *
 *         // 404, request URI is not mapped to a static file
 *         ...
 *     };
 * </pre>
 * <p>
 *     See {@link #handle(HttpRequest)} for types of responses StaticHandler may generate.
 * </p>
 * <h4 id=tagged-uri>Tagged URI</h4>
 * <p>
 *     <code>StaticHandler</code> supports tagged URI which embeds the
 *     {@link StaticFileConf#etag ETag} of the file.
 *     Response to a tagged URI never expires and can be cached forever.
 *     For example, if currently <code>ETag="v1.2.3"</code> for file <code>"/dir/css/main.css"</code>,
 *     the tagged URI is <code>"/uri/css/main.css?v1.2.3"</code>.
 *     This URI can be obtained by {@link #uri(String) staticHandler.uri("css/main.css")}.
 * </p>
 * <pre>
 *     // an html template
 *     _link().rel("stylesheet").type("text/css").href( staticHandler.uri("css/main.css) )
 * </pre>
 * <p>
 *     The browser can request <code>"/uri/css/main.css?v1.2.3"</code> once and cache it forever.
 *     When the ETag is changed (because the file is modified), a new tagged URI is generated,
 *     and the browser will issue a new request to get the new content.
 * </p>
 */

// only tested on default file system.

// we are committed to return an HttpResponseImpl from handle(request), not an Async<HttpResponse>.
// also, uri(file) should be fast and non-blocking.
// therefore we have to cache all file metadata in memory.
// this can be a problem if there are way too many files under the directory.

// an web app may have multiple server instances with different root dirs (on diff machines)
// the file date should be consistent, regardless which dir it's under. e.g. date = VCS commit date.

// we use FileMonitor to monitor file changes. see its javadoc for limitations (mostly about sym link)
// bad cases:
//     if there's a sym link to another file, modify the file, monitor won't be aware,
//         so we still have the old info. if doCache=false, when the file is requested
//         we'll read disk, and almost certainly, have a wrong Content-Length.
//         "touch -h" to touch the sym link itself, so its info is refreshed.

// when updating a file while the handler is running, write to a tmp file (should be excluded by conf)
// (set a consistent date if there are multiple server instances),
// then mv -f the tmp file to the origin file.
// linux: if old file is being served, no problem if it's deleted; old content is still being served to clients.
//    new requests will pick up the new content from new file.
// windows: cannot delete a file if it's open and being read. so on a busy server, we may not be able to switch file.

// firefox bug: https://bugzilla.mozilla.org/show_bug.cgi?id=407172
//   it auto encodes single quote (') which violates the URI spec.
//   (%27) and (') are semantically different in URI path.
// if one of our URI contains ('), in uriPrefix or relative file path, it won't work in firefox


// error messages (400,404,405,500) are very simple. we don't provide a way of customizing them in this class.
// usually 400/405/500 don't need customization. in some apps 404 may be visible to end user and require customization.
// caller of handle() can do any transformation on the returned response.

// performance: apache ab -k -c8 -n100000 http://localhost:8080/...
// small files only, < 1kB
// cached: 45k req/sec
//    note: ab does not accept gzip, therefore if cache=true, gzip=true, we actually read disk, not memory, that's slow.
// not cached:  20k req/sec windows, 30k req/sec linux
//    this is if we share file channel.
//    if not shared, 10k windows, 20k linux

public class StaticHandler implements HttpHandler
{

    static final _Logger logger = _Logger.of(StaticHandler.class);

    // uriPrefix and dirPrefix both end with slash
    final UriPath uriPrefix;
    final String  dirPrefix;  // absolute, normalized

    final ConsumerX<StaticFileConf> confMod;
    // can throw. can do block IO

    final ConcurrentHashMap<UriPath, FileInfo> uri2info = new ConcurrentHashMap<>();
    // r/w by fileMonitor thread, read by handle(), uri(), info()

    final Object fmLock = new Object();
    final _FileMonitor fileMonitor;
    volatile boolean monitoring_volatile;
    volatile long lastRequestTime_volatile = System.currentTimeMillis();

    /**
     * Create a StaticHandler with default settings.
     * <p>
     *     See constructor
     *     {@link #StaticHandler(String, Path, ConsumerX)
     *            StaticHandler(uriPrefix, dirPrefix, confMod)} for details.
     * </p>
     */
    public StaticHandler(String uriPrefix, String dirPrefix) throws RuntimeException
    {
        this(uriPrefix, dirPrefix, conf->{} );
    }

    /**
     * Create a StaticHandler.
     * <p>
     *     <code>uriPrefix</code> must be a valid URI path, starting with "/".
     *     Examples: <code>"/", "/uri", "/foo/bar"</code>.
     * </p>
     * <p>
     *     <code>dirPrefix</code> must point to a directory.
     *     Examples: <code>"/dir", "../dir"</code>.
     * </p>
     * <p id=confMod>
     *     <code>confMod</code> is used to modify per-file config variables.
     *     For each file under the directory, a {@link StaticFileConf} is created with default values,
     *     then passed to <code>confMod</code>; <code>confMod</code> can modify any variables
     *     in the <code>StaticFileConf</code>.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     // map "/uri/xxx" to "/dir/xxx"; cache every file in memory.
     *     static final StaticHandler staticHandler
     *         = new StaticHandler("/uri", "/dir", conf-&gt;conf.cache(true) );
     * </pre>
     * <p>
     *     This constructor does blocking IO to read metadata of all files under the directory.
     *     That's not a problem if the static handler is created during application startup;
     *     but be careful if used in a non-blocking/async context.
     * </p>
     * @throws RuntimeException
     *         if something went wrong unexpectedly, for example file metadata cannot be read.
     */
    public StaticHandler(String uriPrefix, String dirPrefix, ConsumerX<StaticFileConf> confMod) throws RuntimeException
    {
        this(uriPrefix, FileSystems.getDefault().getPath(dirPrefix), confMod);
        // we only tested the default file system.
    }
    StaticHandler(String uriPrefix, Path rootDir, ConsumerX<StaticFileConf> confMod) throws RuntimeException
    {
        if(!uriPrefix.startsWith("/"))
            throw new IllegalArgumentException("uriPrefix must start with /");
        if(!uriPrefix.endsWith("/"))
            uriPrefix = uriPrefix+"/";
        UriPath uriPath = UriPath.parseStrict(uriPrefix, uriPrefix.length());
        if(uriPath==null)
            throw new IllegalArgumentException("uriPrefix is not a valid URI path: "+uriPrefix);
        this.uriPrefix = uriPath;

        // rootDir can be a symbolic path; we'll respect symbolic paths.
        rootDir = rootDir.toAbsolutePath().normalize();
        if(!Files.isDirectory(rootDir))
            throw new IllegalArgumentException("not a dir: " + rootDir.toString());
        String dirPrefix = rootDir.toString();
        String pathSep = rootDir.getFileSystem().getSeparator();
        if(!dirPrefix.endsWith(pathSep))
            dirPrefix = dirPrefix + pathSep;
        this.dirPrefix = dirPrefix;

        this.confMod = confMod;

        fileMonitor = new _FileMonitor(pathMatcher, rootDir);

        ensureMonitoring(); // throws
        // it'll scan dirs, retrieve all files, process the files, then start monitor thread.
        // if we don't call it here, the 1st request will trigger the same action.
        // we do it here for early error detection, and to have info map ready to be queried.
        // if it throws error, this constructor fails too
    }

    StaticFileConf newConf(Path file) throws Exception
    {
        // for each file, this method is invoked twice, by fileMonitor.pathMatcher, and by this.updateFile()
        // don't worry about it.

        StaticFileConf conf = new StaticFileConf(file);
        confMod.accept(conf);
        return conf;
    }

    final PathMatcher pathMatcher = path ->
    {
        // path is absolute normalized under dirPrefix
        try
        {
            return ! newConf(path).exclude;
        }
        catch (Exception e)
        {
            logger.error("%s", e);
            return false;
        }
    };

    /**
     * Try to serve the request with a file response.
     * <p>
     *     If the request is GET/HEAD, and the URI maps to a file, a 200 response is returned to serve the file.
     * </p>
     * <p>
     *     Other possible responses:
     * </p>
     * <ul>
     *     <li>400 - the request is invalid, e.g. the URI is malformed</li>
     *     <li>404 - the URI is not mapped to a file</li>
     *     <li>405 - the URI is mapped to a file, however the request method is not GET/HEAD</li>
     *     <li>500 - unexpected error, e.g. file metadata cannot be read</li>
     * </ul>
     * <p>
     *     These error responses contain very simple text messages. If necessary (mostly for 404),
     *     the caller can transform the response to a better one.
     * </p>
     * <p>
     *     This method always returns a new <code>HttpResponseImpl</code> which the caller can modify at will.
     * </p>
     */
    @Override
    public HttpResponseImpl handle(HttpRequest request)
    {
        try
        {
            return handleX(request);
        }
        catch (RuntimeException e)
        {
            return HttpResponse.internalError(e);
        }
    }
    HttpResponseImpl handleX(HttpRequest request) throws RuntimeException
    {
        lastRequestTime_volatile = System.currentTimeMillis();

        ensureMonitoring(); // throws. low cost on busy server
        // if monitoring=false, we'll first process accumulated file change events.
        // that may block; it's ok since the system isn't busy anyway (or monitor thread wouldn't have quit)
        // typically, this is a dev server. dev makes some file changes, and refresh to see the result.
        //    therefore we must pick up file changes sync-ly before serving the request.
        // on a real server, monitoring doesn't become false, file changes are processed async-ly
        //    by monitor thread; no request is blocked by sync-ly processing file changes.



        // client is supposed to have normalized the uri and removed ".." segment.
        // if uri contains ".." segment (likely malicious), we won't find the file, that's good.

        String uri = request.uri();
        int iQM = uri.indexOf('?');
        UriPath uriPath = UriPath.parseStrict(uri, iQM!=-1 ? iQM : uri.length() );
        if(uriPath==null) // this should not happen. request.uri() is supposed to be valid
            return HttpResponse.text(400, "Malformed URI");

        FileInfo info = uri2info.get(uriPath);
        if(info==null)
            return HttpResponse.text(404, "File Not Found");

        // only support method GET/HEAD. check this after file is found.
        String reqMethod = request.method();
        if( !reqMethod.equals("GET") && !reqMethod.equals("HEAD")  )
            return HttpResponse.text(405, "Method Not Allowed")
                .header(Headers.Allow, "GET, HEAD");

        return info.makeResponse(request, iQM);
    }







    // `file` is absolute normalized, under dirPrefix. maybe a dir.
    UriPath _toUriPath(Path file)
    {
        String fileStr = file.toString();
        String relative;
        if(fileStr.length()<dirPrefix.length()) // dirPrefix="/ab/", file="/ab"
            relative = "";
        else
            relative = fileStr.substring(dirPrefix.length());
        relative = relative.replace('\\', '/');  // windows

        return uriPrefix.append( relative );
    }


    /**
     * Get the URI for the file identified by <code>relativeFilePath</code>.
     * <p>
     *     <code>relativeFilePath</code> is relative to <code>dirPrefix</code>;
     *     it must not start with "/" or "\"; it must not have been encoded with URI encoding.
     * </p>
     * <p>
     *     This method has two purposes: to encode the URI, and to tag the URI.
     * </p>
     * <p>
     *     For example, if
     *     <code>uriPrefix="/uri", dirPrefix="/dir"</code>,
     *     <code>uri("x/a b.txt")</code> is mapped for file <code>"/dir/x/a b.txt"</code>,
     *     and the resulting URI is something like <code>"/uri/x/a%20b.txt?t-53486116-314fb010"</code>.
     * </p>
     * <p>
     *     <code>relativeFilePath</code> can also point to a directory that contains an
     *     {@link bayou.file.StaticFileConf#isIndexFile(boolean) index file}.
     *     For example, <code>uri("x") or uri("x/")</code> means file <code>"/dir/x/index.html"</code>,
     *     <code>uri("")</code> means file <code>"/dir/index.html"</code>.
     * </p>
     */
    // strictly, dev should use uri(...) to obtain a properly encoded/escaped uri.
    // but it's unnecessary in most use cases, where file names contain only ordinary chars.
    // so dev probably prefers to just hand-code uri instead.
    // even if a file name contains odd chars that require encoding,
    // and the naive dev isn't aware of it, hand-coded uri usually still works.
    // e.g. href="/static/a b.txt" in html is ok, browser will encode it.
    //
    // this method is fast, so user shouldn't need to cache the return value.
    //    tho they could if they want to, and they know the etag won't change.
    public String uri(String relativeFilePath) throws IllegalArgumentException
    {
        ensureMonitoring(); // throws. low cost on busy server

        if(relativeFilePath.startsWith("/") || relativeFilePath.startsWith("\\") )
            throw new IllegalArgumentException("relativeFilePath is not relative: "+relativeFilePath);
        // though we can deal with it, we forbid it because it would be very confusing for code readers
        // uri("/x/y.txt") - what does it mean?

        Path file = Paths.get(dirPrefix, relativeFilePath);
        file = file.toAbsolutePath().normalize();
        UriPath uriPath = _toUriPath(file);
        FileInfo info = uri2info.get(uriPath);
        if(info==null)
            throw new IllegalArgumentException("invalid file: "+file); // file may exist but is excluded
        return info.uriTagged;
    }

    /**
     * Return a text document listing all files and their metadata.
     * <p>
     *     This is for diagnosis only. The document can be dumped on stdout,
     *     or served through a secret URL.
     * </p>
     * <pre>
     *     if(request.uri().equals(...))
     *         return HttpResponse.text(200, staticHandler.info());
     * </pre>
     */
    public String info()
    {
        ensureMonitoring(); // throws. low cost on busy server

        return new Object()
        {
            StringBuilder sb = new StringBuilder();
            void println(){ sb.append(System.lineSeparator()); }
            void tr(String n, Object v)
            {
                for(int i=0; i<20-n.length(); i++) // right justify
                    sb.append(' ');
                sb.append(n).append(n.isEmpty()? "  ":": ").append(v);
                println();
            }

            {
                tr("class", StaticHandler.this.getClass().getName());
                tr("uriPrefix", uriPrefix.string());
                tr("dirPrefix", dirPrefix);

                TreeMap<Path,FileInfo> file2info = new TreeMap<>();
                for(FileInfo info : uri2info.values())
                    file2info.put(info.file, info);
                tr("fileCount", file2info.size());

                for(FileInfo info : file2info.values())
                    dumpFile(info);
            }
            void dumpFile(FileInfo info)
            {
                println();
                tr("uri", info.uri);
                for(UriPath alt : info.uriAlt)
                    tr("", alt.string());

                tr("file", info.file);
                tr("file size", info.fileLength);

                tr("gzip", info.doGzip);
                // if(info.gzFile!=null) tr("gz file", info.gzFile.gzFile);

                tr("cache", info.doCache);

                tr("ETag", info.etag);  // can be null
                if(info.etag!=null)
                    tr("tagged uri", info.uriTagged);

                tr("Content-Type", info.contentType);

                tr("Last-Modified", _HttpDate.toHttpDate(info.lastModified));

                if(info.expiresAbsolute!=null)
                    tr("Expires", _HttpDate.toHttpDate(info.expiresAbsolute));
                else if(info.expiresRelative!=null)
                    tr("Expires", "" + (info.expiresRelative.getSeconds()) + " seconds (relative)");
                else
                    tr("Expires", "none");

                if(!info.otherHeaders.isEmpty())
                    tr("Other headers", info.otherHeaders.size());
                for(Map.Entry<String,String> hv : info.otherHeaders.entrySet())
                    tr("", hv.getKey()+": "+hv.getValue());
            }
        }.sb.toString();
    }

    // encode etag as a query name in "x-www-form-urlencoded" format. minimum encoding.
    // if a char in etag is 0x80-FF, encode it as a single %HH. such bytes are unlikely
    // in usual etag generations; they may be UTF-8 bytes, instead of Latin-1 chars.
    static String etagToUri(String uri, String etag)
    {
        StringBuilder sb = new StringBuilder(uri.length()+1+etag.length()); // usually, etag requires no encoding
        sb.append(uri).append('?');
        for(int i=0; i<etag.length(); i++)
        {
            char ch = etag.charAt(i); // it is a valid etag char, ch<=0xFF
            if(_CharDef.check((int) ch, _CharDef.Html.safeQueryChars))
                sb.append(ch);
            else if(ch==' ') // actually never true; SP is not a valid etag char.
                sb.append('+');
            else
            {
                sb.append('%');
                sb.append(_HexUtil.int2hex[ch >> 4]);
                sb.append(_HexUtil.int2hex[ch & 0x0f]);
            }
        }
        return sb.toString();
    }
    // we require that the first name in the query matches etag.  /path?etag[...]
    // the name could have been encoded differently than ours. many libs with diff ways of encoding query.
    static boolean tagMatch(String uri, int start, String etag)
    {
        int end1 = uri.indexOf('=', start);
        if (end1==-1) end1=uri.length();
        int end2 = uri.indexOf('&', start);
        if (end2==-1) end2=uri.length();
        int end = Math.min(end1, end2);

        int t=0;
        for(int i=start; i<end; i++)
        {
            char c = uri.charAt(i);
            if(c=='%')
            {
                int hh = _HexUtil.hh2int(uri, end, i);
                if(hh==-1) return false;
                i+=2;
                c=(char)hh;
            }
            else if(c=='+')
                c=' '; // our etag does not contain SP
            // else, char as is

            if(t>=etag.length())
                return false;
            if(etag.charAt(t++)!=c)
                return false;
        }
        return t==etag.length();
    }


    static class FileInfo // must not change after creation
    {
        String uri;

        static final UriPath[] uriAltNone = new UriPath[0];
        UriPath[] uriAlt = uriAltNone;

        Path file;
        long fileLength;

        Instant lastModified;

        ContentType contentType;

        boolean doGzip;

        boolean doCache;

        String etag; // can be null
        String etagGzip;

        String uriTagged;  // uri?etag

        Instant expiresAbsolute;
        Duration expiresRelative;
        Instant expires()
        {
            if(expiresAbsolute!=null)  // uncommon
                return expiresAbsolute;
            if(expiresRelative!=null) // more common
                try
                {
                    return Instant.now().plus(expiresRelative);
                }
                catch (Exception e) // overflow
                {
                    return expiresRelative.isNegative()? Instant.MIN : Instant.MAX;
                }
            return null;
        }

        HeaderMap otherHeaders;

        FileByteSource.ChannelProvider originFileCP;

        ByteSourceCache bodyCache;
        // if doGzip && doCache, the bodyCache contains gzip-ed data. plain file data not cached in memory.

        GzFile gzFile; // if doGzip && !doCache

        HttpResponseImpl makeResponse(HttpRequest request, int iQM)
        {
            final Instant expiresResp;
            if(iQM==-1 || etag==null)
            {
                expiresResp = expires();
            }
            else if(tagMatch(request.uri(), iQM + 1, etag))
            {
                // never expires. ignore expiresAbsolute/Relative
                expiresResp = Instant.now().plusSeconds(365*24*3600);
                // one year is max value allowed by spec
            }
            else // tag not match
            {
                // this can happen if the file has changed since the tagged uri was created
                // or the tagged uri was created by another server instance with inconsistent file dates
                expiresResp = Instant.now();
                // to be safe, expire this response immediately.

                // note: regardless of req tag, we always serve the current representation,
                // we are not obliged to serve the (past) representation of matching etag.
            }


            final boolean clientAcceptsGzip = request.acceptsGzip();
            final boolean gzipResp = doGzip && clientAcceptsGzip;
            final ByteSource src;  // if null, call bodyCache.newView() later. we don't want to call newView() yet.
            final Long bodyLength;
            if(doCache)
            {
                if(doGzip && !clientAcceptsGzip)
                {
                    // we need to return plain file data. but the cached data is gzip-ed. so we'll read data from disk.
                    // that's ok. we expect that most clients accept gzip, no need to cater to those few who don't.
                    // if however this case does occur frequently, it won't be very slow either due to OS cache.
                    // note: apache benchmark tool 'ab' does not accept gzip.
                    src = new FileByteSource(originFileCP);
                    bodyLength = fileLength;
                    // another solution is to un-gzip the cached data on the fly. that costs cpu and memory.
                    // if this case does occur frequently, the solution is unlikely faster than reading the disk.
                    // if this case is rare, we don't need to optimize for an one-off case
                }
                else  // doGzip && clientAcceptGzip or !doGzip.
                {
                    src = null; // to use bodyCache.newView()
                    bodyLength = bodyCache.getTotalBytes(); // could be null (if gzip and copying not done)
                }
            }
            else
            {
                if(gzipResp)
                {
                    src = gzFile.getSource();
                    bodyLength = gzFile.getLength(); // could be null (if gz file not created)
                    // if the disk-cached gz file is deleted, we can't recover automatically.
                    // user will have to touch the original file, or restart server.
                }
                else
                {
                    src = new FileByteSource(originFileCP);
                    bodyLength = fileLength;
                }
            }

            HttpEntity entity = new HttpEntity()
            {
                @Override public ContentType contentType()
                {
                    return contentType;
                }
                @Override public String contentEncoding()
                {
                    return gzipResp? "gzip" : null;
                }
                @Override public ByteSource body()
                {
                    return src!=null? src : bodyCache.newView();
                }
                @Override public Long contentLength()
                {
                    return bodyLength;
                }
                @Override public String etag()
                {
                    return gzipResp ? etagGzip : etag;
                }
                @Override public Instant lastModified()
                {
                    return lastModified;
                }
                @Override public Instant expires()
                {
                    return expiresResp;
                }
            };

            // headers/cookies can be populated
            HttpResponseImpl resp = new HttpResponseImpl(HttpStatus.c200_OK, entity);
            resp.headers().putAll(otherHeaders);
            return resp;
        }
    }

    // to be hot-reload friendly, the monitor thread quits if the handler has been idle for 5 sec.

    // has the effect of bringing file info up to date.
    void ensureMonitoring() throws RuntimeException
    {
        if(monitoring_volatile)  // it'll stay true on a busy system.
            return;

        synchronized (fmLock)
        {
            if(!monitoring_volatile)
            {
                // called by constructor: 1st time, scan dir, get all files. can be slow.
                // called by handle(), uri(), info()
                //     handler was idle, monitor thread had quit.
                //     now we need the latest file info. during the gap, there could have been
                //     some accumulated events (e.g. dev made file changes during the gap).
                //     process these events sync-ly so that they are reflected in the response.
                //     when this is happening, other requests are blocked too till events are processed.
                List<Set<Path>> changes;
                try
                {
                    changes = fileMonitor.pollFileChanges();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                processChanges(changes);

                monitoring_volatile =true;

                startMonitoring();
            }
        }
    }
    void startMonitoring()
    {
        _Exec.executeB("file handler monitoring thread", () -> {
            try
            {
                doMonitoring();
            }
            finally
            {
                // monitor quits, due to interruption, error, or idle
                monitoring_volatile = false;
            }
            // those 2 lines are not atomic.
            // a new request could come in between the gap and see monitoring=true.
            // no big deal. next request will see monitoring=false.
        });
    }
    void doMonitoring()
    {
        while(true)
        {
            List<Set<Path>> changes;
            try
            {
                changes = fileMonitor.pollFileChanges(1000);
                // wake up every second even if there's no change, to check idle-ness
            }
            catch (InterruptedException e)
            {
                return;
            }
            catch(IOException e)
            {
                logger.error("File Monitor error: %s", e);
                return;
            }

            processChanges(changes);  // usually no change

            if(System.currentTimeMillis()- lastRequestTime_volatile > 5000) // no request in 5 sec
                return;  // handler is idle, quit. not likely on a busy server.
        }
    }

    private void processChanges(List<Set<Path>> changes)
    {
        // all files are absolute normalized under dirPrefix, since they are from fileMonitor

        for(Path fileCreated : changes.get(0))
            updateFile(fileCreated);

        for(Path fileUpdated : changes.get(1))
            updateFile(fileUpdated);

        for(Path fileDeleted : changes.get(2))
            deleteFile(fileDeleted);
    }

    void deleteFile(Path file)
    {
        UriPath uriPath = _toUriPath(file);
        removeInfo(uriPath);
        // it's possible that the map did not has it, because the last updateFile(file) failed.
    }
    void removeInfo(UriPath uriPath)
    {
        FileInfo info = uri2info.remove(uriPath);
        if(info!=null)
            for(UriPath alt : info.uriAlt)
                uri2info.remove(alt);
    }

    void updateFile(Path file) // file is created or updated
    {
        UriPath uriPath = _toUriPath(file);
        uriPath = uriPath.pack(); // it'll be stored in map

        removeInfo(uriPath);
        // remove old info ASAP after the file is modified.
        // this leaves a short gap when the file cannot be served.
        // it would be far worse if we serve the new file content with old info.
        // that can still happen regardless. we can't make this whole thing ACID.

        FileInfo info;
        try
        {
            StaticFileConf conf = newConf(file); // throws.
            // must be a new conf for an updated file.

            if(conf.exclude)
                return;
            // it's possible that a file was included, but becomes excluded after it's modified

            info = createInfo(conf, uriPath);
        }
        catch (Exception e)
        {
            logger.error("Error processing file %s: %s", file, e);
            return;
        }

        uri2info.put(uriPath, info);
        for(UriPath alt : info.uriAlt)
            uri2info.put(alt, info);
        // it's possible that info.isIndexFile changes after file is modified.
    }

    FileInfo createInfo(StaticFileConf conf, UriPath uriPath) throws Exception
    {
        if(false) // disabled, very slow on Windows if there are lots of files. not important. confMod can check it.
        {
            if(!Files.isReadable(conf.filePath))
                throw new AccessDeniedException(conf.filePath.toString(), null, "file is not readable");
            // exclude it, but not silently. app should have explicitly excluded it.
        }

        FileInfo info = new FileInfo();

        info.file = conf.filePath;
        info.fileLength = conf.fileSize;
        info.lastModified = conf.fileLastModified;
        info.contentType = conf.contentType;
        info.doGzip = conf.gzip;
        info.doCache = conf.cache;
        info.expiresAbsolute = conf.expiresAbsolute;
        info.expiresRelative = conf.expiresRelative;

        info.etag = conf.etag;
        if(info.etag!=null)
            info.etagGzip = info.etag+".gzip";

        info.uri = uriPath.string();

        // uriTagged
        if(info.etag==null)
            info.uriTagged = info.uri;
        else
            info.uriTagged = etagToUri(info.uri, info.etag);

        if(conf.isIndexFile) // add alternative URIs
        {
            // dir1: remove the file name from uri, leave only the dir.
            // "/abc/index.html" -> "/abc/", "/index.html" -> "/"
            byte[] uriBytes = uriPath.bytes;
            int S=uriPath.len;
            while(uriBytes[--S]!='/')continue; // find the last slash. (we know it exists and is not "%2F")
            UriPath dir1 = new UriPath(uriBytes, S+1);
            // do not pack(). share the byte[] with uriPath (which is packed)

            // dir2: remove the trailing slash from dir1, but only if it's preceded by a non "/" char.
            // examples of no removing: "/index.html", "//index.html", "/abc//index.html"
            if(S==0) // dir1=="/"
                info.uriAlt = new UriPath[]{ dir1 };
            else if(uriBytes[S-1]=='/')  // dir1 ends with "//"  (or "%2F/" - jeez. whatever. don't care.)
                info.uriAlt = new UriPath[]{ dir1 };
            else
                info.uriAlt = new UriPath[]{ dir1, new UriPath(uriBytes, S) };
        }

        info.otherHeaders = new HeaderMap();
        for(Map.Entry<String,String> entry : conf.headers.entrySet())
        {
            String name = entry.getKey();
            String value = entry.getValue();
            _HttpUtil.checkHeader(name, value);  // we don't trust them
            info.otherHeaders.put(name, value);
        }
        // why copy headers, instead of simply do `info.otherHeaders=conf.headers` ?
        // if confMod called conf.headers.freeze(), we are screwed, coz we may need to add more headers below.

        if(info.doGzip)
        {
            // add Vary: Accept-Encoding
            // the Vary header is added even if the response is not gzip-ed(because client doesn't accept gzip)
            //   Accept-Encoding is used for negotiation of representation regardless.
            _HttpUtil.addVaryHeader(info.otherHeaders, Accept_Encoding);
        }

        info.originFileCP =  FileByteSource.ChannelProvider.pooled(conf.filePath) ;

        if(info.doCache)
        {
            if(info.doGzip) // cache gzip-ed data in memory; plain file data not cached
                info.bodyCache = new ByteSourceCache( gzSrc(info.originFileCP), null );
            else // cache plain file data
                info.bodyCache = new ByteSourceCache(new FileByteSource(info.originFileCP), info.fileLength);
            // all involved sources are lazy, no real resource is consumed,
            // until bodyCache.newView() is invoked for the 1st time.
        }
        else
        {
            if(info.doGzip) // cache gzip-ed data on disk
                info.gzFile = new GzFile(info);  // lazy
        }


        return info;
    }

    static class GzFile
    {
        final Object lock(){ return this; }

        final Path originFile;
        final Path gzFile;

        final FileByteSource.ChannelProvider originFileCP;
        final FileByteSource.ChannelProvider gzFileCP;


        enum State{ notCreated, creating, created, error }
        volatile State state_volatile;
        volatile Long gzLength_volatile;

        GzFile(FileInfo info) throws Exception
        {
            this.originFile = info.file;
            this.originFileCP = info.originFileCP;

            this.gzFile = getGzPath(originFile, info);

            this.gzFileCP  = FileByteSource.ChannelProvider.pooled(this.gzFile);

            if(Files.exists(gzFile))  // created by a prev vm
            {
                gzLength_volatile = Files.size(gzFile); // throws
                state_volatile = State.created;
            }
            else
            {
                gzLength_volatile = null;
                state_volatile = State.notCreated;
            }
        }

        ByteSource getSource()
        {
            if(state_volatile ==State.created)
                return new FileByteSource(gzFileCP);

            synchronized (lock())
            {
                if(state_volatile ==State.notCreated) // then let me create it
                {
                    state_volatile = State.creating;
                    asyncCreate();
                }
            }

            // on-the-fly gzip.
            return gzSrc(originFileCP);
        }
        Long getLength()
        {
            return gzLength_volatile;
        }


        static Path getGzPath(Path originFile, FileInfo info) throws Exception
        {
            // originFile is absolute normalized

            String dir = originFile.getParent().toString();
            Path root = originFile.getRoot(); // e.g. "/", "C:\", "\"
            if(root!=null) // don't want to deal with root.
                dir = dir.substring(root.toString().length());   //  "/x/y" => "x/y"
            // we may confuse C:\ and D:\, end up with same gz dir, but that's ok

            // gz path is versioned by file version; currently we use timestamp as the version.
            // probably better to use etag instead, but then we need to worry about special chars.
            // timestamp, in ns, is probably good enough for now.
            String fileName = originFile.getFileName().toString();
            String ver = _HttpUtil.defaultEtag(info.lastModified, "gzip"); // t-####-####.gzip
            String gzFileName = fileName + "." + ver;

            return Paths.get("/tmp/bayou/file_handler_gz_cache", dir, gzFileName);  // absolute, normalized
            // "/tmp" is ok. machine is not restarted often. even if restarted, recreate gz file isn't too slow.
            // if user complains, we may need to provide customization of gz dir, or even customize gz path per file.
        }

        void asyncCreate()
        {
            _Exec.executeB(() -> {
                Long len = null;
                try
                {
                    createGzFile(); // throws
                    // now gzFile exists
                    len = Files.size(gzFile); // throws
                }
                catch (Exception e)
                {
                    logger.error("Error creating gz file: %s", e);
                }

                // no sync - vars are volatile, and no one else is writing to them.
                if (len != null) // all ok
                {
                    gzLength_volatile = len;
                    state_volatile = State.created;
                }
                else
                {
                    state_volatile = State.error;
                    // error is sticky. won't try again to create the gz file. on-the-fly gzip if error.
                }
            });
        }
        void createGzFile() throws Exception  // ok if the file is created by another VM
        {
            if(Files.exists(gzFile)) // we checked it in constructor, which can be a while ago.
                return;

            Path dir = gzFile.getParent();
            Files.createDirectories(dir);
            Path tmpFile = Files.createTempFile(dir, gzFile.getFileName().toString()+".", ".tmp");

            try
            {
                // write gz content to tmpFile, then move tmpFile to gzFile
                createGzFile2(tmpFile); // throws
            }
            catch(Exception e)
            {
                deleteSilent(tmpFile);
                throw e;
            }
        }

        void createGzFile2(Path tmpFile) throws Exception
        {
            try (InputStream is = new ByteSource2InputStream(gzSrc(originFileCP), Duration.ofSeconds(60)))
            {
                // System.out.println("write to "+tmpFile.toString());
                Files.copy(is, tmpFile, StandardCopyOption.REPLACE_EXISTING); // tmpFile is existing.
            }
            // auto close is

            try
            {
                Files.move(tmpFile, gzFile, StandardCopyOption.ATOMIC_MOVE);
                // System.out.println("moved to "+gzFile.toString());
            }
            catch (FileAlreadyExistsException e)
            {
                // another vm wins. not rare with multiple server instances.
                deleteSilent(tmpFile);
                // as if move succeeded
            }
            // other exceptions
        }

        void deleteSilent(Path file)
        {
            try
            {
                Files.delete(file);
            }
            catch (Exception e) // not critical
            {
                logger.error("%s", e);
            }
        }

    }

    // it's important that gz is consistent: same origin data yields same gz data.
    // same compression level must be used.
    // since we cache gz data (in mem or on disk), 9 is used for max compression.
    // it's slower, but on-the-fly gz is not common here.
    static GzipByteSource gzSrc(FileByteSource.ChannelProvider originFileCP)
    {
        return new GzipByteSource( new FileByteSource(originFileCP), 9 );
    }

}
