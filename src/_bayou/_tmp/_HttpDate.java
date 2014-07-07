package _bayou._tmp;

import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

// todo: java8 new format API?
public class _HttpDate
{
    // a new GregorianCalendar is not cheap. so use a thread local one.
    // as a rule, do not extends ThreadLocal.
    final static ThreadLocal<GregorianCalendar> calTL = new ThreadLocal<>();
    static GregorianCalendar getLocalCal()
    {
        GregorianCalendar cal = calTL.get();
        if(cal==null)
            calTL.set( cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"), Locale.US) );
        return cal;
    }

    static char[] c(String s){ return s.toCharArray(); }
    static final char[] templateA = c("Xxx, 00 Xxx 0000 00:00:00 GMT");
  //static final char[] templateB = c("Xxx, 00-Xxx-0000 00:00:00 GMT");
    static final char[][] DAYS = {c("   "),
            c("Sun"), c("Mon"), c("Tue"), c("Wed"), c("Thu"), c("Fri"), c("Sat")};
    static final char[][] MONTHS = {
            c("Jan"), c("Feb"), c("Mar"), c("Apr"), c("May"), c("Jun"),
            c("Jul"), c("Aug"), c("Sep"), c("Oct"), c("Nov"), c("Dec")};

    // the year of http date must be 4 digits, 0001-9999
    // if input date is out of range, we silently adjust it. not sure if this could be surprising to apps.
    static final Instant minDate = Instant.ofEpochSecond(-62_135_769_600L); // Sat, 01 Jan 0001 00:00:00 GMT
    static final Instant maxDate = Instant.ofEpochSecond(253_402_300_799L); // Fri, 31 Dec 9999 23:59:59 GMT
    // not sure what to do with pre-Gregorian dates. we use Julia here.
    //     the day before [October 15, 1582] is [October 4, 1582]

    // 170ns @2.5GHz
    public static String toHttpDate(Instant instant) // rfc1123 date
    {
        if(instant.isBefore(minDate))
            instant = minDate;
        else if(instant.isAfter(maxDate))
            instant = maxDate;
        long ms = instant.toEpochMilli(); // will not overflow
        return format(ms, templateA);
    }
    static private String format(long millis, char[] template)
    {
        GregorianCalendar cal = getLocalCal();
        cal.setTimeInMillis(millis);

        // Fri, 23 Mar 2012 17:21:57 GMT
        char[] chars = template.clone();

        char[] day = DAYS[cal.get(Calendar.DAY_OF_WEEK)];  // 1-7
        chars[0]=day[0];chars[1]=day[1];chars[2]=day[2];

        i2c(chars, 6, cal.get(Calendar.DAY_OF_MONTH));  // >=1

        char[] month = MONTHS[cal.get(Calendar.MONTH)];   // >=0
        chars[8]=month[0];chars[9]=month[1];chars[10]=month[2];

        // caller guarantees that year is 1-9999.
        i2c(chars, 15, cal.get(Calendar.YEAR));

        i2c(chars, 18, cal.get(Calendar.HOUR_OF_DAY));
        i2c(chars, 21, cal.get(Calendar.MINUTE));
        i2c(chars, 24, cal.get(Calendar.SECOND));

        return new String(chars);
    }

    // requires that string is rfc1123-date.
    // won't work if string is in other 2 obsolete formats; may return false negative.
    public static boolean match(Instant instant, String string)
    {
        return _StrUtil.equalIgnoreCase(toHttpDate(instant), string);
    }

    static void i2c(char[] chars, int pos, int i)
    {
        while(i>0)
        {
            int d = i%10;
            chars[pos--] = (char)('0'+d);
            i=i/10;
        }
    }


    /// curr date. precise to 1 second


    static final Object lock = new Object();
    static volatile long millis_volatile;  // always beginning of a second. (multiple of 1000)
    static volatile String string_volatile;

    // 18ns @2.5GHz
    public static String getCurrStr()
    {
        long now = System.currentTimeMillis();
        if(now- millis_volatile < 1000)   // the same second
            return string_volatile;      // the string is still good. (possibly for a new millis)

        // else a new second. need to update the string
        String s = format(now, templateA);
        long m = now/1000*1000;
        synchronized (lock)
        {
            if(m> millis_volatile)
            {
                string_volatile = s; // do this first, so string is never later than millis
                millis_volatile = m;
            }
        }
        return s;
    }
}
