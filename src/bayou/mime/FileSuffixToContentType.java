package bayou.mime;

import _bayou._tmp._Util;
import bayou.file.StaticHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Maps file suffix to ContentType.
 * <p>
 *     A <code>FileSuffixToContentType</code> instance
 *     is a mapping of <code>{fileSuffix&rarr;contentType}</code>.
 *     <code>fileSuffix</code> can be any string, usually starting with a separator char.
 *     New mappings can be added with method {@link #map(CharSequence, ContentType) map(fileSuffix, contentType)}.
 * </p>
 * <p>
 *     Given a filePath, we can {@link #find(CharSequence) find} the longest fileSuffix that matches filePath,
 *     and return the corresponding ContentType. For example:
 * </p>
 * <pre>
 *     mappings
 *                   .txt &rarr; text/plain;charset=UTF-8
 *                -en.txt &rarr; text/plain;charset=US-ASCII
 *
 *     file "abc.txt" matches the 1st; file "xyz-en.txt" matches the 2nd.
 * </pre>
 * <p>
 *     Matching is case sensitive; "\" matches to "/".
 * </p>
 * <p>
 *     You can map an empty fileSuffix to a ContentType
 *     as the fall-back mapping.
 * </p>
 * <p>
 *     The mapping may be initially populated using the Apache
 *     <a href="http://svn.apache.org/viewvc/httpd/httpd/trunk/docs/conf/mime.types?view=log">mime.types</a>
 *     file; revision
 *     <a href="http://svn.apache.org/viewvc/httpd/httpd/trunk/docs/conf/mime.types?revision=1506674&view=markup">1506674</a>
 *     is used in this implementation.
 *     We add "charset=UTF-8" to all "text/xxx" content types in mime.types.
 * </p>
 * <p>
 *     There is a {@link #getGlobalInstance() global instance}
 *     that is used by {@link StaticHandler} etc. App may add mappings to the global instance.
 * </p>
 * <p>
 *     This class is thread-safe.
 * </p>
 */
public class FileSuffixToContentType
{
    static final TrieNode initTrie = buildInitTrie();  // // initial apache mappings

    static final FileSuffixToContentType instance = new FileSuffixToContentType(true);

    /**
     * The global instance.
     * <p>
     *     The global instance is initially populated with Apache mime.types.
     *     App may add mappings to the global instance.
     * </p>
     */
    public static FileSuffixToContentType getGlobalInstance(){ return instance; }


    final Object writeLock = new Object();
    volatile TrieNode root_volatile;  // copy on write


    /**
     * Create a new instance.
     * <p>
     *     If <code>apache==true</code>, the instance is populated with Apache mime.types.
     * </p>
     */
    public FileSuffixToContentType(boolean apache)
    {
        if(apache)
            root_volatile = initTrie;
        else
            root_volatile = new TrieNode(); // empty
    }

    /**
     * Find the ContentType of filePath.
     * <p>
     *     Find the longest fileSuffix that matches filePath,
     *     and return the corresponding ContentType;
     *     return null if no matching is found.
     * </p>
     */
    public ContentType find(CharSequence filePath)
    {
        TrieNode root = root_volatile;

        return root.lookup(filePath);
    }

    /**
     * Add a mapping <code>fileSuffix&rarr;contentType</code>.
     * <p>
     *     All "\" chars in fileSuffix will be replaced with "/".
     * </p>
     * @return previous ContentType that was mapped to <code>fileSuffix</code>
     */
    public ContentType map(CharSequence fileSuffix, ContentType contentType)
    {
        _Util.require(fileSuffix!=null, "fileSuffix!=null");
        _Util.require(contentType!=null, "contentType!=null");

        ContentType oldValue;
        synchronized (writeLock)
        {
            TrieNode klone = root_volatile.klone();

            oldValue = klone.bind(fileSuffix, fileSuffix.length(), contentType);

            root_volatile = klone;
        }
        return oldValue;
    }

    /**
     * Return the mapping <code>{fileSuffix&rarr;contentType</code>} as a Map.
     * <p>
     *     The returned Map is read-only; it's mainly for diagnosis purpose.
     * </p>
     */
    public Map<String,ContentType> asMap()
    {
        TreeMap<String,ContentType> map = new TreeMap<>();
        root_volatile.dumpMap(map, "");
        return Collections.unmodifiableMap(map);
    }

    static class TrieNode
    {
        ContentType ct;
        HashMap<Character,TrieNode> children;

        ContentType lookup(CharSequence path)
        {
            TrieNode node = this;
            int L=path.length();
            ContentType result = null;
            while(true)
            {
                if(node.ct!=null)
                    result = node.ct;

                if(L==0 || node.children==null)
                    break;

                char c = path.charAt(L-1);
                if(c=='\\') c='/';
                Character ch = Character.valueOf(c);
                node = node.children.get(ch);
                if(node==null)
                    break;
                L--;
            }
            return result;
        }

        ContentType bind(CharSequence path, int L, ContentType ctNew)
        {
            if(L==0)
            {
                ContentType ctOld = ct;
                ct = ctNew;
                return ctOld;
            }

            char c = path.charAt(L-1);
            if(c=='\\') c='/';
            Character ch = Character.valueOf(c);
            if(children==null)
                children = new HashMap<>(4, 0.5f);
            TrieNode child = children.get(ch);
            if(child==null)
                children.put(ch, child=new TrieNode());
            return child.bind(path, L - 1, ctNew);
        }

        TrieNode klone()
        {
            TrieNode that = new TrieNode();
            that.ct = this.ct;
            if(this.children!=null)
                that.children = klone(this.children);
            return that;
        }

        HashMap<Character,TrieNode> klone(HashMap<Character,TrieNode> children)
        {
            @SuppressWarnings("unchecked")
            HashMap<Character,TrieNode> klone = (HashMap<Character,TrieNode>)children.clone();
            // call clone() to keep same capacity. but it's shallow clone
            for(Map.Entry<Character,TrieNode> entry : klone.entrySet())
                entry.setValue( entry.getValue().klone() );
            return klone;
        }

        void dumpMap(TreeMap<String,ContentType> map, String path)
        {
            if(ct!=null)
                map.put(path, ct);
            if(children!=null)
                for(Map.Entry<Character,TrieNode> entry : children.entrySet())
                    entry.getValue().dumpMap(map, entry.getKey()+path);
        }

        void dumpTree(Consumer<CharSequence> out, int indent) throws IOException
        {
            if(ct!=null)
                println(out, indent, ct);
            if(children!=null)
                for(Map.Entry<Character,TrieNode> entry : children.entrySet())
                {
                    println(out, indent, entry.getKey());
                    entry.getValue().dumpTree(out, indent + 1);
                }
        }
        static void println(Consumer<CharSequence> out, int indent, Object obj) throws IOException
        {
            for(int i=0; i<indent; i++)
                out.accept("    ");
            out.accept(String.valueOf(obj));
            out.accept(System.lineSeparator());
        }
    }


    static TrieNode buildInitTrie()
    {
        try
        {
            return buildInitTrieE();
        }
        catch (Exception e) // unlikely
        {
            throw new RuntimeException(e);
        }
    }
    static TrieNode buildInitTrieE() throws Exception
    {
        TrieNode root = new TrieNode();

        try( InputStream r1 = FileSuffixToContentType.class.getResourceAsStream("/_bayou/__resource/mime.types.txt") ;
             Reader r2 = new InputStreamReader( r1, StandardCharsets.UTF_8);
             BufferedReader r3 = new BufferedReader(r2) )
        {
            r3.lines().forEach(line->
            {
                line = line.trim();
                if(line.startsWith("#"))
                    return;

                // example line
                // text/plain        txt text conf def list log in

                String[] words = line.split("\\s+");

                String[] types = words[0].split("\\/");
                String type=types[0], subtype=types[1];
                ContentType ct = type.equals("text") ? new ContentType(type,subtype,"charset","UTF-8") :
                    new ContentType(type,subtype);

                for(int i=1; i<words.length; i++)
                {
                    String ext = words[i];
                    String suffix = "."+ext;
                    root.bind(suffix, suffix.length(), ct);
                    // there are a few duplicates: one ext is mapped to multiple types. we'll just take the last one.
                }
            });
        }
        return root;
    }
}
