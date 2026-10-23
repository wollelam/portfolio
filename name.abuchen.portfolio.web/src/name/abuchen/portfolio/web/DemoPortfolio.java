package name.abuchen.portfolio.web;

import java.time.Instant;
import java.time.LocalDate;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

/** Synthetic data only; no network requests or files are needed for a demo. */
@SuppressWarnings("nls")
final class DemoPortfolio
{
    private DemoPortfolio()
    {
    }

    static PortfolioSource.LoadedPortfolio create(LocalDate today)
    {
        var client = new Client();
        client.setBaseCurrency("EUR");
        var account = new Account("Investment cash");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        var portfolio = new Portfolio("Demo investments");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);
        var start = today.minusDays(400);
        account.addTransaction(new AccountTransaction(start.atTime(9, 0), "EUR", Values.Amount.factorize(100000),
                        null, AccountTransaction.Type.DEPOSIT));

        var security = new Security("Global equities · demo", "EUR");
        client.addSecurity(security);
        for (int ii = 0; ii <= 400; ii++)
        {
            double price = 100 * (1 + ii * 0.00045 + Math.sin(ii / 18d) * 0.025);
            security.addPrice(new SecurityPrice(start.plusDays(ii), Values.Quote.factorize(price)));
        }
        var purchase = new BuySellEntry(portfolio, account);
        purchase.setType(PortfolioTransaction.Type.BUY);
        purchase.setDate(start.atTime(12, 0));
        purchase.setSecurity(security);
        purchase.setShares(Values.Share.factorize(700));
        purchase.setMonetaryAmount(Money.of("EUR", Values.Amount.factorize(70000)));
        purchase.insert();
        for (int ii = 1; ii <= 4; ii++)
        {
            var dividend = new AccountTransaction(start.plusDays(ii * 90L).atTime(12, 0), "EUR",
                            Values.Amount.factorize(420 + ii * 20), security, AccountTransaction.Type.DIVIDENDS);
            account.addTransaction(dividend);
        }
        return new PortfolioSource.LoadedPortfolio(client, "Demo portfolio", "demo-" + today, Instant.now(), true);
    }
}
