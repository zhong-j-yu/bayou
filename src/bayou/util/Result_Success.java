package bayou.util;

/**
 * An implementation of success Result.
 */
class Result_Success<T> implements Result<T>
{
    final T value;

    /**
     * Create a Success Result with the value.
     * @param value
     *        the success value; Can be null.
     */
    public Result_Success(T value)
    {
        // value can be null
        this.value = value;
    }

    /**
     * Return true.
     */
    @Override
    public boolean isSuccess()
    {
        return true;
    }

    /**
     * Return false.
     */
    @Override
    public boolean isFailure()
    {
        return false;
    }

    /**
     * Return the success value.
     */
    @Override
    public T getValue()
    {
        return value;
    }

    /**
     * Return null, because this is a success Result.
     */
    @Override
    public Exception getException()
    {
        return null;
    }

    /**
     * Return the success value.
     */
    @Override
    public T getOrThrow()
    {
        return value;
    }

    @Override
    public String toString()
    {
        return "Result:Success: "+value;
    }
}
