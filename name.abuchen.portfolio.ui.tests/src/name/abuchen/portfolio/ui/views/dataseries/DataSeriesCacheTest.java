package name.abuchen.portfolio.ui.views.dataseries;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.ui.util.chart.ChartCurrencySelection;
import name.abuchen.portfolio.ui.views.dataseries.DataSeries.ClientDataSeries;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class DataSeriesCacheTest
{
    private static final Interval INTERVAL = Interval.of(LocalDate.parse("2025-01-01"),
                    LocalDate.parse("2025-01-10"));

    @Test
    public void testCacheSeparatesCalculationsByResolvedCurrency()
    {
        Client client = new Client();
        client.setBaseCurrency("EUR");
        DataSeriesCache cache = new DataSeriesCache(client, new ExchangeRateProviderFactory(client));
        DataSeries series = new DataSeries(DataSeries.Type.CLIENT, ClientDataSeries.TOTALS, "Client", null);

        var eur = cache.lookup(series, INTERVAL, ChartCurrencySelection.PORTFOLIO);
        var eurAgain = cache.lookup(series, INTERVAL, "EUR");
        var usd = cache.lookup(series, INTERVAL, "USD");

        assertSame(eur, eurAgain);
        assertNotSame(eur, usd);
    }

    @Test
    public void testSecurityCurrencyResolvesPerSeries()
    {
        Client client = new Client();
        client.setBaseCurrency("EUR");
        Security security = new Security();
        security.setCurrencyCode("USD");
        client.addSecurity(security);

        DataSeriesCache cache = new DataSeriesCache(client, new ExchangeRateProviderFactory(client));
        DataSeries series = new DataSeries(DataSeries.Type.SECURITY, security, "Security", null);

        var nativeCurrency = cache.lookup(series, INTERVAL, ChartCurrencySelection.SECURITY);
        var explicitUsd = cache.lookup(series, INTERVAL, "USD");
        var portfolioCurrency = cache.lookup(series, INTERVAL, ChartCurrencySelection.PORTFOLIO);

        assertSame(nativeCurrency, explicitUsd);
        assertNotSame(nativeCurrency, portfolioCurrency);
    }
}
