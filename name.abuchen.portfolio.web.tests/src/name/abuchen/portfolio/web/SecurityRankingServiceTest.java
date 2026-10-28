package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class SecurityRankingServiceTest
{
    private final LocalDate from = LocalDate.of(2025, 6, 10);
    private final LocalDate to = from.plusDays(2);
    private final Client client = new Client();
    private final Account account = new Account("Cash");
    private final Portfolio portfolio = new Portfolio("Investments");

    public SecurityRankingServiceTest()
    {
        client.setBaseCurrency("EUR");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);
    }

    @Test
    public void smallHighReturnAndLargeMoneyGainHaveDifferentLeaders()
    {
        var large = position("Large holding", 100, 110);
        var small = position("Small holding", 1, 130);
        var loss = position("Losing holding", 10, 80);
        position("Unchanged holding", 10, 100);
        account.addTransaction(new AccountTransaction(from.plusDays(1).atStartOfDay(), "EUR", 5_000_000,
                        null, AccountTransaction.Type.DEPOSIT));
        var results = calculate();
        assertEquals(4, results.securities().size());
        assertEquals(0, results.unavailable());
        assertEquals(1, results.neutral());
        assertResult(results, large, 0.10, 100_000);
        assertResult(results, small, 0.30, 3_000);
        assertResult(results, loss, -0.20, -20_000);
        var largeRow = results.securities().stream().filter(item -> item.uuid().equals(large.getUUID())).findFirst().orElseThrow();
        assertEquals(0.20, largeRow.rate() + 100_000 * largeRow.closingValueReturnFactor(), 0.00000001);
    }

    @Test
    public void currencyGainUsesOpeningValueAndNetIncomeAfterSecurityCosts()
    {
        var security = position("Income holding", 10, 110);
        var dividend = new AccountTransaction(from.plusDays(1).atTime(12, 0), "EUR", 800, security,
                        AccountTransaction.Type.DIVIDENDS);
        dividend.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 200)));
        account.addTransaction(dividend);
        account.addTransaction(new AccountTransaction(from.plusDays(1).atTime(13, 0), "EUR", 300, security,
                        AccountTransaction.Type.FEES));
        account.addTransaction(new AccountTransaction(to.atTime(13, 0), "EUR", 200, security,
                        AccountTransaction.Type.TAXES));
        // Opening-date income is outside this performance period.
        account.addTransaction(new AccountTransaction(from.atTime(12, 0), "EUR", 2_500, security,
                        AccountTransaction.Type.DIVIDENDS));
        var row = calculate().securities().getFirst();
        assertEquals(10_300, row.gain().longValue()); // 100 EUR price gain + 8 net income - 3 fees - 2 tax
    }

    private Security position(String name, long shares, long closingPrice)
    {
        var security = new Security(name, "EUR");
        client.addSecurity(security);
        security.addPrice(new SecurityPrice(from, Values.Quote.factorize(100)));
        security.addPrice(new SecurityPrice(to, Values.Quote.factorize(closingPrice)));
        var buy = new BuySellEntry(portfolio, account);
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setDate(from.minusDays(1).atTime(12, 0));
        buy.setSecurity(security);
        buy.setShares(Values.Share.factorize(shares));
        buy.setMonetaryAmount(Money.of("EUR", shares * 10_000));
        buy.insert();
        return security;
    }

    private SecurityRankingService.Rankings calculate()
    {
        return SecurityRankingService.calculate(client, new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR"), from, to);
    }

    private void assertResult(SecurityRankingService.Rankings results, Security security, double rate, long gain)
    {
        var row = results.securities().stream().filter(item -> item.uuid().equals(security.getUUID())).findFirst().orElseThrow();
        assertEquals(rate, row.rate(), 0.00000001);
        assertEquals(gain, row.gain().longValue());
    }
}
