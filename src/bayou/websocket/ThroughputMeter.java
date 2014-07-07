package bayou.websocket;

import java.time.Duration;

class ThroughputMeter
{
    final long minThroughput;  // bytes per second, >=0
    final long timeWindow;     // ns

    public ThroughputMeter(long minThroughput, Duration timeWindow)
    {
        this.minThroughput = minThroughput;
        this.timeWindow = Math.max(timeWindow.toNanos(), 1000_000_000L);  // at least one second
    }

    // remember only recent history 2*timeWindow
    long bytes1;
    long time1;
    long bytes2;
    long time2;

    long t0; // 0 if clock is paused, for assertions

    public void resumeClock()
    {
        assert t0==0;
        t0 = System.nanoTime();
    }

    public boolean isPaused(){ return t0==0; }

    public void pauseClock()
    {
        assert t0!=0;
        time2 += (System.nanoTime()-t0);
        t0 = 0;
    }

    // return false if throughput is lower than min
    public boolean reportBytes(long bytes)
    {
        assert t0!=0;

        bytes2 += bytes;

        long now = System.nanoTime();
        time2 += (now-t0);
        t0 = now;

        if(time2>timeWindow)
        {
            bytes1 = bytes2;
            time1 = time2;
            bytes2 = 0;
            time2 = 0;
        }

        long time = time1 + time2;
        if(time<timeWindow)  // not enough time to do average
            return true;

        // return bytes1+bytes2 >= minThroughput * time / 1000_000_000L;  // possible overflow
        if(minThroughput==0)
            return true;

        return (bytes1+bytes2) * 1000L / minThroughput >= time / 1000_000L;
        // accurate to 1ms.
    }
}
