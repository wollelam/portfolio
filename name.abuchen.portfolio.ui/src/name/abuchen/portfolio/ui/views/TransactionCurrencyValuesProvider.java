package name.abuchen.portfolio.ui.views;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Quote;

final class TransactionCurrencyValuesProvider
{
    static final class Values
    {
        private final Optional<BigDecimal> exchangeRate;
        private final Optional<Quote> grossPrice;
        private final Optional<Money> grossValue;
        private final Optional<Money> fees;
        private final Optional<Money> taxes;
        private final Optional<Money> netValue;

        private Values(Optional<BigDecimal> exchangeRate, Optional<Quote> grossPrice, Optional<Money> grossValue,
                        Optional<Money> fees, Optional<Money> taxes, Optional<Money> netValue)
        {
            this.exchangeRate = exchangeRate;
            this.grossPrice = grossPrice;
            this.grossValue = grossValue;
            this.fees = fees;
            this.taxes = taxes;
            this.netValue = netValue;
        }

        Optional<BigDecimal> getExchangeRate()
        {
            return exchangeRate;
        }

        Optional<Quote> getGrossPrice()
        {
            return grossPrice;
        }

        Optional<Money> getGrossValue()
        {
            return grossValue;
        }

        Optional<Money> getFees()
        {
            return fees;
        }

        Optional<Money> getTaxes()
        {
            return taxes;
        }

        Optional<Money> getNetValue()
        {
            return netValue;
        }
    }

    private static final class MissingExchangeRateException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    private static final class AvailableCurrencyConverter implements CurrencyConverter
    {
        private final ExchangeRateProviderFactory factory;
        private final String termCurrency;

        private AvailableCurrencyConverter(ExchangeRateProviderFactory factory, String termCurrency)
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
            if (termCurrency.equals(currencyCode))
                return new ExchangeRate(date, BigDecimal.ONE);

            return factory.getTimeSeries(currencyCode, termCurrency).lookupRate(date)
                            .orElseThrow(MissingExchangeRateException::new);
        }

        @Override
        public CurrencyConverter with(String currencyCode)
        {
            return termCurrency.equals(currencyCode) ? this : new AvailableCurrencyConverter(factory, currencyCode);
        }
    }

    private final ExchangeRateProviderFactory factory;
    private final Supplier<String> baseCurrencySupplier;
    private final Map<Transaction, Values> cache = new IdentityHashMap<>();

    private String cachedBaseCurrency;

    TransactionCurrencyValuesProvider(ExchangeRateProviderFactory factory, Supplier<String> baseCurrencySupplier)
    {
        this.factory = factory;
        this.baseCurrencySupplier = baseCurrencySupplier;
    }

    Values get(Transaction transaction)
    {
        String baseCurrency = baseCurrencySupplier.get();
        if (!baseCurrency.equals(cachedBaseCurrency))
        {
            cache.clear();
            cachedBaseCurrency = baseCurrency;
        }

        return cache.computeIfAbsent(transaction, tx -> calculate(tx, baseCurrency, factory));
    }

    void clear()
    {
        cache.clear();
    }

    static Values calculate(Transaction transaction, String baseCurrency, ExchangeRateProviderFactory factory)
    {
        var converter = new AvailableCurrencyConverter(factory, baseCurrency);

        Optional<BigDecimal> exchangeRate = optional(
                        () -> converter.getRate(transaction.getDateTime(), transaction.getCurrencyCode()).getValue());
        Optional<Money> grossValue = optional(() -> transaction instanceof PortfolioTransaction pt
                        ? pt.getGrossValue(converter)
                        : converter.convert(transaction.getDateTime(), transaction.getGrossValue()));
        Optional<Quote> grossPrice = transaction instanceof PortfolioTransaction pt && pt.getShares() != 0
                        ? optional(() -> pt.getGrossPricePerShare(converter))
                        : Optional.empty();
        Optional<Money> fees = optional(
                        () -> transaction.getUnitSum(Transaction.Unit.Type.FEE, converter));
        Optional<Money> taxes = optional(
                        () -> transaction.getUnitSum(Transaction.Unit.Type.TAX, converter));
        Optional<Money> netValue = optional(
                        () -> converter.convert(transaction.getDateTime(), transaction.getMonetaryAmount()));

        return new Values(exchangeRate, grossPrice, grossValue, fees, taxes, netValue);
    }

    private static <T> Optional<T> optional(Supplier<T> calculation)
    {
        try
        {
            return Optional.of(calculation.get());
        }
        catch (MissingExchangeRateException e)
        {
            return Optional.empty();
        }
    }
}
