package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class DashboardServiceTest
{
    private final LocalDate end = LocalDate.of(2025, 6, 12);
    private final Clock clock = Clock.fixed(end.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Test
    public void drawdownMatchesNativeTimeWeightedSeriesAndDates()
    {
        var loaded = DemoPortfolio.create(end);
        var service = new DashboardService(loaded, clock, List.of());
        var from = end.minusYears(1);
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded.client()), "EUR");
        var index = PerformanceIndex.forClient(loaded.client(), converter, Interval.of(from, end), new java.util.ArrayList<>());
        var expected = index.getDrawdown();
        var actual = service.dashboard(from, end).drawdown();
        assertEquals(expected.getMaxDrawdown(), actual.maximum(), 0.00000001);
        assertEquals(expected.getIntervalOfMaxDrawdown().getStart().toString(), actual.peakDate());
        assertEquals(expected.getIntervalOfMaxDrawdown().getEnd().toString(), actual.troughDate());
        assertEquals(expected.getMaxDrawdownDuration().getDays(), actual.longestWithoutNewHigh());
        assertEquals(index.getDates().length, actual.series().size());
        for (int i = 0; i < actual.series().size(); i++)
        {
            assertEquals(index.getDates()[i].toString(), actual.series().get(i).date());
            assertEquals(expected.getMaxDrawdownSerie()[i], actual.series().get(i).rate(), 0.00000001);
        }
        assertEquals(actual.series().getLast().rate(), actual.current());
    }

    @Test
    public void waterfallReusesNativeBreakdownAndIsOptIn()
    {
        var loaded = DemoPortfolio.create(end);
        var service = new DashboardService(loaded, clock, List.of());
        var from = end.minusYears(1);
        assertEquals(null, service.dashboard(from, end).waterfall());
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded.client()), "EUR");
        var nativeSnapshot = new ClientPerformanceSnapshot(loaded.client(), converter, Interval.of(from, end), true);
        for (var mode : List.of("calculation", "contributions"))
        {
            var nativeBreakdown = mode.equals("calculation") ? PerformanceBreakdown.createCalculation(nativeSnapshot)
                            : PerformanceBreakdown.createContributions(nativeSnapshot).limitContributions(20);
            var actual = service.dashboard(from, end, null, null, false, mode).waterfall();
            assertEquals(null, actual.message());
            assertEquals(nativeBreakdown.getEntries().size(), actual.entries().size());
            long running = 0;
            for (int i = 0; i < actual.entries().size(); i++)
            {
                var entry = actual.entries().get(i);
                var expected = nativeBreakdown.getEntries().get(i);
                assertEquals(expected.getAmount().getAmount(), entry.amount());
                assertEquals(expected.getType().name(), entry.type());
                assertEquals(expected.getSecurity() == null ? null : expected.getSecurity().getUUID(), entry.uuid());
                if (entry.kind().equals("START")) running = entry.amount();
                else if (entry.kind().equals("CHANGE")) running += entry.amount();
                else if (entry.kind().equals("TOTAL")) assertEquals(running, entry.amount());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> service.dashboard(from, end, null, null, false, "unknown"));
    }

    @Test
    public void waterfallSeparatesExternalFlowsFromAfterTaxPerformance()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.minusDays(10).atStartOfDay(), "EUR", 100_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.atTime(9, 0), "EUR", 50_000, null, AccountTransaction.Type.DEPOSIT));
        var dividend = new AccountTransaction(end.atTime(12, 0), "EUR", 850, null, AccountTransaction.Type.DIVIDENDS);
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 100)));
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 50)));
        account.addTransaction(dividend);
        var service = service(client);
        var bridge = service.dashboard(end.minusDays(1), end, null, null, false, "calculation").waterfall();
        assertEquals(null, bridge.message());
        assertEquals(100_000, bridge.entries().getFirst().amount());
        assertEquals(150_850, bridge.entries().getLast().amount());
        assertEquals(50_000, bridge.entries().stream().filter(e -> e.type().equals("TRANSFERS")).findFirst().orElseThrow().amount());
        assertEquals(-100, bridge.entries().stream().filter(e -> e.type().equals("TAXES")).findFirst().orElseThrow().amount());
        assertEquals(-50, bridge.entries().stream().filter(e -> e.type().equals("FEES")).findFirst().orElseThrow().amount());
        var contributions = service.dashboard(end.minusDays(1), end, null, null, false, "contributions").waterfall();
        assertEquals(850, contributions.entries().getLast().amount());
        assertFalse(contributions.entries().stream().anyMatch(e -> e.type().equals("TRANSFERS")));
    }

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
        assertEquals(0, report.drawdown().maximum(), 0.0000001);
        assertEquals(0, report.drawdown().current(), 0.0000001);
        assertEquals(2, report.series().size());
        assertEquals(0, report.priceCoverage().securities());
        assertEquals(0, report.priceCoverage().missing());
    }

    @Test
    public void quoteCoverageCountsOnlyHeldSecuritiesAtTheSelectedValuationDate()
    {
        var loaded = DemoPortfolio.create(end);
        var held = loaded.client().getSecurities().getFirst();
        held.getPrices().stream().filter(price -> price.getDate().isAfter(end.minusDays(8))).toList()
                        .forEach(held::removePrice);
        var service = new DashboardService(loaded, clock, List.of());
        var coverage = service.dashboard(end.minusMonths(1), end).priceCoverage();
        assertEquals(1, coverage.securities());
        assertEquals(0, coverage.missing());
        assertEquals(1, coverage.olderThanSevenDays());
        assertEquals(end.minusDays(8).toString(), coverage.latestQuoteDate());
        var historical = service.dashboard(end.minusMonths(1), end.minusDays(9)).priceCoverage();
        assertEquals(0, historical.olderThanSevenDays());
        assertEquals(end.minusDays(9).toString(), historical.latestQuoteDate());
        held.removeAllPrices();
        assertEquals(1, service.dashboard(end.minusMonths(1), end).priceCoverage().missing());
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
        var report = new DashboardService(loaded, clock, List.of()).dashboard(end.minusYears(1), end);
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
    public void instrumentMetricsMatchNativeSecurityPerformanceAndExcludeFutureQuotes() throws Exception
    {
        var loaded = DemoPortfolio.create(end);
        var security = loaded.client().getSecurities().getFirst();
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(end.plusDays(1),
                        name.abuchen.portfolio.money.Values.Quote.factorize(999)));
        var from = end.minusMonths(6);
        var service = new DashboardService(loaded, clock, List.of());
        var detail = service.dashboard(from, end, null, security.getUUID()).instrument();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded.client()), "EUR");
        var record = LazySecurityPerformanceSnapshot.create(loaded.client(), converter, Interval.of(from, end),
                        ClientSnapshot.create(loaded.client(), converter, from),
                        ClientSnapshot.create(loaded.client(), converter, end)).getRecord(security).orElseThrow();
        assertEquals(record.getTrueTimeWeightedRateOfReturn(), detail.twror(), 0.0000001);
        assertEquals(record.getIrr(), detail.irr(), 0.0000001);
        assertEquals(record.getDelta().getAmount(), detail.gain().longValue());
        assertEquals(record.getSumOfDividends().getAmount(), detail.dividends());
        assertEquals(record.getSharesHeld().longValue(), detail.shares());
        assertTrue(detail.prices().stream().noneMatch(p -> p.date().equals(end.plusDays(1).toString())));
        assertEquals(1, service.dashboard(from, end).holdings().stream().filter(h -> h.uuid() != null).count());
    }

    @Test
    public void rankingsMatchNativeSnapshotAndAreOptIn()
    {
        var loaded = DemoPortfolio.create(end);
        var security = loaded.client().getSecurities().getFirst();
        var benchmarkOnly = new name.abuchen.portfolio.model.Security("Benchmark only", "EUR");
        benchmarkOnly.addPrice(new name.abuchen.portfolio.model.SecurityPrice(end.minusDays(2),
                        name.abuchen.portfolio.money.Values.Quote.factorize(100)));
        loaded.client().addSecurity(benchmarkOnly);
        var from = end.minusMonths(6);
        var service = new DashboardService(loaded, clock, List.of());
        assertEquals(null, service.dashboard(from, end).rankings());
        var ranking = service.dashboard(from, end, null, null, true).rankings();
        var row = ranking.securities().stream().filter(r -> r.uuid().equals(security.getUUID())).findFirst().orElseThrow();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded.client()), "EUR");
        var record = LazySecurityPerformanceSnapshot.create(loaded.client(), converter, Interval.of(from, end),
                        ClientSnapshot.create(loaded.client(), converter, from), ClientSnapshot.create(loaded.client(), converter, end))
                        .getRecord(security).orElseThrow();
        assertEquals(record.getTrueTimeWeightedRateOfReturn(), row.rate(), 0.0000001);
        assertEquals(record.getDelta().getAmount(), row.gain().longValue());
        assertFalse(ranking.securities().stream().anyMatch(r -> r.uuid().equals(benchmarkOnly.getUUID())));
    }

    @Test
    public void rankingsMaskHeldPositionsWhenEitherBoundaryHasNoUsableQuote()
    {
        var from = end.minusMonths(3);
        var client = new Client();
        client.setBaseCurrency("EUR");
        var portfolio = new name.abuchen.portfolio.model.Portfolio("Test portfolio");
        var account = new Account("Cash");
        account.setCurrencyCode("EUR");
        portfolio.setReferenceAccount(account);
        var security = new name.abuchen.portfolio.model.Security("Test security", "EUR");
        client.addPortfolio(portfolio);
        client.addAccount(account);
        client.addSecurity(security);
        var buy = new BuySellEntry(portfolio, account);
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setDate(from.minusDays(2).atTime(12, 0));
        buy.setSecurity(security);
        buy.setShares(name.abuchen.portfolio.money.Values.Share.factorize(100));
        buy.setMonetaryAmount(Money.of("EUR", name.abuchen.portfolio.money.Values.Amount.factorize(10000)));
        buy.insert();
        var loaded = new PortfolioSource.LoadedPortfolio(client, "test", "test", java.time.Instant.now(), true);
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(from.plusDays(1),
                        name.abuchen.portfolio.money.Values.Quote.factorize(100)));
        var openingMissing = new DashboardService(loaded, clock, List.of()).dashboard(from, end, null, null, true).rankings();
        var row = openingMissing.securities().stream().filter(r -> r.uuid().equals(security.getUUID())).findFirst().orElseThrow();
        assertEquals(null, row.rate());
        assertEquals(null, row.gain());
        assertEquals(0, openingMissing.neutral());
        var detail = new DashboardService(loaded, clock, List.of()).dashboard(from, end, null, security.getUUID()).instrument();
        assertEquals(null, detail.twror());
        assertEquals(null, detail.gain());
        assertTrue(detail.message().contains("opening quote"));

        security.removeAllPrices();
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(from.minusDays(1),
                        name.abuchen.portfolio.money.Values.Quote.factorize(100)));
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(end, 0));
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(end.plusDays(1),
                        name.abuchen.portfolio.money.Values.Quote.factorize(500)));
        var closingZero = new DashboardService(loaded, clock, List.of()).dashboard(from, end, null, null, true).rankings();
        row = closingZero.securities().stream().filter(r -> r.uuid().equals(security.getUUID())).findFirst().orElseThrow();
        assertEquals(null, row.rate());
        assertEquals(null, row.gain());
        assertEquals(0, closingZero.neutral());
        detail = new DashboardService(loaded, clock, List.of()).dashboard(from, end, null, security.getUUID()).instrument();
        assertEquals(null, detail.value());
        assertEquals(null, detail.twror());

        security.removeAllPrices();
        portfolio.getTransactions().getFirst().setDateTime(from.plusDays(1).atTime(12, 0));
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(end.plusDays(1),
                        name.abuchen.portfolio.money.Values.Quote.factorize(500)));
        var closingFutureOnly = new DashboardService(loaded, clock, List.of()).dashboard(from, end, null, null, true).rankings();
        row = closingFutureOnly.securities().stream().filter(r -> r.uuid().equals(security.getUUID())).findFirst().orElseThrow();
        assertEquals(null, row.rate());
        assertEquals(null, row.gain());
        assertTrue(closingFutureOnly.unavailable() > 0);
        assertEquals(0, closingFutureOnly.neutral());
    }

    @Test
    public void rankingsIncludeSoldOnlyPeriodActivityWithNativeMetrics()
    {
        var loaded = DemoPortfolio.create(end);
        var client = loaded.client();
        var security = client.getSecurities().getFirst();
        var portfolio = client.getPortfolios().getFirst();
        var account = client.getAccounts().getFirst();
        var sale = new BuySellEntry(portfolio, account);
        sale.setType(PortfolioTransaction.Type.SELL);
        sale.setDate(end.minusDays(1).atTime(12, 0));
        sale.setSecurity(security);
        sale.setShares(name.abuchen.portfolio.money.Values.Share.factorize(700));
        sale.setMonetaryAmount(Money.of("EUR", name.abuchen.portfolio.money.Values.Amount.factorize(100000)));
        sale.insert();
        var from = end.minusMonths(3);
        var rankings = new DashboardService(loaded, clock, List.of()).dashboard(from, end, null, null, true).rankings();
        var row = rankings.securities().stream().filter(r -> r.uuid().equals(security.getUUID())).findFirst().orElseThrow();
        assertEquals(0, ClientSnapshot.create(client, new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR"), end)
                        .getAssetPositions().filter(p -> security.equals(p.getSecurity())).count());
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR");
        var record = LazySecurityPerformanceSnapshot.create(client, converter, Interval.of(from, end),
                        ClientSnapshot.create(client, converter, from), ClientSnapshot.create(client, converter, end))
                        .getRecord(security).orElseThrow();
        assertEquals(record.getTrueTimeWeightedRateOfReturn(), row.rate(), 0.0000001);
        assertEquals(record.getDelta().getAmount(), row.gain().longValue());
    }

    @Test
    public void removedInstrumentDoesNotBreakDashboard()
    {
        var service = new DashboardService(DemoPortfolio.create(end), clock, List.of());
        var detail = service.dashboard(end.minusMonths(1), end, null, "removed-uuid").instrument();
        assertTrue(detail.message().contains("no longer available"));
        assertEquals("removed-uuid", detail.uuid());
    }

    @Test
    public void instrumentWithNoStoredQuotesRetainsIdentityAndHistoricalQuantity()
    {
        var loaded = DemoPortfolio.create(end);
        var security = loaded.client().getSecurities().getFirst();
        security.removeAllPrices();
        var detail = new DashboardService(loaded, clock, List.of())
                        .dashboard(end.minusMonths(3), end, null, security.getUUID()).instrument();
        assertEquals(security.getUUID(), detail.uuid());
        assertTrue(detail.prices().isEmpty());
        assertTrue(detail.message().contains("No stored quote on or before"));
        assertEquals(name.abuchen.portfolio.money.Values.Share.factorize(700), detail.shares());
        assertTrue(detail.value() == null);
        assertEquals(null, detail.gain());
        assertEquals(null, detail.twror());
        assertEquals(null, detail.irr());
        assertTrue(detail.dividends() > 0);
    }

    @Test
    public void soldInstrumentStillShowsPeriodDividendsWithoutAClosingPosition()
    {
        var loaded = DemoPortfolio.create(end);
        var client = loaded.client();
        var security = client.getSecurities().getFirst();
        var portfolio = client.getPortfolios().getFirst();
        var account = client.getAccounts().getFirst();
        var sale = new BuySellEntry(portfolio, account);
        sale.setType(PortfolioTransaction.Type.SELL);
        sale.setDate(end.minusDays(1).atTime(12, 0));
        sale.setSecurity(security);
        sale.setShares(name.abuchen.portfolio.money.Values.Share.factorize(700));
        sale.setMonetaryAmount(Money.of("EUR", name.abuchen.portfolio.money.Values.Amount.factorize(100000)));
        sale.insert();
        var detail = new DashboardService(loaded, clock, List.of())
                        .dashboard(end.minusMonths(3), end, null, security.getUUID()).instrument();
        assertEquals(0, detail.shares());
        assertTrue(detail.value() == null);
        assertTrue(detail.dividends() > 0);
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
    public void defaultDateUsesTodayIncludingWeekends()
    {
        var monday = LocalDate.of(2025, 6, 16);
        for (var date : List.of(monday.minusDays(1), monday))
        {
            var fixedClock = Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
            var service = new DashboardService(DemoPortfolio.create(date), fixedClock, List.of());
            assertEquals(date, service.defaultClosingDate());
        }
    }

    private DashboardService service(Client client)
    {
        return new DashboardService(new PortfolioSource.LoadedPortfolio(client, "Test", "revision", Instant.EPOCH, false),
                        clock, List.of());
    }
}
