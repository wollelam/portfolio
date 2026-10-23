package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class DashboardServiceTest
{
    private final LocalDate end = LocalDate.of(2025, 6, 12);

    @Test
    public void depositsChangeValueButNotGainOrReturn()
    {
        var client = new Client();
        client.setBaseCurrency("EUR");
        var account = new Account("Cash");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.minusDays(10).atStartOfDay(), "EUR", 100_000,
                        null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.atTime(12, 0), "EUR", 50_000,
                        null, AccountTransaction.Type.DEPOSIT));
        var service = service(client);
        var report = service.dashboard(end.minusDays(1), end);
        assertEquals(150_000, report.totalValue());
        assertEquals(50_000, report.day().valueChange());
        assertEquals(50_000, report.day().netFlows());
        assertEquals(0, report.day().gain());
        assertEquals(0, report.day().rate(), 0.0000001);
        assertEquals(2, report.series().size());
    }

    @Test
    public void openingDateIsAValuationBoundaryAndDividendsKeepGrossAndNetSeparate()
    {
        var client = new Client();
        client.setBaseCurrency("EUR");
        var account = new Account("Cash");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.minusWeeks(1).atTime(12, 0), "EUR", 100_000,
                        null, AccountTransaction.Type.DEPOSIT));
        var dividend = new AccountTransaction(end.atTime(12, 0), "EUR", 850, null, AccountTransaction.Type.DIVIDENDS);
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 100)));
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 50)));
        account.addTransaction(dividend);
        var report = service(client).dashboard(end.minusWeeks(1), end);
        assertEquals(0, report.period().netFlows());
        assertEquals(850, report.period().gain());
        assertEquals(1000, report.dividends().getFirst().gross());
        assertEquals(850, report.dividends().getFirst().net());
        assertEquals(100, report.dividends().getFirst().taxes());
        assertEquals(50, report.dividends().getFirst().fees());
        assertEquals(0.0085, report.period().rate(), 0.0000001);
    }

    @Test
    public void dailyChartMatchesTheDesktopCalculationEngine()
    {
        var loaded = DemoPortfolio.create(end);
        var client = loaded.client();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR");
        var index = PerformanceIndex.forClient(client, converter, Interval.of(end.minusYears(1), end),
                        new java.util.ArrayList<>());
        var report = new DashboardService(loaded, end, List.of()).dashboard(end.minusYears(1), end);
        assertEquals(index.getDates().length, report.series().size());
        for (int ii = 0; ii < index.getDates().length; ii++)
        {
            assertEquals(index.getDates()[ii].toString(), report.series().get(ii).date());
            assertEquals(index.getTotals()[ii], report.series().get(ii).value());
            assertEquals(index.getAccumulatedPercentage()[ii], report.series().get(ii).rate(), 0.0000001);
        }
        assertFalse(report.holdings().isEmpty());
        assertEquals(4, report.dividends().size());
    }

    @Test
    public void calculationWarningsArePreservedAndBadRangesRejected()
    {
        var client = new Client();
        client.setBaseCurrency("EUR");
        var account = new Account("Cash");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.atStartOfDay(), "EUR", 100,
                        null, AccountTransaction.Type.INTEREST));
        var service = service(client);
        assertTrue(service.dashboard(end.minusWeeks(1), end).warnings().stream()
                        .anyMatch(w -> w.startsWith("Calculation warning:")));
        assertThrows(IllegalArgumentException.class, () -> service.dashboard(end, end));
        assertThrows(IllegalArgumentException.class, () -> service.dashboard(end, end.plusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> service.dashboard(end.minusYears(11), end));
    }

    @Test
    public void defaultDateUsesThePreviousCompletedTradingDay()
    {
        var monday = LocalDate.of(2025, 6, 16);
        var service = new DashboardService(DemoPortfolio.create(monday), monday, List.of());
        assertEquals(LocalDate.of(2025, 6, 13), service.defaultClosingDate());
    }

    private DashboardService service(Client client)
    {
        return new DashboardService(new PortfolioSource.LoadedPortfolio(client, "Test", "revision", Instant.EPOCH, false),
                        end, List.of());
    }
}
