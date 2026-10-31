package name.abuchen.portfolio.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.impl.EmptyExchangeRateTimeSeries;

/** Uses the latest recorded rate through a date, or the first recorded rate before a series begins. */
final class AvailableCurrencyConverter implements CurrencyConverter
{
    private final ExchangeRateProviderFactory factory;
    private final String termCurrency;

    AvailableCurrencyConverter(ExchangeRateProviderFactory factory, String termCurrency)
    {
        this.factory = factory;
        this.termCurrency = termCurrency;
    }

    @Override
    public String getTermCurrency()
    {
        return termCurrency;
    }

    @Override
    public ExchangeRate getRate(LocalDate date, String currencyCode)
    {
        return getRateIfAvailable(date, currencyCode).orElseThrow(
                        () -> new DashboardService.MissingReportingRateException(currencyCode, termCurrency, date));
    }

    @Override
    public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currencyCode)
    {
        if (termCurrency.equals(currencyCode))
            return Optional.of(new ExchangeRate(date, BigDecimal.ONE));
        var series = factory.getTimeSeries(currencyCode, termCurrency);
        return series == null || series instanceof EmptyExchangeRateTimeSeries ? Optional.empty()
                        : series.lookupRate(date);
    }

    @Override
    public CurrencyConverter with(String currencyCode)
    {
        return currencyCode.equals(termCurrency) ? this : new AvailableCurrencyConverter(factory, currencyCode);
    }
}
