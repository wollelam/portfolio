package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class SecurityActivityServiceTest
{
    private final LocalDate from = LocalDate.of(2025, 6, 10);
    private final LocalDate to = from.plusDays(2);
    private final Client client = new Client();
    private final Account account = new Account("Cash");
    private final Portfolio portfolio = new Portfolio("Investments");
    private final Security security = new Security("Income security", "EUR");

    public SecurityActivityServiceTest()
    {
        client.setBaseCurrency("EUR");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);
        client.addSecurity(security);
    }

    @Test
    public void showsOnlySelectedSecurityAndPeriodWithoutDuplicatingCashTradeLegs()
    {
        trade(PortfolioTransaction.Type.BUY, from.plusDays(1));
        trade(PortfolioTransaction.Type.SELL, to);
        payment(from, "EUR", 850); // Excluded opening date.
        var paid = payment(to, "EUR", 850);
        paid.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 100)));
        paid.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 50)));
        payment(to.plusDays(1), "EUR", 850); // Excluded future date.
        var other = new Security("Other security", "EUR");
        client.addSecurity(other);
        account.addTransaction(new AccountTransaction(to.atTime(12, 0), "EUR", 999, other, AccountTransaction.Type.DIVIDENDS));

        var result = SecurityActivityService.calculate(client, security,
                        new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR"), from, to);
        assertEquals(1, result.dividends().size());
        assertEquals(3, result.transactions().size());
        assertEquals(3, result.transactions().stream().map(SecurityActivityService.Entry::uuid).distinct().count());
        var dividend = result.dividends().getFirst();
        assertEquals(1000, dividend.gross());
        assertEquals(850, dividend.net());
        assertEquals(100, dividend.taxes());
        assertEquals(50, dividend.fees());
        assertEquals(850, dividend.reportingNet().longValue());
        assertEquals(2, result.transactions().stream().filter(item -> item.shares() != null).count());
        assertTrue(result.transactions().getLast().date().startsWith(from.plusDays(1).toString()));
    }

    @Test
    public void retainsOriginalPaymentsAndUsesOnlyAvailableTransactionDateExchangeRates()
    {
        account.setCurrencyCode("USD");
        payment(from.plusDays(1), "USD", 100);
        payment(to, "USD", 100);
        var converter = new CurrencyConverter()
        {
            @Override
            public String getTermCurrency()
            {
                return "EUR";
            }

            @Override
            public ExchangeRate getRate(LocalDate date, String currency)
            {
                return new ExchangeRate(date, BigDecimal.valueOf(2));
            }

            @Override
            public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currency)
            {
                return date.equals(to) ? Optional.of(getRate(date, currency)) : Optional.empty();
            }
            @Override
            public CurrencyConverter with(String currency)
            {
                return this;
            }
        };
        var result = SecurityActivityService.calculate(client, security, converter, from, to);
        assertEquals(2, result.dividends().size());
        assertEquals("USD", result.dividends().getFirst().currency());
        assertEquals(100, result.dividends().getFirst().net());
        assertEquals(200, result.dividends().getFirst().reportingNet().longValue());
        assertNull(result.dividends().getLast().reportingNet());
        assertNull(result.transactions().getLast().reportingAmount());
    }

    @Test
    public void emptyPeriodReturnsEmptyLists()
    {
        var result = SecurityActivityService.calculate(client, security,
                        new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR"), from, to);
        assertTrue(result.dividends().isEmpty());
        assertTrue(result.transactions().isEmpty());
    }

    private AccountTransaction payment(LocalDate date, String currency, long net)
    {
        var transaction = new AccountTransaction(date.atTime(9, 0), currency, net, security, AccountTransaction.Type.DIVIDENDS);
        account.addTransaction(transaction);
        return transaction;
    }

    private void trade(PortfolioTransaction.Type type, LocalDate date)
    {
        var entry = new BuySellEntry(portfolio, account);
        entry.setType(type);
        entry.setDate(date.atTime(12, 0));
        entry.setSecurity(security);
        entry.setShares(Values.Share.factorize(10));
        entry.setMonetaryAmount(Money.of("EUR", 100_000));
        entry.insert();
    }
}
