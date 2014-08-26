package _bayou._tmp;

public class _Tcp
{
    public static void validate_confSelectorIds(int[] confSelectorIds) throws IllegalArgumentException
    {
        _Util.require(confSelectorIds.length>0, "confSelectorIds.length>0");
        final int NS = confSelectorIds.length;

        // no duplicate ids
        for(int i = 0; i<NS; i++)
            for(int j=i+1; j<NS; j++)
                if(confSelectorIds[i]==confSelectorIds[j])
                    throw new IllegalArgumentException("duplicate in confSelectorIds: id="+confSelectorIds[i]);

    }
}
