package name.abuchen.portfolio.ui.views.securitychart;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class SecurityPriceSeriesTest
{
    @Test
    public void testConvertsEveryPriceWithRateForItsDate()
    {
        List<SecurityPrice> prices = List.of( //
                        new SecurityPrice(LocalDate.parse("2025-01-02"), Values.Quote.factorize(10)),
                        new SecurityPrice(LocalDate.parse("2025-01-03"), Values.Quote.factorize(10)));

        CurrencyConverter converter = new CurrencyConverter()
        {
            @Override
            public String getTermCurrency()
            {
                return "EUR";
            }

            @Override
            public ExchangeRate getRate(LocalDate date, String currencyCode)
            {
                return new ExchangeRate(date, BigDecimal.valueOf(date.getDayOfMonth()));
            }

            @Override
            public CurrencyConverter with(String currencyCode)
            {
                throw new UnsupportedOperationException();
            }
        };

        List<SecurityPrice> converted = SecurityPriceSeries.convert(prices, "USD", converter);

        assertThat(converted.get(0).getValue(), is(Values.Quote.factorize(20)));
        assertThat(converted.get(1).getValue(), is(Values.Quote.factorize(30)));

        // Conversion is a presentation concern and must not modify stored prices.
        assertThat(prices.get(0).getValue(), is(Values.Quote.factorize(10)));
        assertThat(prices.get(1).getValue(), is(Values.Quote.factorize(10)));
    }

    @Test
    public void testSameAndMissingSourceCurrencyRemainUnchanged()
    {
        List<SecurityPrice> prices = List.of(
                        new SecurityPrice(LocalDate.parse("2025-01-02"), Values.Quote.factorize(10)));

        CurrencyConverter converter = new CurrencyConverter()
        {
            @Override
            public String getTermCurrency()
            {
                return "EUR";
            }

            @Override
            public ExchangeRate getRate(LocalDate date, String currencyCode)
            {
                throw new AssertionError("No exchange rate should be requested");
            }

            @Override
            public CurrencyConverter with(String currencyCode)
            {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(SecurityPriceSeries.convert(prices, "EUR", converter), is(prices));
        assertThat(SecurityPriceSeries.convert(prices, null, converter), is(prices));
    }
}
