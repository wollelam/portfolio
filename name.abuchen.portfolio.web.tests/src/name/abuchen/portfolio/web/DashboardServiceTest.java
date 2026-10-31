package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityEvent;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProvider;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class DashboardServiceTest
{
    @Test
    public void tickerFallbackDoesNotMergeConflictingIsins()
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var primaryPortfolio = DemoPortfolio.create(today);
        var secondaryPortfolio = DemoPortfolio.create(today);
        primaryPortfolio.client().getSecurities().getFirst().setTickerSymbol("SHARED");
        secondaryPortfolio.client().getSecurities().getFirst().setTickerSymbol("SHARED");
        primaryPortfolio.client().getSecurities().getFirst().setIsin("ISIN-OLE");
        secondaryPortfolio.client().getSecurities().getFirst().setIsin("ISIN-YING");

        var combined = DashboardService.combined(new DashboardService(primaryPortfolio, clock, List.of()),
                        new DashboardService(secondaryPortfolio, clock, List.of()));

        assertEquals(4, combined.dashboard(today.minusYears(1), today).holdings().size());
    }

    @Test
    public void combinedMatchedHoldingsKeepEachSourcesSavedQuote()
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var primaryPortfolio = DemoPortfolio.create(today);
        var secondaryPortfolio = DemoPortfolio.create(today);
        for (int ii = 0; ii < primaryPortfolio.client().getSecurities().size(); ii++)
        {
            primaryPortfolio.client().getSecurities().get(ii).setIsin("QUOTE-" + ii);
            secondaryPortfolio.client().getSecurities().get(ii).setIsin("QUOTE-" + ii);
        }
        primaryPortfolio.client().getSecurities().getFirst().addPrice(new name.abuchen.portfolio.model.SecurityPrice(today,
                        name.abuchen.portfolio.money.Values.Quote.factorize(100)));
        secondaryPortfolio.client().getSecurities().getFirst().addPrice(new name.abuchen.portfolio.model.SecurityPrice(today,
                        name.abuchen.portfolio.money.Values.Quote.factorize(200)));
        var primary = new DashboardService(primaryPortfolio, clock, List.of()).dashboard(today.minusYears(1), today);
        var secondary = new DashboardService(secondaryPortfolio, clock, List.of()).dashboard(today.minusYears(1), today);
        var combinedService = DashboardService.combined(new DashboardService(primaryPortfolio, clock, List.of()),
                        new DashboardService(secondaryPortfolio, clock, List.of()));
        var combined = combinedService.dashboard(today.minusYears(1), today);

        assertEquals(primary.totalValue() + secondary.totalValue(), combined.totalValue());
        var sourceHoldingValue = java.util.stream.Stream.concat(primary.holdings().stream(), secondary.holdings().stream())
                        .filter(h -> "security".equals(h.type())).mapToLong(DashboardService.Holding::value).sum();
        assertEquals(sourceHoldingValue, combined.holdings().stream().filter(h -> "security".equals(h.type()))
                        .mapToLong(DashboardService.Holding::value).sum());
        assertEquals(1, combined.holdings().stream().filter(h -> "security".equals(h.type())).count());
        var securityUuid = primaryPortfolio.client().getSecurities().getFirst().getUUID();
        var detail = combinedService.dashboard(today.minusYears(1), today, null, securityUuid).instrument();
        assertTrue(detail.message().contains("shown separately by source"));
        assertEquals(sourceHoldingValue, detail.value().longValue());
        assertEquals(primaryDashboardValue(primaryPortfolio, today.minusYears(1), today)
                        + primaryDashboardValue(secondaryPortfolio, today.minusYears(1), today), detail.value().longValue());
        assertEquals(2, detail.sourcePrices().size());
        assertTrue(detail.sourcePrices().containsKey("Primary"));
        assertTrue(detail.sourcePrices().containsKey("Secondary"));
        assertTrue(detail.sourcePrices().values().stream().allMatch(prices -> !prices.isEmpty()));
        assertFalse(detail.totalReturn().isEmpty());
        var primarySource = new DashboardService(primaryPortfolio, clock, List.of());
        var secondarySource = new DashboardService(secondaryPortfolio, clock, List.of());
        var primaryDetail = primarySource.dashboard(today.minusYears(1), today, null,
                        primaryPortfolio.client().getSecurities().getFirst().getUUID()).instrument();
        var secondaryDetail = secondarySource.dashboard(today.minusYears(1), today, null,
                        secondaryPortfolio.client().getSecurities().getFirst().getUUID()).instrument();
        var primaryConverter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(primaryPortfolio.client()), "EUR");
        var secondaryConverter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(secondaryPortfolio.client()), "EUR");
        var primaryIndex = PerformanceIndex.forInvestment(primaryPortfolio.client(), primaryConverter,
                        primaryPortfolio.client().getSecurities().getFirst(), Interval.of(today.minusYears(1), today),
                        new ArrayList<>());
        var secondaryIndex = PerformanceIndex.forInvestment(secondaryPortfolio.client(), secondaryConverter,
                        secondaryPortfolio.client().getSecurities().getFirst(), Interval.of(today.minusYears(1), today),
                        new ArrayList<>());
        assertEquals(combinedRate(primaryIndex, secondaryIndex), detail.twror(), 0.0000001);
        assertEquals(primaryDetail.gain() + secondaryDetail.gain(), detail.gain().longValue());
        assertEquals(primaryDetail.dividends() + secondaryDetail.dividends(), detail.dividends());
        assertFalse(detail.activity().dividends().isEmpty());
        assertFalse(detail.activity().transactions().isEmpty());
        assertTrue(detail.events().stream().anyMatch(event -> event.kind().equals("dividend")));

        var waterfall = combinedService.dashboard(today.minusYears(1), today, null, null, false,
                        "contributions").waterfall();
        assertNull(waterfall.message());
        assertEquals("START", waterfall.entries().getFirst().kind());
        assertEquals("TOTAL", waterfall.entries().getLast().kind());
        assertTrue(waterfall.entries().stream().filter(entry -> entry.type().equals("SECURITY")).count() <= 20);
        assertTrue(waterfall.entries().stream().anyMatch(entry -> entry.type().equals("SECURITY")
                        && entry.uuid().equals(securityUuid)));
        assertEquals(waterfall.entries().getLast().amount(), waterfall.entries().stream()
                        .filter(entry -> entry.kind().equals("CHANGE")).mapToLong(DashboardService.WaterfallEntry::amount).sum());
    }

    private long primaryDashboardValue(PortfolioSource.LoadedPortfolio loaded, LocalDate from, LocalDate to)
    {
        var service = new DashboardService(loaded, Clock.fixed(to.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
                        List.of());
        return service.dashboard(from, to).holdings().stream().filter(h -> h.type().equals("security"))
                        .mapToLong(DashboardService.Holding::value).sum();
    }

    @Test
    public void combinedValuesAndHistoryUseEachSourcesFxContext()
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var primaryPortfolio = DemoPortfolio.create(today);
        var secondaryPortfolio = DemoPortfolio.create(today);
        primaryPortfolio.client().getSecurities().getFirst().setCurrencyCode("USD");
        secondaryPortfolio.client().getSecurities().getFirst().setCurrencyCode("USD");
        primaryPortfolio.client().getSecurities().getFirst().setIsin("FX-MATCH");
        secondaryPortfolio.client().getSecurities().getFirst().setIsin("FX-MATCH");
        var primaryRates = fxView("primary", "0.90");
        var secondaryRates = fxView("secondary", "1.10");
        var primary = new DashboardService(primaryPortfolio, clock, List.of(), primaryRates);
        var secondary = new DashboardService(secondaryPortfolio, clock, List.of(), secondaryRates);
        var from = today.minusYears(1);
        var primaryDashboard = primary.dashboard(from, today);
        var secondaryDashboard = secondary.dashboard(from, today);
        var combined = DashboardService.combined(primary, secondary).dashboard(from, today);

        assertEquals(primaryDashboard.totalValue() + secondaryDashboard.totalValue(), combined.totalValue());
        assertEquals(primaryDashboard.period().valueChange() + secondaryDashboard.period().valueChange(), combined.period().valueChange());
        assertEquals(primaryDashboard.period().gain() + secondaryDashboard.period().gain(), combined.period().gain());
        assertEquals(primaryDashboard.day().valueChange() + secondaryDashboard.day().valueChange(), combined.day().valueChange());
        assertEquals(primaryDashboard.week().valueChange() + secondaryDashboard.week().valueChange(), combined.week().valueChange());
        assertEquals(primaryDashboard.series().size(), combined.series().size());
        for (int ii = 0; ii < combined.series().size(); ii++)
            assertEquals(primaryDashboard.series().get(ii).value() + secondaryDashboard.series().get(ii).value(),
                            combined.series().get(ii).value());
        var expectedRate = combined.series().getLast().value() / (double) combined.series().getFirst().value() - 1;
        assertEquals(expectedRate, combined.period().rate(), 0.0000000001);
        var individualHoldingValue = java.util.stream.Stream.concat(primaryDashboard.holdings().stream(), secondaryDashboard.holdings().stream())
                        .filter(h -> "security".equals(h.type())).mapToLong(DashboardService.Holding::value).sum();
        assertEquals(individualHoldingValue, combined.holdings().stream().filter(h -> "security".equals(h.type()))
                        .mapToLong(DashboardService.Holding::value).sum());
        var individualCashValue = java.util.stream.Stream.concat(primaryDashboard.holdings().stream(), secondaryDashboard.holdings().stream())
                        .filter(h -> "cash".equals(h.type())).mapToLong(DashboardService.Holding::value).sum();
        assertEquals(individualCashValue, combined.holdings().stream().filter(h -> "cash".equals(h.type()))
                        .mapToLong(DashboardService.Holding::value).sum());

        var detailed = DashboardService.combined(primary, secondary).dashboard(from, today, null, null, true, null, true);
        var primaryDetailed = primary.dashboard(from, today, null, null, true);
        var secondaryDetailed = secondary.dashboard(from, today, null, null, true);
        var primaryUuid = primaryPortfolio.client().getSecurities().getFirst().getUUID();
        var secondaryUuid = secondaryPortfolio.client().getSecurities().getFirst().getUUID();
        var primaryRank = primaryDetailed.rankings().securities().stream().filter(e -> e.uuid().equals(primaryUuid)).findFirst().orElseThrow();
        var secondaryRank = secondaryDetailed.rankings().securities().stream().filter(e -> e.uuid().equals(secondaryUuid)).findFirst().orElseThrow();
        var combinedRank = detailed.rankings().securities().stream().filter(e -> e.uuid().equals(primaryUuid)).findFirst().orElseThrow();
        assertEquals(primaryRank.gain() + secondaryRank.gain(), combinedRank.gain().longValue());
        var primaryConverter = new CurrencyConverterImpl(primaryRates.factory(primaryPortfolio.client()), "EUR");
        var secondaryConverter = new CurrencyConverterImpl(secondaryRates.factory(secondaryPortfolio.client()), "EUR");
        var primaryIndex = PerformanceIndex.forInvestment(primaryPortfolio.client(), primaryConverter,
                        primaryPortfolio.client().getSecurities().getFirst(), Interval.of(from, today), new ArrayList<>());
        var secondaryIndex = PerformanceIndex.forInvestment(secondaryPortfolio.client(), secondaryConverter,
                        secondaryPortfolio.client().getSecurities().getFirst(), Interval.of(from, today), new ArrayList<>());
        assertEquals(combinedRate(primaryIndex, secondaryIndex), combinedRank.rate(), 0.0000000001);
        var dailyFrom = LocalDate.parse(detailed.day().openingDate());
        var primaryDaily = SecurityRankingService.calculate(primaryPortfolio.client(), primaryConverter, dailyFrom, today).securities().stream()
                        .filter(e -> e.uuid().equals(primaryUuid)).findFirst().orElseThrow();
        var secondaryDaily = SecurityRankingService.calculate(secondaryPortfolio.client(), secondaryConverter, dailyFrom, today).securities().stream()
                        .filter(e -> e.uuid().equals(secondaryUuid)).findFirst().orElseThrow();
        var combinedDaily = detailed.dailyRankings().securities().stream().filter(e -> e.uuid().equals(primaryUuid)).findFirst().orElseThrow();
        assertEquals(primaryDaily.gain() + secondaryDaily.gain(), combinedDaily.gain().longValue());
        var primaryDailyIndex = PerformanceIndex.forInvestment(primaryPortfolio.client(), primaryConverter,
                        primaryPortfolio.client().getSecurities().getFirst(), Interval.of(dailyFrom, today), new ArrayList<>());
        var secondaryDailyIndex = PerformanceIndex.forInvestment(secondaryPortfolio.client(), secondaryConverter,
                        secondaryPortfolio.client().getSecurities().getFirst(), Interval.of(dailyFrom, today), new ArrayList<>());
        assertEquals(combinedRate(primaryDailyIndex, secondaryDailyIndex), combinedDaily.rate(), 0.0000000001);
        var primaryWaterfall = primary.dashboard(from, today, null, null, false, "calculation").waterfall();
        var secondaryWaterfall = secondary.dashboard(from, today, null, null, false, "calculation").waterfall();
        var waterfall = DashboardService.combined(primary, secondary).dashboard(from, today, null, null, false,
                        "calculation", false).waterfall();
        assertNull(waterfall.message());
        assertTrue(waterfall.entries().size() > 0);
        assertEquals(primaryWaterfall.entries().stream().filter(e -> "START".equals(e.kind())).mapToLong(DashboardService.WaterfallEntry::amount).sum()
                        + secondaryWaterfall.entries().stream().filter(e -> "START".equals(e.kind())).mapToLong(DashboardService.WaterfallEntry::amount).sum(),
                        waterfall.entries().stream().filter(e -> "START".equals(e.kind())).mapToLong(DashboardService.WaterfallEntry::amount).sum());
        assertEquals(primaryWaterfall.entries().stream().filter(e -> "TOTAL".equals(e.kind())).mapToLong(DashboardService.WaterfallEntry::amount).sum()
                        + secondaryWaterfall.entries().stream().filter(e -> "TOTAL".equals(e.kind())).mapToLong(DashboardService.WaterfallEntry::amount).sum(),
                        waterfall.entries().stream().filter(e -> "TOTAL".equals(e.kind())).mapToLong(DashboardService.WaterfallEntry::amount).sum());
    }

    private double combinedRate(PerformanceIndex primary, PerformanceIndex secondary)
    {
        double accumulated = 0;
        for (int ii = 1; ii < primary.getDates().length; ii++)
        {
            long opening = primary.getTotals()[ii - 1] + secondary.getTotals()[ii - 1]
                            + primary.getInboundTransferals()[ii] + secondary.getInboundTransferals()[ii];
            long closing = primary.getTotals()[ii] + secondary.getTotals()[ii]
                            + primary.getOutboundTransferals()[ii] + secondary.getOutboundTransferals()[ii];
            double daily = opening == 0 ? 0 : closing / (double) opening - 1;
            accumulated = (1 + accumulated) * (1 + daily) - 1;
        }
        return accumulated;
    }

    private FxRates.View fxView(String version, String rate)
    {
        ExchangeRateProvider provider = new ExchangeRateProvider()
        {
            private final ExchangeRateTimeSeriesImpl series = createSeries();

            private ExchangeRateTimeSeriesImpl createSeries()
            {
                var result = new ExchangeRateTimeSeriesImpl(this, "USD", "EUR");
                result.addRate(new ExchangeRate(LocalDate.of(2020, 1, 1), new BigDecimal(rate)));
                return result;
            }

            @Override
            public String getName()
            {
                return version;
            }

            @Override
            public List<ExchangeRateTimeSeries> getAvailableTimeSeries(Client client)
            {
                return List.of(series);
            }
        };
        return new FxRates.View(version, provider.getAvailableTimeSeries(new Client()));
    }

    private final LocalDate end = LocalDate.of(2025, 6, 12);
    private final Clock clock = Clock.fixed(end.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Test
    public void investedCapitalTracksDepositsAndWithdrawalsWithoutCountingIncome()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.minusDays(5).atStartOfDay(), "EUR", 100_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.minusDays(1).atStartOfDay(), "EUR", 20_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.atTime(9, 0), "EUR", 5_000, null, AccountTransaction.Type.REMOVAL));
        account.addTransaction(new AccountTransaction(end.atTime(12, 0), "EUR", 850, null, AccountTransaction.Type.INTEREST));
        var report = service(client).dashboard(end.minusDays(5), end);
        assertEquals(100_000, report.series().getFirst().investedCapital());
        assertEquals(120_000, report.series().get(report.series().size() - 2).investedCapital());
        assertEquals(115_000, report.series().getLast().investedCapital());
        assertEquals(115_850, report.series().getLast().value());
        assertEquals(850, report.period().gain());
    }

    @Test
    public void liveClosingValueReturnFactorPreservesDepositsAndWithdrawals()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.minusDays(2).atStartOfDay(), "EUR", 100_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.atTime(9, 0), "EUR", 20_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.atTime(10, 0), "EUR", 5_000, null, AccountTransaction.Type.REMOVAL));
        account.addTransaction(new AccountTransaction(end.atTime(11, 0), "EUR", 850, null, AccountTransaction.Type.INTEREST));
        var before = service(client).dashboard(end.minusDays(2), end).day();
        assertEquals(850, before.gain());
        assertEquals(15_000, before.netFlows());
        assertEquals(1d / 120_000, before.closingValueReturnFactor(), 1e-15);
        account.addTransaction(new AccountTransaction(end.atTime(12, 0), "EUR", 1200, null, AccountTransaction.Type.INTEREST));
        var after = service(client).dashboard(end.minusDays(2), end).day();
        assertEquals(before.gain() + 1200, after.gain());
        assertEquals(before.rate() + 1200 * before.closingValueReturnFactor(), after.rate(), 1e-12);
    }

    @Test
    public void liveClosingValueReturnFactorHandlesZeroCapitalAndTotalLoss()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        assertEquals(null, service(client).dashboard(end.minusDays(1), end).day().closingValueReturnFactor());
        var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(end.minusDays(2).atStartOfDay(), "EUR", 100_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.atTime(9, 0), "EUR", 100_000, null, AccountTransaction.Type.FEES));
        var before = service(client).dashboard(end.minusDays(2), end).day();
        assertEquals(-1d, before.rate(), 1e-12);
        assertEquals(1d / 100_000, before.closingValueReturnFactor(), 1e-15);
        account.addTransaction(new AccountTransaction(end.atTime(12, 0), "EUR", 1000, null, AccountTransaction.Type.INTEREST));
        var after = service(client).dashboard(end.minusDays(2), end).day();
        assertEquals(before.rate() + 1000 * before.closingValueReturnFactor(), after.rate(), 1e-12);
    }

    @Test
    public void maximumRangeIncludesFirstActivityAndHistoryOlderThanTenYears()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
        var first = end.minusYears(20);
        account.addTransaction(new AccountTransaction(first.atStartOfDay(), "EUR", 100_000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(end.plusDays(1).atStartOfDay(), "EUR", 50_000, null, AccountTransaction.Type.DEPOSIT));
        var report = service(client).dashboard(first.minusDays(1), end);
        assertEquals(first.minusDays(1).toString(), report.inceptionDate());
        assertEquals(100_000, report.totalValue());
        assertEquals(100_000, report.period().netFlows());
        assertEquals(0, report.period().gain());
        assertThrows(IllegalArgumentException.class, () -> service(client).dashboard(first.minusDays(2), end));
        var empty = new Client(); empty.setBaseCurrency("EUR");
        assertEquals(end.minusDays(1).toString(), service(empty).dashboard(end.minusDays(1), end).inceptionDate());
    }

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
        assertEquals(100_000, report.series().getFirst().investedCapital());
        assertEquals(150_000, report.series().getLast().investedCapital());
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
        assertEquals(100_000, report.series().getLast().investedCapital());
        assertEquals(850, report.series().getLast().value() - report.series().getLast().investedCapital());
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
        var nativeCapital = index.calculateInvestedCapital();
        for (int i = 0; i < nativeCapital.length; i++) assertEquals(nativeCapital[i], report.series().get(i).investedCapital());
        assertEquals(report.period().gain(), report.series().getLast().value() - report.series().getLast().investedCapital());
        for (int ii = 0; ii < index.getDates().length; ii++)
        {
            assertEquals(index.getDates()[ii].toString(), report.series().get(ii).date());
            assertEquals(index.getTotals()[ii], report.series().get(ii).value());
            assertEquals(index.getAccumulatedPercentage()[ii], report.series().get(ii).rate(), 0.0000001);
        }
        assertFalse(report.holdings().isEmpty());
        for (var holding : report.holdings())
            assertEquals(converter.getRateIfAvailable(end, holding.quoteCurrency())
                            .map(rate -> rate.getValue().doubleValue()).orElse(null), holding.valuationFxRate());
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
        var investmentIndex = PerformanceIndex.forInvestment(loaded.client(), converter, security, Interval.of(from, end),
                        new java.util.ArrayList<>());
        assertEquals(investmentIndex.getDates().length, detail.totalReturn().size());
        for (int i = 0; i < detail.totalReturn().size(); i++)
        {
            assertEquals(investmentIndex.getDates()[i].toString(), detail.totalReturn().get(i).date());
            assertEquals(investmentIndex.getAccumulatedPercentage()[i], detail.totalReturn().get(i).value(), 0.0000001);
        }
        assertTrue(detail.prices().stream().noneMatch(p -> p.date().equals(end.plusDays(1).toString())));
        assertEquals(1, service.dashboard(from, end).holdings().stream().filter(h -> h.uuid() != null).count());
    }

    @Test
    public void instrumentEventsIncludeBookedDividendsTradesAndStoredCorporateNotes()
    {
        var from = end.minusDays(5);
        var client = new Client(); client.setBaseCurrency("EUR");
        var cash = new Account("Cash"); cash.setCurrencyCode("EUR"); client.addAccount(cash);
        var portfolio = new name.abuchen.portfolio.model.Portfolio("Investments");
        portfolio.setReferenceAccount(cash); client.addPortfolio(portfolio);
        var security = new Security("Example", "EUR"); client.addSecurity(security);
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(from.minusDays(2), name.abuchen.portfolio.money.Values.Quote.factorize(100)));
        security.addPrice(new name.abuchen.portfolio.model.SecurityPrice(end, name.abuchen.portfolio.money.Values.Quote.factorize(110)));
        var priorBuy = new BuySellEntry(portfolio, cash); priorBuy.setType(PortfolioTransaction.Type.BUY);
        priorBuy.setDate(from.minusDays(1).atTime(10, 0)); priorBuy.setSecurity(security);
        priorBuy.setShares(name.abuchen.portfolio.money.Values.Share.factorize(1));
        priorBuy.setMonetaryAmount(Money.of("EUR", name.abuchen.portfolio.money.Values.Amount.factorize(100))); priorBuy.insert();
        var trade = new BuySellEntry(portfolio, cash); trade.setType(PortfolioTransaction.Type.BUY); trade.setDate(from.atTime(10, 0));
        trade.setSecurity(security); trade.setShares(name.abuchen.portfolio.money.Values.Share.factorize(10));
        trade.setMonetaryAmount(Money.of("EUR", name.abuchen.portfolio.money.Values.Amount.factorize(1000))); trade.insert();
        var sale = new BuySellEntry(portfolio, cash); sale.setType(PortfolioTransaction.Type.SELL); sale.setDate(end.atTime(10, 0));
        sale.setSecurity(security); sale.setShares(name.abuchen.portfolio.money.Values.Share.factorize(2));
        sale.setMonetaryAmount(Money.of("EUR", name.abuchen.portfolio.money.Values.Amount.factorize(220))); sale.insert();
        var dividend = new AccountTransaction(end.minusDays(2).atTime(12, 0), "EUR", 800, security, AccountTransaction.Type.DIVIDENDS);
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 150)));
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 50))); cash.addTransaction(dividend);
        security.addEvent(new SecurityEvent(end.minusDays(1), SecurityEvent.Type.NOTE, "Stored event detail"));
        security.addEvent(new SecurityEvent(end.minusDays(3), SecurityEvent.Type.STOCK_SPLIT, "2:1"));
        security.addEvent(new SecurityEvent(end.plusDays(1), SecurityEvent.Type.NOTE, "Future note"));

        var detail = service(client).dashboard(from, end, null, security.getUUID()).instrument();
        assertEquals(1, detail.events().stream().filter(e -> e.kind().equals("buy")).count());
        assertTrue(detail.events().stream().anyMatch(e -> e.kind().equals("sell")));
        assertTrue(detail.events().stream().anyMatch(e -> e.kind().equals("dividend") && e.label().startsWith("Dividend payment")
                        && e.label().contains("gross EUR 10.00")
                        && e.label().contains("net EUR 8.00") && e.label().contains("tax EUR 1.50") && e.label().contains("fee EUR 0.50")));
        assertTrue(detail.events().stream().anyMatch(e -> e.kind().equals("corporate") && e.label().equals("Note: Stored event detail")));
        assertTrue(detail.events().stream().anyMatch(e -> e.kind().equals("corporate") && e.label().equals("Stock split · 2:1")));
        assertFalse(detail.events().stream().anyMatch(e -> e.label().contains("Future note")));
        assertTrue(detail.events().stream().allMatch(e -> !LocalDate.parse(e.date()).isBefore(from) && !LocalDate.parse(e.date()).isAfter(end)));
    }

    @Test
    public void dailyRankingsAreOptInAndUseTheDailyIntervalAndPortfolioScope()
    {
        var loaded = DemoPortfolio.create(end);
        var service = new DashboardService(loaded, clock, List.of());
        assertEquals(null, service.dashboard(end.minusMonths(6), end).dailyRankings());
        var report = service.dashboard(end.minusMonths(6), end, null, null, false, null, true);
        assertEquals(null, report.rankings());
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded.client()), "EUR");
        assertEquals(SecurityRankingService.calculate(loaded.client(), converter, LocalDate.parse(report.day().openingDate()), end),
                        report.dailyRankings());
        assertFalse(report.dailyRankings().securities().isEmpty());
        var exclusions = String.join(",", report.scope().options().stream().map(PortfolioScope.Option::id).toList());
        var empty = service.excluding(exclusions).dashboard(end.minusMonths(6), end, null, null, false, null, true);
        assertTrue(empty.dailyRankings().securities().isEmpty());
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
        var benchmark = service.dashboard(from, end, null, benchmarkOnly.getUUID()).instrument();
        assertEquals(null, benchmark.twror());
        assertTrue(benchmark.totalReturn().isEmpty());
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
        assertTrue(detail.totalReturn().isEmpty());
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
        assertTrue(detail.totalReturn().isEmpty());
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
