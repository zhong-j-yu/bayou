package bayou.mime;

import _bayou._tmp._KnownHeaders;
import _bayou._tmp._StrCi;

import java.util.*;

/**
 * A Map implementation for MIME headers, with case-insensitive keys.
 * <p>
 *     A key-value pair in this Map corresponds to a name-value pair of a MIME header.
 *     Because MIME header names are case-insensitive, this Map treats keys as case-insensitive too.
 *     Note that this violates the general contracts of Map.
 * </p>
 * <p>
 *     The implementation of this class is optimized for well-known headers (see {@link Headers}).
 * </p>
 */
public class HeaderMap implements Map<String,String>
{
    // header name/value usually cannot contain arbitrary chars.
    // we don't do sanity check for name/value during put();
    // names/values need to be checked either
    //    earlier, e.g. request/response.header(n,v),
    //    or later, e.g. when generating response

    /**
     * Create a HeaderMap instance.
     */
    public HeaderMap()
    {

    }

    /**
     * Freeze this map so that it cannot be modified anymore.
     * <p>
     *     After this method is called, any mutating method like put(key,value)
     *     will throw UnsupportedOperationException.
     * </p>
     */
    public void freeze()
    {
        readOnly = true;
    }
    // it's kind of odd to have a freeze() method on a Map. we want a cheaper way to make it unmodifiable.
    // this is used by HttpRequestImpl to make sure app won't tamper with it
    boolean readOnly;
    void notReadOnly()
    {
        if(readOnly)
            throw new UnsupportedOperationException("this map is read only");
        // after freeze(), the producer probably wants to pass this map as read-only to the consumer,
        // so UnsupportedOperationException makes more sense than IllegalStateException
    }

    @Override public int size() { return k2v.size(); }
    @Override public boolean isEmpty() { return k2v.isEmpty(); }
    @Override public boolean containsValue(Object value) { return k2v.containsValue(value); }

    @Override public void clear()
    {
        notReadOnly();
        k2v.clear();
        // note: niceForms is not cleared
    }
    @Override public Set<String> keySet()
    {
        return readOnly? Collections.unmodifiableSet(k2v.keySet()) : k2v.keySet();
    }
    @Override public Collection<String> values()
    {
        return readOnly? Collections.unmodifiableCollection(k2v.values()) : k2v.values();
    }
    @Override public Set<Entry<String, String>> entrySet()
    {
        return readOnly? Collections.unmodifiableSet(k2v.entrySet()) : k2v.entrySet();
    }

    @Override public void putAll(Map<? extends String, ? extends String> m)
    {
        notReadOnly();
        for (Map.Entry<? extends String, ? extends String> entry : m.entrySet())
            put(entry.getKey(), entry.getValue());
    }

    @Override public String toString(){ return k2v.toString(); }

    // hashCode() and equals() are required by Map interface. not implemented here. no big deal.

    // no clone(). use the copy constructor

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    // a header name can be in different forms of varying cases, but there is one nice form.
    // for a well-known header name, the nice form is how it's appeared in the spec. e.g. "Host"
    // otherwise, the nice form is how we see the name first time in put()

    // internal map, key to value. key is in the nice form.
    LinkedHashMap<String,String> k2v = new LinkedHashMap<>(32, 0.5f);  // keep insertion order. typically <16 entries.

    HashMap<_StrCi, String> niceForms;  // only for none-well-known headers
    String niceForm(String key)  // may return null.
    {
        assert key!=null;

        String nice = _KnownHeaders.lookup(key); // usually succeeds; and usually nice==key
        if(nice==null) // uncommon
            if(niceForms!=null)
                nice = niceForms.get(new _StrCi(key));

        return nice;
    }

    @Override
    public String get(Object key)
    {
        // try our luck directly at k2v.
        // usually, key is already in the nice form. tho case insensitive, people tend to use consistent form.
        // and usually if user calls get(k), k2v contains the k. so we bet k2v.get() likely to succeed.
        String value = k2v.get(key);
        if(value!=null)
            return value;

        if(!(key instanceof String)) // true if key==null
            return null;

        String key2 = niceForm((String) key); // likely, key is known header in nice form, key2==key
        if(key2==null)  // unknown, and never seen by us
            return null;
        if(key2.equals(key))
            return null;
        return k2v.get(key2);
    }

    @Override
    public boolean containsKey(Object key)
    {
        return get(key)!=null;  // note: values cannot be null. see put()
    }

    @Override
    public String remove(Object key)  // tho we may remove a key, the `niceForms` map is not touched.
    {
        notReadOnly();

        // very similar to get(), try directly first
        String value = k2v.remove(key);
        if(value!=null)
            return value;

        if(!(key instanceof String)) // true if key==null
            return null;

        String key2 = niceForm((String) key); // likely, key is known header in nice form, key2==key
        if(key2==null)  // unknown, and never seen by us
            return null;
        if(key2.equals(key))
            return null;
        return k2v.remove(key2);
    }


    @Override
    public String put(String key, String value) throws IllegalArgumentException
    {
        notReadOnly();

        if(key==null)
            throw new IllegalArgumentException("header name cannot be null");
        if(key.isEmpty())
            throw new IllegalArgumentException("header name cannot be empty");
        if(value==null)
            throw new IllegalArgumentException("header value cannot be null");

        // no further sanity checking here.

        String keyNice = _KnownHeaders.lookup(key); // usually succeeds; and usually nice==key
        if(keyNice==null) // not a well known header name. rare.
        {
            // look up its nice form if it was seen before
            _StrCi ciKey = new _StrCi(key);
            if(niceForms==null)
            {
                niceForms = new HashMap<>();
                keyNice = null;
            }
            else
                keyNice = niceForms.get(ciKey);  // could return null

            if(keyNice==null) // never seen before; install it as the nice form
            {
                keyNice=key;
                niceForms.put(ciKey, keyNice);
            }
        }

        return k2v.put(keyNice, value);
    }

}
