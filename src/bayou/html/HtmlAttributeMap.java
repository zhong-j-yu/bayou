package bayou.html;

import java.util.*;

class HtmlAttributeMap extends AbstractMap<String, CharSequence>
{
    final HtmlElement element;
    final EntrySet entrySet;

    HtmlAttributeMap(HtmlElement element)
    {
        this.element = element;
        this.entrySet = new EntrySet(element);
    }

    // AbstractMap does get() by linear search thru entrySet, which is generally not good.
    // but it works for us, since we store entries as an array (reasoning that there aren't many entries)

    @Override
    public CharSequence put(String key, CharSequence value)
    {
        HtmlElement.validateAttributeName(key);

        // tho element.setAttribute() supports null value, we cannot allow it for Map.put().
        // user should use remove() instead.
        if(value==null)
            throw new NullPointerException("attribute value cannot be null");

        return element.setAttribute(key, value);
    }

    @Override
    public Set<Entry<String, CharSequence>> entrySet()
    {
        return entrySet;
    }

    static class EntrySet extends AbstractSet<Entry<String, CharSequence>>
    {
        final HtmlElement element;

        EntrySet(HtmlElement element)
        {
            this.element = element;
        }

        @Override
        public Iterator<Entry<String, CharSequence>> iterator()
        {
            return new EntryIterator(element);
        }

        @Override
        public int size()
        {
            return element.getAttributeCount();
        }
    }

    static class EntryIterator implements Iterator<Entry<String, CharSequence>>
    {
        final HtmlElement element;
        int x;
        AttrEntry current;

        EntryIterator(HtmlElement element)
        {
            this.element = element;
        }

        @Override
        public boolean hasNext()
        {
            return x < element.attributesSize;
        }

        @Override
        public Entry<String, CharSequence> next()
        {
            if(!hasNext())
                throw new NoSuchElementException();

            return current = new AttrEntry(element, x++);
        }

        @Override
        public void remove()
        {
            if(current==null)
                throw new IllegalStateException();

            current.remove();
            current = null;
            x--; // adjust pointer
        }
    }

    static class AttrEntry extends SimpleEntry<String, CharSequence>
    {
        final HtmlElement element;
        int index; // -1 if removed

        AttrEntry(HtmlElement element, int index)
        {
            super( (String)element.attributes[2*index], element.attributes[2*index+1] );

            this.element = element;
            this.index = index;
        }

        void remove()
        {
            validateIndex();
            element._removeAttribute(index);
            index = -1;
        }

        // the table cell pointed to by the index may no longer represent this entry
        void validateIndex() throws ConcurrentModificationException
        {
            if(index>=element.attributesSize) // likely some attrs were removed by someone else.
                throw new ConcurrentModificationException();

            Object prevKey = this.getKey(); // as seen in the constructor
            Object currKey = element.attributes[2* index];
            if(prevKey!=currKey) // identity comparison!
                throw new ConcurrentModificationException();
            // even if prevKey==currKey, table might have been modified.
            // we cannot detect that case, but it's not harmful.
            // we could also do prevKey.equals(currKey) to be more lenient, since it's not harmful either.
        }

        // getKey()/getValue() still works after remove(). but setValue() does not.

        @Override
        public CharSequence setValue(CharSequence value)
        {
            if(value==null)
                throw new NullPointerException("attribute value cannot be null");

            if(index==-1)
                throw new IllegalStateException("this entry has been removed from the map");
            // setValue() after remove() is very likely a programming error.

            validateIndex();

            CharSequence old = super.setValue(value); // local copy
            element.attributes[2* index +1] = value;  // write through
            return old;
        }
    }


}
