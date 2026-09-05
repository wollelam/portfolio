package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.Interval;

public class TransactionReportTest
{
    @Test
    public void rendersLedgerFiltersAndDoesNotDoubleCountLinkedBuySellEntries()
    {
        Client client = new Client();
        Account account = new Account("Cash Account"); //$NON-NLS-1$
        Portfolio portfolio = new Portfolio("Brokerage"); //$NON-NLS-1$
        portfolio.setReferenceAccount(account);
        client.addAccount(account);
        client.addPortfolio(portfolio);

        Security security = new Security("Example Corp", "EUR"); //$NON-NLS-1$ //$NON-NLS-2$
        client.addSecurity(security);

        BuySellEntry linked = new BuySellEntry(portfolio, account);
        linked.setType(PortfolioTransaction.Type.BUY);
        linked.setDate(LocalDateTime.of(2024, 1, 2, 10, 0));
        linked.setSecurity(security);
        linked.setShares(Values.Share.factorize(2));
        linked.setMonetaryAmount(Money.of("EUR", Values.Amount.factorize(100))); //$NON-NLS-1$
        linked.setNote("linked purchase"); //$NON-NLS-1$
        linked.insert();

        AccountTransaction unlinked = new AccountTransaction(AccountTransaction.Type.BUY);
        unlinked.setDateTime(LocalDateTime.of(2024, 1, 3, 10, 0));
        unlinked.setCurrencyCode("EUR"); //$NON-NLS-1$
        unlinked.setAmount(Values.Amount.factorize(50));
        account.addTransaction(unlinked);

        PortfolioTransaction delivery = new PortfolioTransaction(PortfolioTransaction.Type.DELIVERY_INBOUND);
        delivery.setDateTime(LocalDateTime.of(2024, 1, 4, 10, 0));
        delivery.setCurrencyCode("EUR"); //$NON-NLS-1$
        delivery.setAmount(Values.Amount.factorize(20));
        delivery.setSecurity(security);
        delivery.setShares(Values.Share.factorize(1));
        delivery.setNote("with costs"); //$NON-NLS-1$
        delivery.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE,
                        Money.of("EUR", Values.Amount.factorize(2)))); //$NON-NLS-1$
        delivery.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX,
                        Money.of("EUR", Values.Amount.factorize(1)))); //$NON-NLS-1$
        portfolio.addTransaction(delivery);

        Interval interval = Interval.of(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 4));
        String example = "example"; //$NON-NLS-1$
        String brokerage = "broker"; //$NON-NLS-1$
        String deliveryInbound = "delivery_inbound"; //$NON-NLS-1$
        List<String> lines = TransactionReport.render(client, interval, example, brokerage, deliveryInbound);

        assertThat(lines.get(1), containsString("Owner type")); //$NON-NLS-1$
        assertThat(lines.get(2), containsString(
                        "Brokerage | Portfolio |  | 2024-01-04 | DELIVERY_INBOUND | Example Corp")); //$NON-NLS-1$
        assertThat(lines.get(2), containsString("EUR 2.00 | EUR 1.00 | with costs")); //$NON-NLS-1$
        assertThat(lines.get(3), is("Count: 1")); //$NON-NLS-1$

        lines = TransactionReport.render(client, interval, null, null, null);
        assertThat(lines.get(lines.size() - 1), is("Count: 3")); //$NON-NLS-1$
        assertThat(lines.stream().filter(line -> line.contains("linked purchase")).count(), is(1L)); //$NON-NLS-1$
        assertThat(lines.stream().anyMatch(
                        line -> line.contains("Cash Account | Account |  | 2024-01-03 | BUY")), is(true)); //$NON-NLS-1$

        String cashAccount = "cash account"; //$NON-NLS-1$
        String buy = "buy"; //$NON-NLS-1$
        lines = TransactionReport.render(client, interval, example, cashAccount, buy);
        assertThat(lines.get(2),
                        containsString("Brokerage | Portfolio | Cash Account | 2024-01-02 | BUY")); //$NON-NLS-1$
        assertThat(lines.get(3), is("Count: 1")); //$NON-NLS-1$

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> TransactionReport.render(client, interval, null, null, "bu")); //$NON-NLS-1$
        assertThat(error.getMessage(), is("Unknown transaction type: bu")); //$NON-NLS-1$
    }

    @Test
    public void explicitlyReportsAnEmptyResult()
    {
        List<String> lines = TransactionReport.render(new Client(),
                        Interval.of(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)), null, null, null);

        assertThat(lines.get(2), is("No transactions found.")); //$NON-NLS-1$
        assertThat(lines.get(3), is("Count: 0")); //$NON-NLS-1$
    }
}
