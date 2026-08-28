package name.abuchen.portfolio.ui.util.chart;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

@SuppressWarnings("nls")
public class ChartCurrencySelectionTest
{
    @Test
    public void testResolvesPortfolioSecurityAndSpecificCurrency()
    {
        Client client = new Client();
        client.setBaseCurrency("CHF");
        Security security = new Security();
        security.setCurrencyCode("USD");

        assertThat(ChartCurrencySelection.resolve(ChartCurrencySelection.PORTFOLIO, client, security), is("CHF"));
        assertThat(ChartCurrencySelection.resolve(ChartCurrencySelection.SECURITY, client, security), is("USD"));
        assertThat(ChartCurrencySelection.resolve("EUR", client, security), is("EUR"));
        assertThat(ChartCurrencySelection.resolve(ChartCurrencySelection.SECURITY, client, null), is("CHF"));
    }

    @Test
    public void testRestoresSupportedPersistedSelections()
    {
        assertThat(ChartCurrencySelection.restore(ChartCurrencySelection.SECURITY, "EUR"),
                        is(ChartCurrencySelection.SECURITY));
        assertThat(ChartCurrencySelection.restore(ChartCurrencySelection.PORTFOLIO, "EUR"),
                        is(ChartCurrencySelection.PORTFOLIO));
        assertThat(ChartCurrencySelection.restore("invalid", ChartCurrencySelection.SECURITY),
                        is(ChartCurrencySelection.SECURITY));
    }

    @Test
    public void testRestoresLegacyCurrencyCodeToSuppliedDefault()
    {
        assertThat(ChartCurrencySelection.restore("USD", ChartCurrencySelection.PORTFOLIO),
                        is(ChartCurrencySelection.PORTFOLIO));
        assertThat(ChartCurrencySelection.restore("EUR", ChartCurrencySelection.SECURITY),
                        is(ChartCurrencySelection.SECURITY));
    }
}
