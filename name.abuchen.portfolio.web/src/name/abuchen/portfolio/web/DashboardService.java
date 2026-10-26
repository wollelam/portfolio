package name.abuchen.portfolio.web;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit.Type;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyAccount;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;
import name.abuchen.portfolio.util.TradeCalendarManager;

/** All monetary API values are integer minor units; returns are decimal ratios. */
@SuppressWarnings("nls")
final class DashboardService
{
    record Period(String openingDate, String closingDate, long valueChange, long netFlows, long gain, Double rate, Double closingValueReturnFactor)
    {
    }

    record Point(String date, long value, Double rate, long investedCapital)
    {
    }

    record Holding(String name, String uuid, long value, Double weight, String quoteDate, String type, String icon, String id, long shares, String quoteCurrency, String symbol, Double valuationFxRate)
    {
    }

    record InstrumentPoint(String date, long price)
    {
    }

    record Instrument(String uuid, String name, String currency, double quoteScale, long shares, Long value, String quoteDate,
                    Double twror, Double irr, Long gain, long dividends, List<InstrumentPoint> prices, String message,
                    SecurityActivityService.Activity activity)
    {
    }

    record PriceCoverage(String valuationDate, int securities, int missing, int olderThanSevenDays,
                    String oldestQuoteDate, String latestQuoteDate)
    {
    }

    record Dividend(String date, String security, String account, String originalCurrency, long originalNet,
                    long gross, long taxes, long fees, long net)
    {
    }

    record Dashboard(String label, String revision, String loadedAt, boolean demo, String currency, String asOf,
                    long totalValue, Period day, Period week, Period period, List<Point> series, List<Holding> holdings,
                    List<Dividend> dividends, List<String> warnings, List<BenchmarkService.Option> benchmarks,
                    BenchmarkService.Result benchmark, PriceCoverage priceCoverage, String fxVersion, Instrument instrument,
                    SecurityRankingService.Rankings rankings, Waterfall waterfall, Drawdown drawdown, String inceptionDate, String today, Map<String, String> icons, PortfolioScope.Selection scope)
    {
    }

    record WaterfallEntry(String kind, String type, String label, long amount, String uuid)
    {
    }

    record Waterfall(List<WaterfallEntry> entries, String message)
    {
    }

    record DrawdownPoint(String date, Double rate)
    {
    }

    record Drawdown(Double maximum, String peakDate, String troughDate, Double current,
                    long longestWithoutNewHigh, List<DrawdownPoint> series)
    {
    }

    private final PortfolioSource.LoadedPortfolio portfolio;
    private final CurrencyConverterImpl converter;
    private final Clock clock;
    private final LocalDate inceptionDate;
    private final PortfolioIcons icons;
    private final PortfolioScope scope;
    private final List<String> exclusions;
    private final Map<String, DashboardService> filtered = new LinkedHashMap<>();
    private final List<String> startupWarnings;
    private final FxRates.View rates;

    DashboardService(PortfolioSource.LoadedPortfolio portfolio, Clock clock, List<String> startupWarnings)
    {
        this(portfolio, clock, startupWarnings, null);
    }

    DashboardService(PortfolioSource.LoadedPortfolio portfolio, Clock clock, List<String> startupWarnings,
                    FxRates.View rates)
    {
        this(portfolio, clock, startupWarnings, rates, new PortfolioScope(portfolio.client()), List.of(), null);
    }

    private DashboardService(PortfolioSource.LoadedPortfolio portfolio, Clock clock, List<String> startupWarnings,
                    FxRates.View rates, PortfolioScope scope, List<String> exclusions, CurrencyConverterImpl inheritedConverter)
    {
        this.portfolio = portfolio;
        this.scope = scope;
        this.exclusions = exclusions;
        this.icons = new PortfolioIcons(scope.client);
        this.clock = clock;
        var accountDates = scope.client.getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                        .map(t -> t.getDateTime().toLocalDate());
        var portfolioDates = scope.client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                        .map(t -> t.getDateTime().toLocalDate());
        this.inceptionDate = Stream.concat(accountDates, portfolioDates).filter(date -> !date.isAfter(LocalDate.now(clock)))
                        .min(Comparator.naturalOrder()).orElse(LocalDate.now(clock)).minusDays(1);
        this.startupWarnings = List.copyOf(startupWarnings);
        this.rates = rates;
        this.converter = inheritedConverter != null ? inheritedConverter : new CurrencyConverterImpl(rates == null ? new ExchangeRateProviderFactory(portfolio.client())
                        : rates.factory(portfolio.client()),
                        portfolio.client().getBaseCurrency());
    }

