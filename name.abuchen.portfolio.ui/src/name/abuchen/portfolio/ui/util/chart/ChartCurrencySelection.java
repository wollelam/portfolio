package name.abuchen.portfolio.ui.util.chart;

import java.util.Objects;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

public final class ChartCurrencySelection
{
    public static final String SECURITY = "SECURITY"; //$NON-NLS-1$
    public static final String PORTFOLIO = "PORTFOLIO"; //$NON-NLS-1$

    private ChartCurrencySelection()
    {
    }

    public static String resolve(String selection, Client client, Security security)
    {
        Objects.requireNonNull(selection);
        Objects.requireNonNull(client);

        if (SECURITY.equals(selection))
            return security != null && security.getCurrencyCode() != null ? security.getCurrencyCode()
                            : client.getBaseCurrency();
        else if (PORTFOLIO.equals(selection))
            return client.getBaseCurrency();
        else
            return selection;
    }

    public static String restore(String storedSelection, String defaultSelection)
    {
        if (SECURITY.equals(storedSelection) || PORTFOLIO.equals(storedSelection))
            return storedSelection;

        return defaultSelection;
    }
}
