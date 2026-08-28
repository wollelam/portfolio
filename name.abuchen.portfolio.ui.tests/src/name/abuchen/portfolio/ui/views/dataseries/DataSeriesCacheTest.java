package name.abuchen.portfolio.ui.views.dataseries;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.IsCloseTo.closeTo;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Values;
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

    @Test
    public void testBenchmarkPerformanceUsesDailyExchangeRates()
    {
        LocalDate start = LocalDate.parse("2025-01-02");
        LocalDate end = LocalDate.parse("2025-01-06");

        Client client = new Client();
        client.setBaseCurrency("EUR");
        new AccountBuilder().deposit_(start.atStartOfDay(), Values.Amount.factorize(100)).addTo(client);

        Security security = new Security("Constant USD stock", "USD");
        security.addPrice(new SecurityPrice(start, Values.Quote.factorize(100)));
        security.addPrice(new SecurityPrice(end, Values.Quote.factorize(100)));
        client.addSecurity(security);

        Security exchangeRate = new Security("Daily USD/EUR", "USD");
        exchangeRate.setTargetCurrencyCode("EUR");
        exchangeRate.addPrice(new SecurityPrice(start, Values.Quote.factorize(0.80)));
        exchangeRate.addPrice(new SecurityPrice(LocalDate.parse("2025-01-03"), Values.Quote.factorize(0.90)));
        exchangeRate.addPrice(new SecurityPrice(end, Values.Quote.factorize(1.00)));
        client.addSecurity(exchangeRate);

        DataSeriesCache cache = new DataSeriesCache(client, new ExchangeRateProviderFactory(client));
        DataSeries series = new DataSeries(DataSeries.Type.SECURITY_BENCHMARK, security, "Security", null);
        Interval interval = Interval.of(start.minusDays(1), end);

        var nativeCurrency = cache.lookup(series, interval, ChartCurrencySelection.SECURITY);
        var euro = cache.lookup(series, interval, "EUR");

        assertThat(nativeCurrency.getFinalAccumulatedPercentage(), closeTo(0, 0.000001));
        assertThat(euro.getFinalAccumulatedPercentage(), closeTo(0.25, 0.000001));
    }
}