    synchronized DashboardService excluding(String value)
    {
        var selected = scope.parse(value);
        if (selected.isEmpty())
            return this;
        var key = String.join(",", selected);
        var existing = filtered.get(key);
        if (existing != null)
            return existing;
        var loaded = new PortfolioSource.LoadedPortfolio(scope.filter(selected), portfolio.label(), portfolio.revision(),
                        portfolio.loadedAt(), portfolio.demo());
        var result = new DashboardService(loaded, clock, startupWarnings, rates, scope, selected, converter);
        if (filtered.size() >= 12)
            filtered.remove(filtered.keySet().iterator().next());
        filtered.put(key, result);
        return result;
    }

    record AllocationFrame(String date, long totalValue, List<Holding> holdings)
    {
    }

    record AllocationFrames(String revision, String fxVersion, PortfolioScope.Selection scope, List<AllocationFrame> frames)
    {
    }

    synchronized AllocationFrames allocationFrames(LocalDate from, LocalDate to)
    {
        if (from.isBefore(inceptionDate) || to.isAfter(defaultClosingDate()) || to.isBefore(from)
                        || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 92)
            throw new IllegalArgumentException("Choose at most 93 recorded allocation dates");
        var frames = new ArrayList<AllocationFrame>();
        for (var date = from; !date.isAfter(to); date = date.plusDays(1))
        {
            var snapshot = ClientSnapshot.create(portfolio.client(), converter, date);
            frames.add(new AllocationFrame(date.toString(), snapshot.getMonetaryAssets().getAmount(),
                            holdings(snapshot, date, new LinkedHashSet<>())));
        }
        return new AllocationFrames(portfolio.revision(), fxVersion(), scope.selection(exclusions), List.copyOf(frames));
    }

    byte[] icon(String uuid)
    {
        return icons.image(uuid);
    }

    String fxVersion()
    {
        return rates == null ? null : rates.version();
    }

    DashboardService withRates(FxRates.View view) throws IOException
    {
        // Each factory registers listeners on its client; independent contexts must not retain old factories.
        var independent = new PortfolioSource.LoadedPortfolio(ClientFactory.duplicate(portfolio.client()), portfolio.label(),
                        portfolio.revision(), portfolio.loadedAt(), portfolio.demo());
        return new DashboardService(independent, clock, startupWarnings, view);
    }

    LocalDate defaultClosingDate()
    {
        return LocalDate.now(clock);
    }

    private LocalDate previousTradingDay(LocalDate date)
    {
        var calendar = TradeCalendarManager.getDefaultInstance();
        var previous = date.minusDays(1);
        while (calendar.isHoliday(previous))
            previous = previous.minusDays(1);
        return previous;
    }

    Dashboard dashboard(LocalDate openingDate, LocalDate closingDate)
    {
        return dashboard(openingDate, closingDate, null);
    }

    synchronized Dashboard dashboard(LocalDate openingDate, LocalDate closingDate, String benchmark)
    {
        return dashboard(openingDate, closingDate, benchmark, null);
    }

    synchronized Dashboard dashboard(LocalDate openingDate, LocalDate closingDate, String benchmark, String instrumentId)
    {
        return dashboard(openingDate, closingDate, benchmark, instrumentId, false);
    }

    synchronized Dashboard dashboard(LocalDate openingDate, LocalDate closingDate, String benchmark, String instrumentId,
                    boolean includeRankings)
    {
        return dashboard(openingDate, closingDate, benchmark, instrumentId, includeRankings, null);
    }

