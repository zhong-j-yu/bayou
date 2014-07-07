package bayou.util;

import _bayou._async._Fiber_Stack_Trace_;
import bayou.async.Fiber;

import java.util.Objects;

/**
 * An implementation of failure Result.
 */
class Result_Failure<T> implements Result<T>
{
    final Exception exception;

    /**
     * Create a Failure Result.
     * @param exception
     *        the failure exception. Must be non-null.
     */
    public Result_Failure(Exception exception)
    {
        Objects.requireNonNull(exception);

        if(Fiber.enableTrace)
            _Fiber_Stack_Trace_.addFiberStackTrace(exception, Fiber.current()); // fiber may be null

        this.exception = exception;
    }

    /**
     * Return false.
     */
    @Override
    public boolean isSuccess()
    {
        return false;
    }

    /**
     * Return true.
     */
    @Override
    public boolean isFailure()
    {
        return true;
    }

    /**
     * Return null, because this is a failure Result.
     */
    @Override
    public T getValue()
    {
        return null;
    }

    /**
     * Return the failure exception.
     */
    @Override
    public Exception getException()
    {
        return exception;
    }

    /**
     * Throw the failure exception.
     */
    @Override
    public T getOrThrow() throws Exception
    {
        throw exception;
    }

    @Override
    public String toString()
    {
        return "Result:Failure: "+exception;
    }
}
