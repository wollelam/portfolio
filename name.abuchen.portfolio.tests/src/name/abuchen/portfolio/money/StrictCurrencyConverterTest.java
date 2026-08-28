package name.abuchen.portfolio.money;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.Test;

@SuppressWarnings("nls")
public class StrictCurrencyConverterTest
{
    private static final LocalDate DATE = LocalDate.parse("2025-01-02");

    @Test
    public void testRejectsMissingRate()
    {
        StrictCurrencyConverter converter = new StrictCurrencyConverter(new TestConverter("EUR", Optional.empty()));

        MonetaryException exception = assertThrows(MonetaryException.class,
                        () -> converter.convert(DATE, Money.of("USD", Values.Amount.factorize(10))));

        assertThat(exception.getMessage(), is("No exchange rate available to convert from USD to EUR"));
    }

    @Test
    public void testUsesAvailableRateAndRetainsStrictnessWithNewTermCurrency()
    {
        StrictCurrencyConverter converter = new StrictCurrencyConverter(new TestConverter("EUR",
                        Optional.of(new ExchangeRate(DATE, BigDecimal.valueOf(2)))));

        assertThat(converter.convert(DATE, Money.of("USD", Values.Amount.factorize(10))),
                        is(Money.of("EUR", Values.Amount.factorize(20))));
        assertThat(converter.with("CHF").getTermCurrency(), is("CHF"));
    }

    private record TestConverter(String getTermCurrency, Optional<ExchangeRate> rate) implements CurrencyConverter
    {
        @Override
        public ExchangeRate getRate(LocalDate date, String currencyCode)
        {
            return rate.orElse(new ExchangeRate(date, BigDecimal.ONE));
        }

        @Override
        public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currencyCode)
        {
            return getTermCurrency.equals(currencyCode) ? Optional.of(new ExchangeRate(date, BigDecimal.ONE)) : rate;
        }

        @Override
        public CurrencyConverter with(String currencyCode)
        {
            return new TestConverter(currencyCode, rate);
        }
    }
}