    synchronized Dashboard dashboard(LocalDate openingDate, LocalDate closingDate, String benchmark, String instrumentId,
                    boolean includeRankings, String waterfallMode)
    {
        if (waterfallMode != null && !waterfallMode.equals("calculation") && !waterfallMode.equals("contributions"))
            throw new IllegalArgumentException("Invalid waterfall mode");
        var earliest = closingDate.minusDays(3660);
        if (inceptionDate.isBefore(earliest))
            earliest = inceptionDate;
        if (!openingDate.isBefore(closingDate) || closingDate.isAfter(LocalDate.now(clock)) || openingDate.isBefore(earliest))
            throw new IllegalArgumentException("Choose an opening date before the closing date, no future dates, and a range within 10 years or the portfolio's recorded history.");

        var warnings = new LinkedHashSet<>(startupWarnings);
        var index = index(openingDate, closingDate, warnings);
        var day = period(index(previousTradingDay(closingDate), closingDate, warnings));
        var week = period(index(closingDate.minusWeeks(1), closingDate, warnings));
        var snapshot = ClientSnapshot.create(portfolio.client(), converter, closingDate);
        long total = snapshot.getMonetaryAssets().getAmount();
        var holdings = holdings(snapshot, closingDate, warnings);

        var series = new ArrayList<Point>();
        var investedCapital = index.calculateInvestedCapital();
        for (int ii = 0; ii < index.getDates().length; ii++)
            series.add(new Point(index.getDates()[ii].toString(), index.getTotals()[ii],
                            finite(index.getAccumulatedPercentage()[ii]), investedCapital[ii]));

        var dividends = new ArrayList<Dividend>();
        for (var account : portfolio.client().getAccounts())
        {
            for (var transaction : account.getTransactions())
            {
                var date = transaction.getDateTime().toLocalDate();
                if (transaction.getType() != AccountTransaction.Type.DIVIDENDS
                                || NonCashDistributions.isSpinOff(portfolio.client(), account, transaction)
                                || !date.isAfter(openingDate) || date.isAfter(closingDate))
                    continue;
                var gross = transaction.getGrossValue();
                var taxes = transaction.getUnitSum(Type.TAX);
                var fees = transaction.getUnitSum(Type.FEE);
                dividends.add(new Dividend(date.toString(), transaction.getSecurity() == null ? "Unassigned"
                                : transaction.getSecurity().getName(), account.getName(), transaction.getCurrencyCode(),
                                transaction.getAmount(), converter.convert(date, gross).getAmount(),
                                converter.convert(date, taxes).getAmount(), converter.convert(date, fees).getAmount(),
                                converter.convert(date, transaction.getMonetaryAmount()).getAmount()));
            }
        }
        dividends.sort(Comparator.comparing(Dividend::date).reversed().thenComparing(Dividend::security));

        // The core permits fallback FX rates. Surface gaps across the actual calculation dates,
        // including currencies in historical transactions and accounts that are now empty.
        var currencies = new LinkedHashSet<String>();
        portfolio.client().getAccounts().forEach(a -> {
            currencies.add(a.getCurrencyCode());
            a.getTransactions().forEach(t -> currencies.add(t.getCurrencyCode()));
        });
        portfolio.client().getSecurities().forEach(s -> currencies.add(s.getCurrencyCode()));
        var firstDate = openingDate.isBefore(closingDate.minusWeeks(1)) ? openingDate : closingDate.minusWeeks(1);
        if (previousTradingDay(closingDate).isBefore(firstDate))
            firstDate = previousTradingDay(closingDate);
        for (var currency : currencies)
        {
            if (currency == null || currency.equals(converter.getTermCurrency()))
                continue;
            for (var date = firstDate; !date.isAfter(closingDate); date = date.plusDays(1))
            {
                if (converter.getRateIfAvailable(date, currency).isEmpty())
                {
                    warnings.add("Missing exchange rate: " + currency + " → " + converter.getTermCurrency()
                                    + " on " + date + ". Values may use fallback rates.");
                    break;
                }
            }
        }

        var securityHoldings = holdings.stream().filter(h -> h.type().equals("security")).toList();
        var quoteDates = securityHoldings.stream().map(Holding::quoteDate).filter(java.util.Objects::nonNull).sorted().toList();
        var coverage = new PriceCoverage(closingDate.toString(), securityHoldings.size(),
                        securityHoldings.size() - quoteDates.size(),
                        (int) quoteDates.stream().filter(date -> LocalDate.parse(date).isBefore(closingDate.minusDays(7))).count(),
                        quoteDates.isEmpty() ? null : quoteDates.getFirst(), quoteDates.isEmpty() ? null : quoteDates.getLast());
        var rankings = includeRankings
                        ? SecurityRankingService.calculate(portfolio.client(), converter, openingDate, closingDate) : null;
        return new Dashboard(portfolio.label(), portfolio.revision(), portfolio.loadedAt().toString(), portfolio.demo(),
                        converter.getTermCurrency(), closingDate.toString(), total, day, week, period(index), series,
                        holdings, List.copyOf(dividends), List.copyOf(warnings), BenchmarkService.options(scope.client),
                        BenchmarkService.calculate(scope.client, converter, openingDate, closingDate, benchmark),
                        coverage, fxVersion(), instrumentId == null ? null : instrument(instrumentId, openingDate, closingDate), rankings,
                        waterfallMode == null ? null : waterfall(openingDate, closingDate, waterfallMode), drawdown(index), inceptionDate.toString(), defaultClosingDate().toString(), icons.urls(), scope.selection(exclusions));
    }

