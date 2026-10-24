package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.CostMethod;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class NonCashDistributionsTest
{
    @Test
    public void balancedSpinOffPreservesCashAndPerformanceWithoutInventingPaidIncome()
    {
        var day = LocalDate.of(2024, 4, 15);
        var client = new Client(); client.setBaseCurrency("EUR");
        var cash = new Account("Cash"); cash.setCurrencyCode("EUR"); client.addAccount(cash);
        var portfolio = new Portfolio("Investments"); portfolio.setReferenceAccount(cash); client.addPortfolio(portfolio);
        var parent = new Security("Example parent", "EUR"); client.addSecurity(parent);
        var child = new Security("Example child", "EUR"); client.addSecurity(child);
        parent.addPrice(new SecurityPrice(day.minusDays(2), Values.Quote.factorize(100)));
        parent.addPrice(new SecurityPrice(day, Values.Quote.factorize(70)));
        child.addPrice(new SecurityPrice(day, Values.Quote.factorize(100)));
        cash.addTransaction(new AccountTransaction(day.minusDays(2).atStartOfDay(), "EUR", 100_000, null, AccountTransaction.Type.DEPOSIT));
        trade(portfolio, cash, parent, day.minusDays(2), PortfolioTransaction.Type.BUY, 10, 100_000);
        var acquisition = trade(portfolio, cash, child, day, PortfolioTransaction.Type.BUY, 3, 30_000);
        acquisition.setDate(day.atTime(12, 34));
        var marker = NonCashDistributions.PREFIX + acquisition.getPortfolioTransaction().getUUID();
        acquisition.getPortfolioTransaction().setNote(marker + " acquisition");
        var distribution = new AccountTransaction(day.atTime(12, 34), "EUR", 30_000, parent, AccountTransaction.Type.DIVIDENDS);
        distribution.setNote(marker + " distribution (non-cash)"); cash.addTransaction(distribution);
        trade(portfolio, cash, child, day, PortfolioTransaction.Type.SELL, 1, 10_000).setDate(day.atTime(12, 34));
        assertTrue(NonCashDistributions.isSpinOff(client, cash, distribution));
        var clock = Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var source = new PortfolioSource.LoadedPortfolio(client, "Synthetic", "synthetic", clock.instant(), true);
        var service = new DashboardService(source, clock, List.of());
        var report = service.dashboard(day.minusDays(1), day, null, parent.getUUID(), true);
        assertEquals(100_000, report.totalValue());
        assertEquals(0, report.period().gain());
        assertEquals(0, report.period().netFlows());
        assertEquals(0, report.period().rate(), 0.0000001);
        assertTrue(report.dividends().isEmpty());
        assertEquals(0, report.instrument().dividends());
        assertTrue(report.instrument().activity().dividends().isEmpty());
        assertTrue(report.instrument().activity().transactions().stream().anyMatch(t -> t.type().equals("Spin-off distribution (non-cash)")));
        for (var row : report.rankings().securities()) {
            assertEquals(0, row.rate(), 0.0000001);
            assertEquals(0, row.gain().longValue());
        }
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR");
        var childRecord = LazySecurityPerformanceSnapshot.create(client, converter, Interval.of(day.minusDays(1), day))
                        .getRecord(child).orElseThrow();
        assertEquals(0, childRecord.getRealizedCapitalGains(CostMethod.FIFO).getCapitalGains().getAmount());
        for (var weights : List.of(java.util.Map.<Object, Integer>of(),
                        java.util.Map.<Object, Integer>of(portfolio, Classification.ONE_HUNDRED_PERCENT / 2,
                                        cash, Classification.ONE_HUNDRED_PERCENT / 2)))
        {
            var filtered = new PortfolioClientFilter(List.of(portfolio), List.of(cash), weights).filter(client);
            var view = new DashboardService(new PortfolioSource.LoadedPortfolio(filtered, "Filtered", "filtered", clock.instant(), true), clock, List.of());
            var filteredReport = view.dashboard(day.minusDays(1), day, null, parent.getUUID(), true);
            assertTrue(filteredReport.dividends().isEmpty());
            assertEquals(0, filteredReport.instrument().dividends());
            assertTrue(filteredReport.instrument().activity().dividends().isEmpty());
        }
        distribution.setAmount(29_999);
        assertFalse(NonCashDistributions.isSpinOff(client, cash, distribution));
        assertEquals(1, service.dashboard(day.minusDays(1), day).dividends().size());
    }

    private BuySellEntry trade(Portfolio portfolio, Account cash, Security security, LocalDate date,
                    PortfolioTransaction.Type type, int shares, long amount)
    {
        var entry = new BuySellEntry(portfolio, cash); entry.setType(type); entry.setSecurity(security);
        entry.setDate(date.atStartOfDay()); entry.setShares(Values.Share.factorize(shares));
        entry.setMonetaryAmount(Money.of("EUR", amount)); entry.insert(); return entry;
    }
}
