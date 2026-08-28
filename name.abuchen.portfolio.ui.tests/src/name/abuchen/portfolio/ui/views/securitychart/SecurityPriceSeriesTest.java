package name.abuchen.portfolio.ui.views.securitychart;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

        SecurityPriceSeries.ConversionResult result = SecurityPriceSeries.convert(prices, "USD", converter);
        List<SecurityPrice> converted = result.getConvertedPrices();

        assertThat(converted.get(0).getValue(), is(Values.Quote.factorize(20)));
        assertThat(converted.get(1).getValue(), is(Values.Quote.factorize(30)));

        // Conversion is a presentation concern and must not modify stored prices.
        assertThat(prices.get(0).getValue(), is(Values.Quote.factorize(10)));
        assertThat(prices.get(1).getValue(), is(Values.Quote.factorize(10)));
        assertThat(result.getRateUses(), is(List.of( //
                        new SecurityPriceSeries.RateUse(LocalDate.parse("2025-01-02"),
                                        LocalDate.parse("2025-01-02")),
                        new SecurityPriceSeries.RateUse(LocalDate.parse("2025-01-03"),
                                        LocalDate.parse("2025-01-03")))));
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

        assertThat(SecurityPriceSeries.convert(prices, "EUR", converter).getConvertedPrices(), is(prices));
        assertThat(SecurityPriceSeries.convert(prices, null, converter).getConvertedPrices(), is(prices));
    }

    @Test
    public void testMissingRateDoesNotProduceOneToOnePrices()
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
                return new ExchangeRate(date, BigDecimal.ONE);
            }

            @Override
            public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currencyCode)
            {
                return Optional.empty();
            }

            @Override
            public CurrencyConverter with(String currencyCode)
            {
                throw new UnsupportedOperationException();
            }
        };

        SecurityPriceSeries.ConversionResult result = SecurityPriceSeries.convert(prices, "USD", converter);

        assertThat(result.getConvertedPrices(), is(List.of()));
        assertThat(result.getConvertedPriceSegments(), is(List.of()));
        assertThat(result.getMissingExchangeRateIntervals(), is(List.of(
                        new SecurityPriceSeries.MissingExchangeRateInterval(LocalDate.parse("2025-01-02"),
                                        LocalDate.parse("2025-01-02")))));
    }

    @Test
    public void testKeepsMissingPrefixSeparateFromConvertedPrices()
    {
        List<SecurityPrice> prices = List.of( //
                        new SecurityPrice(LocalDate.parse("2010-12-01"), Values.Quote.factorize(10)),
                        new SecurityPrice(LocalDate.parse("2012-09-11"), Values.Quote.factorize(10)),
                        new SecurityPrice(LocalDate.parse("2012-09-12"), Values.Quote.factorize(10)),
                        new SecurityPrice(LocalDate.parse("2012-09-13"), Values.Quote.factorize(10)));

        CurrencyConverter converter = new CurrencyConverter()
        {
            @Override
            public String getTermCurrency()
            {
                return "CHF";
            }

            @Override
            public ExchangeRate getRate(LocalDate date, String currencyCode)
            {
                throw new AssertionError("Only available rates must be used");
            }

            @Override
            public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currencyCode)
            {
                return date.isBefore(LocalDate.parse("2012-09-12")) ? Optional.empty()
                                : Optional.of(new ExchangeRate(LocalDate.parse("2012-09-12"), BigDecimal.valueOf(2)));
            }

            @Override
            public CurrencyConverter with(String currencyCode)
            {
                throw new UnsupportedOperationException();
            }
        };

        SecurityPriceSeries.ConversionResult result = SecurityPriceSeries.convert(prices, "USD", converter);

        assertThat(result.getSourcePrices(), is(prices));
        assertThat(result.getSourceStartDate(), is(Optional.of(LocalDate.parse("2010-12-01"))));
        assertThat(result.getSourceEndDate(), is(Optional.of(LocalDate.parse("2012-09-13"))));
        assertThat(result.getConvertedPrices(), is(List.of( //
                        new SecurityPrice(LocalDate.parse("2012-09-12"), Values.Quote.factorize(20)),
                        new SecurityPrice(LocalDate.parse("2012-09-13"), Values.Quote.factorize(20)))));
        assertThat(result.getConvertedPriceSegments(), is(List.of(result.getConvertedPrices())));
        assertThat(result.getMissingExchangeRateIntervals(), is(List.of(
                        new SecurityPriceSeries.MissingExchangeRateInterval(LocalDate.parse("2010-12-01"),
                                        LocalDate.parse("2012-09-11")))));
        assertThat(result.getRateUses(), is(List.of( //
                        new SecurityPriceSeries.RateUse(LocalDate.parse("2012-09-12"),
                                        LocalDate.parse("2012-09-12")),
                        new SecurityPriceSeries.RateUse(LocalDate.parse("2012-09-13"),
                                        LocalDate.parse("2012-09-12")))));
    }

    @Test
    public void testReportsEntirelyMissingPairAsOneClosedInterval()
    {
        List<SecurityPrice> prices = List.of( //
                        new SecurityPrice(LocalDate.parse("2010-12-01"), Values.Quote.factorize(10)),
                        new SecurityPrice(LocalDate.parse("2010-12-02"), Values.Quote.factorize(11)));

        CurrencyConverter converter = new CurrencyConverter()
        {
            @Override
            public String getTermCurrency()
            {
                return "CHF";
            }

            @Override
            public ExchangeRate getRate(LocalDate date, String currencyCode)
            {
                throw new AssertionError("No exchange rate should be requested");
            }

            @Override
            public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currencyCode)
            {
                return Optional.empty();
            }

            @Override
            public CurrencyConverter with(String currencyCode)
            {
                throw new UnsupportedOperationException();
            }
        };

        SecurityPriceSeries.ConversionResult result = SecurityPriceSeries.convert(prices, "USD", converter);

        assertThat(result.getConvertedPrices(), is(List.of()));
        assertThat(result.getMissingExchangeRateIntervals(), is(List.of(
                        new SecurityPriceSeries.MissingExchangeRateInterval(LocalDate.parse("2010-12-01"),
                                        LocalDate.parse("2010-12-02")))));
    }
}