    private List<Holding> holdings(ClientSnapshot snapshot, LocalDate closingDate, Set<String> warnings)
    {
        long total = snapshot.getMonetaryAssets().getAmount();
        return snapshot.getAssetPositions().map(position -> {
            String quoteDate = null;
            if (position.getInvestmentVehicle() instanceof Security security)
            {
                var prices = security.getPricesIncludingLatest().stream()
                                .filter(p -> !p.getDate().isAfter(closingDate))
                                .max(Comparator.comparing(p -> p.getDate()));
                if (prices.isEmpty())
                    warnings.add("Missing closing quote: " + security.getName());
                else
                {
                    quoteDate = prices.get().getDate().toString();
                    if (prices.get().getDate().isBefore(closingDate.minusDays(7)))
                        warnings.add("Quote older than 7 calendar days: " + security.getName() + " (" + quoteDate + ")");
                }
            }
            var vehicle = position.getInvestmentVehicle();
            var vehicleId = vehicle instanceof Account account ? ReadOnlyAccount.unwrap(account).getUUID() : vehicle.getUUID();
            return new Holding(position.getDescription(), position.getInvestmentVehicle() instanceof Security security ? security.getUUID() : null, position.getValuation().getAmount(),
                            total == 0 ? null : position.getValuation().getAmount() / (double) total, quoteDate,
                            position.getInvestmentVehicle() instanceof Security ? "security" : "cash", icons.url(vehicleId),
                            vehicleId, position.getPosition().getShares(),
                            position.getInvestmentVehicle().getCurrencyCode(),
                            position.getInvestmentVehicle() instanceof Security security ? security.getTickerSymbol() : null,
                            vehicle.getCurrencyCode() == null ? null : converter.getRateIfAvailable(closingDate, vehicle.getCurrencyCode())
                                            .map(rate -> rate.getValue().doubleValue()).orElse(null));
        }).sorted(Comparator.comparingLong(Holding::value).reversed()).toList();
    }

    private Drawdown drawdown(PerformanceIndex index)
    {
        var drawdown = index.getDrawdown();
        var values = drawdown.getMaxDrawdownSerie();
        var points = new ArrayList<DrawdownPoint>();
        for (int i = 0; i < index.getDates().length; i++)
            points.add(new DrawdownPoint(index.getDates()[i].toString(), finite(values[i])));
        var interval = drawdown.getIntervalOfMaxDrawdown();
        return new Drawdown(finite(drawdown.getMaxDrawdown()), interval.getStart().toString(),
                        interval.getEnd().toString(), points.getLast().rate(),
                        drawdown.getMaxDrawdownDuration().getDays(), List.copyOf(points));
    }

    private Waterfall waterfall(LocalDate from, LocalDate to, String mode)
    {
        try
        {
            var snapshot = new ClientPerformanceSnapshot(portfolio.client(), converter, Interval.of(from, to), true);
            var breakdown = mode.equals("calculation") ? PerformanceBreakdown.createCalculation(snapshot)
                            : PerformanceBreakdown.createContributions(snapshot).limitContributions(20);
            if (!breakdown.isReconciled())
                return new Waterfall(List.of(), "The breakdown could not be reconciled for this period.");
            return new Waterfall(breakdown.getEntries().stream().map(entry -> new WaterfallEntry(
                            entry.getKind().name(), entry.getType().name(), entry.getLabel(), entry.getAmount().getAmount(),
                            entry.getSecurity() == null ? null : entry.getSecurity().getUUID())).toList(), null);
        }
        catch (RuntimeException e)
        {
            return new Waterfall(List.of(), "The breakdown is unavailable for this period. Check market-data warnings.");
        }
    }

