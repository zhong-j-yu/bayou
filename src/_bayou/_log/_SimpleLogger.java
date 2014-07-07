package _bayou._log;

import java.io.PrintStream;

// public, in case user app just wants to change `level` or `out`
public class _SimpleLogger implements _Logger
{
    final String name;
    final _Level level;
    final PrintStream out;

    public _SimpleLogger(String name, _Level level, PrintStream out)
    {
        this.name = name;
        this.level = level;
        this.out = out;
    }

    @Override
    public _Level level()
    {
        return level;
    }

    @Override
    public void impl_log(_Level level, CharSequence message, Throwable throwable)
    {
        out.printf("%1$tF %1$tT.%1$tL [%2$s] %3$-5s %4$s - %5$s%n",
            new java.util.Date(), Thread.currentThread().getName(), level, name, message);

        if(throwable!=null)
            throwable.printStackTrace(out);
    }
}
