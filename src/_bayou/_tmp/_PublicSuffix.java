package _bayou._tmp;

import java.io.*;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

public class _PublicSuffix
{
    // https://publicSuffix.org
    // we make some assumptions that simplifies our algorithm.
    // these assumptions are true for the current rule list, but may break in future.

    // [assumption]
    // there are only 3 types of rules:
    //      <domain>  -  normal.    e.g. "com", "co.uk"
    //    *.<domain>  -  star.      e.g. "*.ck"
    //     !<domain>  -  exception. e.g. "!www.ck"
    // <domain> is normal domain, containing no "*". it's lower case, A-Label or NR-LDH.
    // a domain can only be mapped to one type (or none)

    enum Type { normal, exception, star }
    static final HashMap<String,Type> rules = new HashMap<>();  // domain->type
    static
    {
        try
        {
            loadRules();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    static void loadRules() throws Exception
    {
        try( InputStream r1 = _PublicSuffix.class.getResourceAsStream("/_bayou/__resource/public.suffix.txt") ;
             Reader r2 = new InputStreamReader( r1, StandardCharsets.UTF_8);
             BufferedReader r3 = new BufferedReader(r2) )
        {
            r3.lines().forEach(line->
            {
                try
                {
                    addRuleLine(line);
                }
                catch (Exception e)
                {
                    throw new RuntimeException("unexpected rule: "+line, e);
                }
            });
        }
    }
    static void addRuleLine(String line) throws Exception
    {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("//"))
            return;

        Type type;
        String domain;
        if(line.startsWith("*."))
        {
            type = Type.star;
            domain = line.substring(2);
        }
        else if(line.startsWith("!"))
        {
            type = Type.exception;
            domain = line.substring(1);
            // [assumption] exception rule contains at least 2 labels
            if(!domain.contains("."))
                throw new Exception();
        }
        else
        {
            type = Type.normal;
            domain = line;
        }

        // [assumption] there is at most one "*", at the left-most level, followed by more labels.
        if(domain.contains("*"))
            throw new RuntimeException();

        domain = IDN.toASCII(domain).toLowerCase();

        // [assumption] one rule for each domain. no cases like { "*.x.y" and "x.y" }
        if(rules.containsKey(domain))
            throw new Exception();

        rules.put(domain, type);
    }


    // ==========================================================================================================

    // the input is in canonical form - lower case, A-Label or NR-LDH.
    public static boolean isPublicSuffix(String domain)
    {
        Type type = rules.get(domain);
        if(type==null)
        {
            String parent = _Dns.parent(domain);
            if(parent==null)    // domain is unknown TLD.
                return true;    // treat all TLDs as public suffix, as far as cookie is concerned
            return rules.get(parent)==Type.star;  // *.parent
        }
        switch(type)
        {
            case normal :    return true;
            case exception:  return false;

            // rule [*.domain] - domain is not public suffix, unless it's TLD
            case star: return domain.indexOf('.')==-1;

            default: throw new AssertionError();
        }

    }

}
