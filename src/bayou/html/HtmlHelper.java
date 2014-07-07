package bayou.html;

import _bayou._tmp._CharSubSeq;

import java.util.function.Consumer;

class HtmlHelper
{
    // customization of indentation.
    // for now, do not publicize this feature. indentation overhead is small.

    // default is TAB, which is fine - most modern editors will show 4 spaces for 1 TAB.
    static final String INDENT = System.getProperty(HtmlPiece.class.getName()+".INDENT", "\t");
    // if INDENT="", we still print new lines

    static final boolean disabled = INDENT.equalsIgnoreCase("disabled");

    static String x_indent(int level)
    {
        if(disabled)
            return "";
        String s = "\n";
        for(int i=0; i<level; i++)
            s+= INDENT;
        return s;
    }

    static final int cacheMax = 32;
    static String[] indentCache = new String[cacheMax];
    static
    {
        for(int i=0;i< cacheMax; i++)
            indentCache[i]= x_indent(i);
    }

    public static String indent(int level)
    {
        if(level<0)
            return "";
        else if(level<cacheMax)
            return indentCache[level];
        else
            return x_indent(level);
    }




    // if an arg is not CharSequence, will use String.valueOf(arg).
    public static Object[] toCharSeq(Object[] args)
    {
        // we do in-place replacement. assume we own the array
        for(int i=0; i<args.length; i++)
        {
            Object arg = args[i];
            args[i] = (arg instanceof CharSequence)? (CharSequence)arg : String.valueOf(arg);
        }
        return args;
    }





    static void renderEscaped(String[] escMap, CharSequence csq, Consumer<CharSequence> out)
    {
        int L = csq.length();
        int s=0;
        for(int i=0; i<L; i++)
        {
            char c = csq.charAt(i);
            String esc = c<escMap.length ? escMap[c] : null;
            if(esc!=null)
            {
                if(i>s)
                    out.accept(_CharSubSeq.of(csq, s, i));
                out.accept(esc);
                s=i+1;
            }
        }
        if(L>s)
        {
            if(s==0)
                out.accept(csq);
            else
                out.accept(_CharSubSeq.of(csq, s, L));
        }
    }

    // length=128 to help branch prediction on c<escMap.length, for ascii charset
    static final String[] escMap3 = new String[128];
    static
    {
        escMap3['<'] = "&lt;";
        escMap3['>'] = "&gt;";
        escMap3['&'] = "&amp;";
    }
    static void renderEscaped3(CharSequence csq, Consumer<CharSequence> out)
    {
        renderEscaped(escMap3, csq, out);
    }

    static final String[] escMap4 = new String[128];
    static
    {
        escMap4['<'] = "&lt;";
        escMap4['>'] = "&gt;";
        escMap4['&'] = "&amp;";
        escMap4['"'] = "&quot;";
    }
    static void renderEscaped4(CharSequence csq, Consumer<CharSequence> out)
    {
        renderEscaped(escMap4, csq, out);
    }



}
