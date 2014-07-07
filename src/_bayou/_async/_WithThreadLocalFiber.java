package _bayou._async;

// implemented by threads that directly host a thread local field for current fiber.
// an optimization for ThreadLocal
public interface _WithThreadLocalFiber
{
    Object getThreadLocalFiber();
    void setThreadLocalFiber(Object obj);
}