    private Instrument instrument(String uuid, LocalDate openingDate, LocalDate closingDate)
    {
        var security = scope.client.getSecurities().stream().filter(s -> uuid.equals(s.getUUID())).findFirst().orElse(null);
        if (security == null)
            return new Instrument(uuid, null, null, Values.Quote.divider(), 0, null, null, null, null, null, 0, List.of(),
                            "This instrument is no longer available in the loaded portfolio.", SecurityActivityService.empty());

        var atEnd = ClientSnapshot.create(portfolio.client(), converter, closingDate);
        var position = atEnd.getAssetPositions().filter(p -> security.equals(p.getInvestmentVehicle())).findFirst().orElse(null);
        var points = security.getPricesIncludingLatest().stream().filter(p -> !p.getDate().isAfter(closingDate))
                        .filter(p -> !p.getDate().isBefore(openingDate)).sorted(Comparator.comparing(p -> p.getDate()))
                        .map(p -> new InstrumentPoint(p.getDate().toString(), p.getValue())).toList();
        var atStart = ClientSnapshot.create(portfolio.client(), converter, openingDate);
        var record = LazySecurityPerformanceSnapshot.create(portfolio.client(), converter, Interval.of(openingDate, closingDate),
                        atStart, atEnd).getRecord(security).orElse(null);
        var closingQuoteDate = security.getPricesIncludingLatest().stream().filter(p -> !p.getDate().isAfter(closingDate))
                        .map(p -> p.getDate()).max(Comparator.naturalOrder()).orElse(null);
        boolean hasClosingQuote = SecurityRankingService.usableQuote(security, closingDate);
        boolean validPeriod = SecurityRankingService.pricesAvailable(security, openingDate, closingDate,
                        atStart.getAssetPositions().anyMatch(p -> security.equals(p.getSecurity())), position != null);
        long nonCashDividends = portfolio.client().getAccounts().stream().flatMap(account -> account.getTransactions().stream()
                        .filter(t -> security.equals(t.getSecurity()) && t.getDateTime().toLocalDate().isAfter(openingDate)
                                        && !t.getDateTime().toLocalDate().isAfter(closingDate)
                                        && NonCashDistributions.isSpinOff(portfolio.client(), account, t)))
                        .mapToLong(t -> converter.convert(t.getDateTime().toLocalDate(), t.getGrossValue()).getAmount()).sum();
        return new Instrument(security.getUUID(), security.getName(), security.getCurrencyCode(), Values.Quote.divider(),
                        record == null ? 0 : record.getSharesHeld(),
                        position == null || !hasClosingQuote ? null : position.getValuation().getAmount(),
                        hasClosingQuote ? closingQuoteDate.toString() : null,
                        record == null || !validPeriod ? null : finite(record.getTrueTimeWeightedRateOfReturn()),
                        record == null || !validPeriod ? null : finite(record.getIrr()),
                        record == null || !validPeriod ? null : record.getDelta().getAmount(),
                        record == null ? 0 : record.getSumOfDividends().getAmount() - nonCashDividends, points,
                        !validPeriod ? (hasClosingQuote ? "The opening quote is missing or invalid; period performance is unavailable."
                                        : "No stored quote on or before the selected closing date is usable; valuation and period performance are unavailable.") :
                                        points.isEmpty() ? "No stored quotes in the selected period." : null,
                        SecurityActivityService.calculate(portfolio.client(), security, converter, openingDate, closingDate));
    }

    private PerformanceIndex index(LocalDate from, LocalDate to, Set<String> warnings)
    {
        var failures = new ArrayList<Exception>();
        var index = PerformanceIndex.forClient(portfolio.client(), converter, Interval.of(from, to), failures);
        failures.forEach(e -> warnings.add("Calculation warning: " + e.getMessage()));
        return index;
    }

    private Period period(PerformanceIndex index)
    {
        var totals = index.getTotals();
        long valueChange = totals[totals.length - 1] - totals[0];
        long flows = 0;
        var transfers = index.getTransferals();
        // Opening-date transactions are already included in the opening valuation.
        for (int ii = 1; ii < transfers.length; ii++)
            flows += transfers[ii];
        int last = totals.length - 1;
        // Native TTWROR puts closing-day inflows in the denominator and outflows in the numerator.
        // Only the terminal valuation changes intraday; retain all prior daily factors and cash flows.
        double denominator = last > 0 ? (double) totals[last - 1] + index.getInboundTransferals()[last] : 0;
        Double closingValueReturnFactor = denominator == 0 ? null
                        : finite((1 + index.getAccumulatedPercentage()[last - 1]) / denominator);
        return new Period(index.getActualInterval().getStart().toString(), index.getActualInterval().getEnd().toString(),
                        valueChange, flows, valueChange - flows, finite(index.getFinalAccumulatedPercentage()), closingValueReturnFactor);
    }

    private Double finite(double value)
    {
        return Double.isFinite(value) ? value : null;
    }
}
