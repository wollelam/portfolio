package name.abuchen.portfolio.ui.views;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Quote;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;

@SuppressWarnings("nls")
public class TransactionCurrencyValuesProviderTest
{
    private static final LocalDateTime DATE = LocalDateTime.parse("2025-01-02T12:00:00");

    @Test
    public void testAccountTransactionInBaseCurrency()
    {
        var transaction = new AccountTransaction(DATE, CurrencyUnit.EUR, 10_000, null,
                        AccountTransaction.Type.DIVIDENDS);
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(CurrencyUnit.EUR, 200)));
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(CurrencyUnit.EUR, 300)));

        var values = TransactionCurrencyValuesProvider.calculate(transaction, CurrencyUnit.EUR, factory(null));

        assertThat(values.getExchangeRate(), is(Optional.of(BigDecimal.ONE)));
        assertThat(values.getGrossPrice(), is(Optional.empty()));
        assertThat(values.getGrossValue(), is(Optional.of(Money.of(CurrencyUnit.EUR, 10_500))));
        assertThat(values.getFees(), is(Optional.of(Money.of(CurrencyUnit.EUR, 200))));
        assertThat(values.getTaxes(), is(Optional.of(Money.of(CurrencyUnit.EUR, 300))));
        assertThat(values.getNetValue(), is(Optional.of(Money.of(CurrencyUnit.EUR, 10_000))));
    }

    @Test
    public void testAccountTransactionConvertedToBaseCurrency()
    {
        var transaction = new AccountTransaction(DATE, CurrencyUnit.USD, 10_000, null,
                        AccountTransaction.Type.DIVIDENDS);
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(CurrencyUnit.USD, 200)));
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(CurrencyUnit.USD, 300)));

        var values = TransactionCurrencyValuesProvider.calculate(transaction, CurrencyUnit.EUR,
                        factory(new BigDecimal("0.8")));

        assertThat(values.getExchangeRate(), is(Optional.of(new BigDecimal("0.8"))));
        assertThat(values.getGrossValue(), is(Optional.of(Money.of(CurrencyUnit.EUR, 8_400))));
        assertThat(values.getFees(), is(Optional.of(Money.of(CurrencyUnit.EUR, 160))));
        assertThat(values.getTaxes(), is(Optional.of(Money.of(CurrencyUnit.EUR, 240))));
        assertThat(values.getNetValue(), is(Optional.of(Money.of(CurrencyUnit.EUR, 8_000))));
    }

    @Test
    public void testMissingExchangeRateDoesNotFabricateValues()
    {
        var transaction = new AccountTransaction(DATE, CurrencyUnit.USD, 10_000, null,
                        AccountTransaction.Type.DIVIDENDS);
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(CurrencyUnit.USD, 200)));
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(CurrencyUnit.USD, 300)));

        var values = TransactionCurrencyValuesProvider.calculate(transaction, CurrencyUnit.EUR, factory(null));

        assertThat(values.getExchangeRate(), is(Optional.empty()));
        assertThat(values.getGrossValue(), is(Optional.empty()));
        assertThat(values.getFees(), is(Optional.empty()));
        assertThat(values.getTaxes(), is(Optional.empty()));
        assertThat(values.getNetValue(), is(Optional.empty()));
    }

    @Test
    public void testFirstFutureExchangeRateDoesNotFabricateHistoricalValues()
    {
        var transaction = new AccountTransaction(DATE, CurrencyUnit.USD, 10_000, null,
                        AccountTransaction.Type.DIVIDENDS);

        var values = TransactionCurrencyValuesProvider.calculate(transaction, CurrencyUnit.EUR,
                        factory(DATE.toLocalDate().plusDays(1), new BigDecimal("0.8")));

        assertThat(values.getExchangeRate(), is(Optional.empty()));
        assertThat(values.getGrossValue(), is(Optional.empty()));
        assertThat(values.getNetValue(), is(Optional.empty()));
    }

    @Test
    public void testBookedForexGrossValueDoesNotRequireHistoricalRate()
    {
        var security = new Security("Security", CurrencyUnit.EUR);
        var transaction = new PortfolioTransaction(DATE, CurrencyUnit.USD, 12_300, security,
                        Values.Share.factorize(10), PortfolioTransaction.Type.BUY, 300, 0);
        transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                        Money.of(CurrencyUnit.USD, 12_000), Money.of(CurrencyUnit.EUR, 10_000),
                        new BigDecimal("1.2")));

        var values = TransactionCurrencyValuesProvider.calculate(transaction, CurrencyUnit.EUR, factory(null));

        assertThat(values.getExchangeRate(), is(Optional.empty()));
        assertThat(values.getGrossValue(), is(Optional.of(Money.of(CurrencyUnit.EUR, 10_000))));
        assertThat(values.getGrossPrice(),
                        is(Optional.of(Quote.of(CurrencyUnit.EUR, Values.Quote.factorize(10)))));
        assertThat(values.getFees(), is(Optional.empty()));
        assertThat(values.getNetValue(), is(Optional.empty()));
    }

    private ExchangeRateProviderFactory factory(BigDecimal rate)
    {
        return factory(DATE.toLocalDate(), rate);
    }

    private ExchangeRateProviderFactory factory(LocalDate date, BigDecimal rate)
    {
        var series = new ExchangeRateTimeSeriesImpl(null, CurrencyUnit.USD, CurrencyUnit.EUR);
        if (rate != null)
            series.addRate(new ExchangeRate(date, rate));

        return new ExchangeRateProviderFactory(new Client())
        {
            @Override
            public ExchangeRateTimeSeries getTimeSeries(String baseCurrency, String termCurrency)
            {
                return series;
            }
        };
    }
}
