package name.abuchen.portfolio.ui.jobs.priceupdate;

public enum PriceUpdateMode
{
    LIVE("LIVE"), //$NON-NLS-1$
    BATCHED("BATCHED"); //$NON-NLS-1$

    private final String code;

    PriceUpdateMode(String code)
    {
        this.code = code;
    }

    public String getCode()
    {
        return code;
    }

    public static PriceUpdateMode fromCode(String code)
    {
        if (code != null)
        {
            for (var mode : values())
            {
                if (mode.code.equals(code))
                    return mode;
            }
        }

        return LIVE;
    }
}
