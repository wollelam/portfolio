package name.abuchen.portfolio.money;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.Messages;

/**
 * A converter that rejects conversions for which no historical exchange rate
 * is available instead of falling back to a rate of one.
 */
public final class StrictCurrencyConverter implements CurrencyConverter
{
    private final CurrencyConverter delegate;

    public StrictCurrencyConverter(CurrencyConverter delegate)
    {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public String getTermCurrency()
    {
        return delegate.getTermCurrency();
    }

    @Override
    public ExchangeRate getRate(LocalDate date, String currencyCode)
    {
        return getRateIfAvailable(date, currencyCode)
                        .orElseThrow(() -> new MonetaryException(MessageFormat.format(
                                        Messages.MsgNoExchangeRateAvailableForConversion, currencyCode,
                                        getTermCurrency())));
    }

    @Override
    public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currencyCode)
    {
        return delegate.getRateIfAvailable(date, currencyCode);
    }

    @Override
    public CurrencyConverter with(String currencyCode)
    {
        if (currencyCode.equals(getTermCurrency()))
            return this;

        return new StrictCurrencyConverter(delegate.with(currencyCode));
    }
}
