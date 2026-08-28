package name.abuchen.portfolio.money;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;

@SuppressWarnings("nls")
public class CurrencyConverterImplTest
{
    @Test
    public void testRateBeforeStartOfSeriesUsesFirstRateButStrictLookupDoesNot()
    {
        var series = new ExchangeRateTimeSeriesImpl(null, "DKK", "EUR");
        series.addRate(new ExchangeRate(LocalDate.parse("2014-01-02"), BigDecimal.valueOf(7.5)));

        var converter = new CurrencyConverterImpl(new TestFactory(series), "EUR");

        assertThat(converter.getRate(LocalDate.parse("2012-01-02"), "DKK").getValue(),
                        is(BigDecimal.valueOf(7.5)));
        assertThat(converter.getRateIfAvailable(LocalDate.parse("2012-01-02"), "DKK").isPresent(), is(false));
        assertThrows(MonetaryException.class,
                        () -> new StrictCurrencyConverter(converter).getRate(LocalDate.parse("2012-01-02"), "DKK"));
    }

    private static class TestFactory extends ExchangeRateProviderFactory
    {
        private final ExchangeRateTimeSeries series;

        TestFactory(ExchangeRateTimeSeries series)
        {
            super(new Client());
            this.series = series;
        }

        @Override
        public ExchangeRateTimeSeries getTimeSeries(String baseCurrency, String termCurrency)
        {
            return series;
        }
    }
}
