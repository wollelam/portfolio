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
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Transaction.Unit.Type;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Quote;
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

    record InstrumentPoint(String date, long price, Long reportingPrice)
    {
    }

    record ReturnPoint(String date, Double value)
    {
    }

    record ChartEvent(String date, String kind, String label)
    {
    }

    record Instrument(String uuid, String name, String currency, double quoteScale, long shares, Long value, String quoteDate,
                    Double twror, Double irr, Long gain, long dividends, List<InstrumentPoint> prices,
                    Map<String, List<InstrumentPoint>> sourcePrices, String message,
                    SecurityActivityService.Activity activity, List<ReturnPoint> totalReturn, List<ChartEvent> events)
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
                    SecurityRankingService.Rankings rankings, SecurityRankingService.Rankings dailyRankings, Waterfall waterfall, Drawdown drawdown, String inceptionDate, String today, Map<String, String> icons, PortfolioScope.Selection scope, Map<String, List<String>> quoteKeys)
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
    private final CurrencyConverter converter;
    private final Clock clock;
    private final LocalDate inceptionDate;
    private final PortfolioIcons icons;
    private final PortfolioScope scope;
    private final List<String> exclusions;
    private final Map<String, DashboardService> filtered = new LinkedHashMap<>();
    private final Set<String> validatedRateRanges = new java.util.HashSet<>();
    private final List<String> startupWarnings;
    private final FxRates.View rates;
    private Map<String, List<String>> quoteKeys;
    private Map<String, String> iconUrls;
    private Map<String, byte[]> iconImages;
    private Map<String, List<String>> combinedSecurityGroups = Map.of();
    private Map<String, String> canonicalSecurityIds = Map.of();
    private DashboardService combinedOle;
    private DashboardService combinedYing;

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
                    FxRates.View rates, PortfolioScope scope, List<String> exclusions, CurrencyConverter inheritedConverter)
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
        this.quoteKeys = portfolio.client().getSecurities().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Security::getUUID, security -> List.of(security.getUUID())));
        this.iconUrls = icons.urls();
        this.iconImages = Map.of();
        this.converter = inheritedConverter != null ? inheritedConverter : new CurrencyConverterImpl(rates == null ? new ExchangeRateProviderFactory(portfolio.client())
                        : rates.factory(portfolio.client()),
                        portfolio.client().getBaseCurrency());
    }

    static DashboardService combined(DashboardService ole, DashboardService ying)
    {
        try
        {
            var first = ClientFactory.duplicate(ole.portfolio.client());
            var second = ClientFactory.duplicate(ying.portfolio.client());
            var merged = new name.abuchen.portfolio.model.Client();
            merged.setBaseCurrency(ole.portfolio.client().getBaseCurrency());
            first.getTaxonomies().forEach(merged::addTaxonomy);
            second.getTaxonomies().forEach(merged::addTaxonomy);
            var sourceToCanonical = new java.util.IdentityHashMap<Security, Security>();
            var identities = new LinkedHashMap<String, Security>();
            for (var security : first.getSecurities())
            {
                merged.addSecurity(security);
                sourceToCanonical.put(security, security);
                identities.putIfAbsent(securityIdentity(security, first, second), security);
            }
            for (var security : second.getSecurities())
            {
                var key = securityIdentity(security, first, second);
                var canonical = key == null ? null : identities.get(key);
                if (merged.getSecurities().stream().anyMatch(s -> s.getUUID().equals(security.getUUID())))
                    throw new IllegalArgumentException("Conflicting security UUIDs in combined view");
                merged.addSecurity(security);
                sourceToCanonical.put(security, canonical == null ? security : canonical);
            }
            var uuids = new java.util.HashSet<String>();
            first.getAccounts().forEach(account -> { if (!uuids.add(account.getUUID())) throw new IllegalArgumentException("Conflicting account UUIDs"); merged.addAccount(account); });
            first.getPortfolios().forEach(portfolio -> { if (!uuids.add(portfolio.getUUID())) throw new IllegalArgumentException("Conflicting portfolio UUIDs"); merged.addPortfolio(portfolio); });
            for (var account : second.getAccounts())
            {
                if (!uuids.add(account.getUUID())) throw new IllegalArgumentException("Conflicting account UUIDs in combined view");
                merged.addAccount(account);
            }
            for (var portfolio : second.getPortfolios())
            {
                if (!uuids.add(portfolio.getUUID())) throw new IllegalArgumentException("Conflicting portfolio UUIDs in combined view");
                merged.addPortfolio(portfolio);
            }
            var loaded = new PortfolioSource.LoadedPortfolio(merged, "Combined (" + ole.portfolio.label() + " + " + ying.portfolio.label() + ")",
                            ole.portfolio.revision() + ":" + ying.portfolio.revision(),
                            ole.portfolio.loadedAt().isAfter(ying.portfolio.loadedAt()) ? ole.portfolio.loadedAt() : ying.portfolio.loadedAt(), false);
            var result = new DashboardService(loaded, ole.clock, java.util.stream.Stream.concat(ole.startupWarnings.stream(), ying.startupWarnings.stream()).distinct().toList(), ole.rates);
            var keys = new LinkedHashMap<String, List<String>>();
            var groups = new LinkedHashMap<String, List<String>>();
            var canonicalIds = new LinkedHashMap<String, String>();
            sourceToCanonical.forEach((source, canonical) -> canonicalIds.put(source.getUUID(), canonical.getUUID()));
            merged.getSecurities().forEach(s -> {
                var canonical = sourceToCanonical.get(s);
                if (canonical == s)
                {
                    keys.put(s.getUUID(), new ArrayList<>());
                    groups.put(s.getUUID(), new ArrayList<>());
                }
            });
            appendQuoteKeys(first.getSecurities(), sourceToCanonical, keys);
            appendQuoteKeys(second.getSecurities(), sourceToCanonical, keys);
            keys.replaceAll((uuid, values) -> List.copyOf(values));
            appendQuoteKeys(first.getSecurities(), sourceToCanonical, groups);
            appendQuoteKeys(second.getSecurities(), sourceToCanonical, groups);
            groups.replaceAll((uuid, values) -> List.copyOf(values));
            result.quoteKeys = Map.copyOf(keys);
            result.combinedSecurityGroups = Map.copyOf(groups);
            result.canonicalSecurityIds = Map.copyOf(canonicalIds);
            result.combinedOle = ole;
            result.combinedYing = ying;
            var imageUrls = new LinkedHashMap<>(ole.icons.urls());
            for (var entry : sourceToCanonical.entrySet())
            {
                var url = ole.icons.urls().get(entry.getKey().getUUID());
                if (url == null)
                    url = ying.icons.urls().get(entry.getKey().getUUID());
                if (url != null)
                    imageUrls.putIfAbsent(entry.getValue().getUUID(), url);
            }
            result.iconUrls = Map.copyOf(imageUrls);
            var images = new LinkedHashMap<String, byte[]>();
            for (var security : first.getSecurities())
                addIcon(images, ole, security.getUUID(), sourceToCanonical.get(security).getUUID());
            for (var security : second.getSecurities())
                addIcon(images, ole, security.getUUID(), sourceToCanonical.get(security).getUUID(), ying);
            first.getAccounts().forEach(account -> addIcon(images, ole, account.getUUID(), account.getUUID()));
            second.getAccounts().forEach(account -> addIcon(images, ole, account.getUUID(), account.getUUID(), ying));
            result.iconImages = Map.copyOf(images);
            return result;
        }
        catch (IOException failure)
        {
            throw new IllegalArgumentException("Could not build combined portfolio", failure);
        }
    }

    private static void appendQuoteKeys(List<Security> securities, Map<Security, Security> sourceToCanonical,
                    Map<String, List<String>> keys)
    {
        for (var security : securities)
        {
            var canonical = sourceToCanonical.get(security);
            var values = keys.get(canonical.getUUID());
            if (!values.contains(security.getUUID()))
                values.add(security.getUUID());
        }
    }

    private static void addIcon(Map<String, byte[]> images, DashboardService primary, String sourceUuid,
                    String displayedUuid)
    {
        addIcon(images, primary, sourceUuid, displayedUuid, null);
    }

    private static void addIcon(Map<String, byte[]> images, DashboardService primary, String sourceUuid,
                    String displayedUuid, DashboardService fallback)
    {
        var image = primary.icons.image(sourceUuid);
        if (image == null && fallback != null)
            image = fallback.icons.image(sourceUuid);
        if (image != null)
            images.putIfAbsent(displayedUuid, image);
    }

    private static String securityIdentity(Security security, name.abuchen.portfolio.model.Client first,
                    name.abuchen.portfolio.model.Client second)
    {
        var currency = security.getCurrencyCode();
        if (currency == null) return null;
        var isin = security.getIsin();
        if (isin != null && !isin.isBlank())
        {
            var key = "isin:" + isin.trim().toUpperCase(java.util.Locale.ROOT) + ":" + currency;
            long countFirst = first.getSecurities().stream().filter(s -> key.equals(rawIsinIdentity(s))).count();
            long countSecond = second.getSecurities().stream().filter(s -> key.equals(rawIsinIdentity(s))).count();
            if (countFirst == 1 && countSecond == 1)
                return key;
        }
        var ticker = security.getTickerSymbol();
        if (ticker == null || ticker.isBlank()) return null;
        var key = "ticker:" + ticker.trim().toUpperCase(java.util.Locale.ROOT) + ":" + currency;
        var firstTickerMatches = first.getSecurities().stream().filter(s -> key.equals(rawSecurityIdentity(s))).toList();
        var secondTickerMatches = second.getSecurities().stream().filter(s -> key.equals(rawSecurityIdentity(s))).toList();
        if (firstTickerMatches.size() != 1 || secondTickerMatches.size() != 1)
            return null;
        var firstIsin = normalizedIsin(firstTickerMatches.getFirst());
        var secondIsin = normalizedIsin(secondTickerMatches.getFirst());
        return firstIsin != null && secondIsin != null && !firstIsin.equals(secondIsin) ? null : key;
    }

    private static String normalizedIsin(Security security)
    {
        var isin = security.getIsin();
        return isin == null || isin.isBlank() ? null : isin.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String rawSecurityIdentity(Security security)
    {
        if (security.getCurrencyCode() == null || security.getTickerSymbol() == null || security.getTickerSymbol().isBlank()) return null;
        return "ticker:" + security.getTickerSymbol().trim().toUpperCase(java.util.Locale.ROOT) + ":" + security.getCurrencyCode();
    }

    private static String rawIsinIdentity(Security security)
    {
        if (security.getCurrencyCode() == null || security.getIsin() == null || security.getIsin().isBlank()) return null;
        return "isin:" + security.getIsin().trim().toUpperCase(java.util.Locale.ROOT) + ":" + security.getCurrencyCode();
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
        result.quoteKeys = quoteKeys;
        result.iconUrls = iconUrls;
        result.iconImages = iconImages;
        result.combinedSecurityGroups = combinedSecurityGroups;
        result.canonicalSecurityIds = canonicalSecurityIds;
        if (combinedOle != null)
        {
            var oleIds = selected.stream().filter(id -> combinedOle.scope.selection(List.of()).options().stream().anyMatch(o -> o.id().equals(id))).toList();
            var yingIds = selected.stream().filter(id -> combinedYing.scope.selection(List.of()).options().stream().anyMatch(o -> o.id().equals(id))).toList();
            result.combinedOle = combinedOle.excluding(String.join(",", oleIds));
            result.combinedYing = combinedYing.excluding(String.join(",", yingIds));
        }
        if (filtered.size() >= 12)
            filtered.remove(filtered.keySet().iterator().next());
        filtered.put(key, result);
        return result;
    }

    record AllocationFrame(String date, long totalValue, List<Holding> holdings)
    {
    }

    record AllocationFrames(String revision, String fxVersion, String currency, PortfolioScope.Selection scope, List<AllocationFrame> frames)
    {
    }

    synchronized AllocationFrames allocationFrames(LocalDate from, LocalDate to)
    {
        if (from.isBefore(inceptionDate) || to.isAfter(defaultClosingDate()) || to.isBefore(from)
                        || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 92)
            throw new IllegalArgumentException("Choose at most 93 recorded allocation dates");
        validateReportingRates(from, to);
        if (combinedOle != null)
        {
            var oleFrames = sourceAllocationFrames(combinedOle, from, to);
            var yingFrames = sourceAllocationFrames(combinedYing, from, to);
            if (oleFrames.size() != yingFrames.size())
                throw new IllegalArgumentException("Source allocation ranges do not align");
            var frames = new ArrayList<AllocationFrame>();
            for (int ii = 0; ii < oleFrames.size(); ii++)
            {
                var oleFrame = oleFrames.get(ii);
                var yingFrame = yingFrames.get(ii);
                if (!oleFrame.date().equals(yingFrame.date()))
                    throw new IllegalArgumentException("Source allocation dates do not align");
                frames.add(new AllocationFrame(oleFrame.date(), oleFrame.totalValue() + yingFrame.totalValue(),
                                combinedHoldings(oleFrame.holdings(), yingFrame.holdings())));
            }
            return new AllocationFrames(portfolio.revision(), fxVersion(), converter.getTermCurrency(), scope.selection(exclusions), List.copyOf(frames));
        }
        var frames = new ArrayList<AllocationFrame>();
        for (var date = from; !date.isAfter(to); date = date.plusDays(1))
        {
            var snapshot = ClientSnapshot.create(portfolio.client(), converter, date);
            frames.add(new AllocationFrame(date.toString(), snapshot.getMonetaryAssets().getAmount(),
                            holdings(snapshot, date, new LinkedHashSet<>())));
        }
        return new AllocationFrames(portfolio.revision(), fxVersion(), converter.getTermCurrency(), scope.selection(exclusions), List.copyOf(frames));
    }

    private List<AllocationFrame> sourceAllocationFrames(DashboardService service, LocalDate from, LocalDate to)
    {
        var frames = new ArrayList<AllocationFrame>();
        var firstRecorded = service.inceptionDate.isAfter(from) ? service.inceptionDate : from;
        for (var date = from; date.isBefore(firstRecorded) && !date.isAfter(to); date = date.plusDays(1))
            frames.add(new AllocationFrame(date.toString(), 0, List.of()));
        if (!firstRecorded.isAfter(to))
            frames.addAll(service.allocationFrames(firstRecorded, to).frames());
        return List.copyOf(frames);
    }

    byte[] icon(String uuid)
    {
        var mapped = iconImages.get(uuid);
        if (mapped != null)
            return mapped.clone();
        return icons.image(uuid);
    }

    Map<String, List<String>> quoteKeys()
    {
        return quoteKeys;
    }

    String fxVersion()
    {
        return rates == null ? null : rates.version();
    }

    String reportingCurrency()
    {
        return converter.getTermCurrency();
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
        return dashboard(openingDate, closingDate, benchmark, instrumentId, includeRankings, waterfallMode, false);
    }

    synchronized Dashboard dashboard(LocalDate openingDate, LocalDate closingDate, String benchmark, String instrumentId,
                    boolean includeRankings, String waterfallMode, boolean includeDailyRankings)
    {
        return dashboard(openingDate, closingDate, benchmark, instrumentId, includeRankings, waterfallMode,
                        includeDailyRankings, true);
    }

    private synchronized Dashboard dashboard(LocalDate openingDate, LocalDate closingDate, String benchmark,
                    String instrumentId, boolean includeRankings, String waterfallMode, boolean includeDailyRankings,
                    boolean limitWaterfallContributions)
    {
        if (waterfallMode != null && !waterfallMode.equals("calculation") && !waterfallMode.equals("contributions"))
            throw new IllegalArgumentException("Invalid waterfall mode");
        if (combinedOle != null)
            return combinedDashboard(openingDate, closingDate, benchmark, instrumentId, includeRankings, waterfallMode,
                            includeDailyRankings);
        var earliest = closingDate.minusDays(3660);
        if (inceptionDate.isBefore(earliest))
            earliest = inceptionDate;
        if (!openingDate.isBefore(closingDate) || closingDate.isAfter(LocalDate.now(clock)) || openingDate.isBefore(earliest))
            throw new IllegalArgumentException("Choose an opening date before the closing date, no future dates, and a range within 10 years or the portfolio's recorded history.");
        validateReportingRates(reportingRateStart(openingDate, closingDate), closingDate);

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
        var dailyRankings = includeDailyRankings
                        ? SecurityRankingService.calculate(portfolio.client(), converter, LocalDate.parse(day.openingDate()), closingDate) : null;
        return new Dashboard(portfolio.label(), portfolio.revision(), portfolio.loadedAt().toString(), portfolio.demo(),
                        converter.getTermCurrency(), closingDate.toString(), total, day, week, period(index), series,
                        holdings, List.copyOf(dividends), List.copyOf(warnings), BenchmarkService.options(scope.client),
                        BenchmarkService.calculate(scope.client, converter, openingDate, closingDate, benchmark),
                        coverage, fxVersion(), instrumentId == null ? null : instrument(instrumentId, openingDate, closingDate), rankings, dailyRankings,
                        waterfallMode == null ? null : waterfall(openingDate, closingDate, waterfallMode,
                                        limitWaterfallContributions), drawdown(index), inceptionDate.toString(), defaultClosingDate().toString(), iconUrls, scope.selection(exclusions), quoteKeys);
    }

    private List<Holding> holdings(ClientSnapshot snapshot, LocalDate closingDate, Set<String> warnings)
    {
        long total = snapshot.getMonetaryAssets().getAmount();
        var calculated = snapshot.getAssetPositions().map(position -> {
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
                            position.getInvestmentVehicle() instanceof Security ? "security" : "cash", iconUrls.get(vehicleId),
                            vehicleId, position.getPosition().getShares(),
                            position.getInvestmentVehicle().getCurrencyCode(),
                            position.getInvestmentVehicle() instanceof Security security ? security.getTickerSymbol() : null,
                            vehicle.getCurrencyCode() == null ? null : converter.getRateIfAvailable(closingDate, vehicle.getCurrencyCode())
                                            .map(rate -> rate.getValue().doubleValue()).orElse(null));
        }).toList();
        if (combinedSecurityGroups.isEmpty())
            return calculated.stream().sorted(Comparator.comparingLong(Holding::value).reversed()).toList();

        var grouped = new LinkedHashMap<String, Holding>();
        var amounts = new LinkedHashMap<String, long[]>();
        for (var holding : calculated)
        {
            var canonical = holding.uuid() == null ? null : canonicalSecurityIds.get(holding.uuid());
            var key = canonical == null ? "other:" + holding.id() : "security:" + canonical;
            var current = grouped.get(key);
            if (current == null || holding.uuid() != null && holding.uuid().equals(canonical))
                grouped.put(key, holding);
            var values = amounts.computeIfAbsent(key, ignored -> new long[2]);
            values[0] += holding.value();
            values[1] += holding.shares();
        }
        var result = new ArrayList<Holding>();
        for (var entry : grouped.entrySet())
        {
            var holding = entry.getValue();
            var totals = amounts.get(entry.getKey());
            var canonical = holding.uuid() == null ? null : canonicalSecurityIds.get(holding.uuid());
            String quoteDate = holding.quoteDate();
            if (canonical != null)
                quoteDate = calculated.stream().filter(h -> h.uuid() != null && canonical.equals(canonicalSecurityIds.get(h.uuid())) && h.quoteDate() != null)
                                .map(Holding::quoteDate).max(Comparator.naturalOrder()).orElse(quoteDate);
            result.add(new Holding(holding.name(), canonical == null ? holding.uuid() : canonical, totals[0],
                            total == 0 ? null : totals[0] / (double) total, quoteDate, holding.type(), holding.icon(),
                            canonical == null ? holding.id() : canonical, totals[1], holding.quoteCurrency(), holding.symbol(), holding.valuationFxRate()));
        }
        return result.stream().sorted(Comparator.comparingLong(Holding::value).reversed()).toList();
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

    private Waterfall waterfall(LocalDate from, LocalDate to, String mode, boolean limitContributions)
    {
        try
        {
            var snapshot = new ClientPerformanceSnapshot(portfolio.client(), converter, Interval.of(from, to), true);
            var breakdown = mode.equals("calculation") ? PerformanceBreakdown.createCalculation(snapshot)
                            : PerformanceBreakdown.createContributions(snapshot);
            if (limitContributions && mode.equals("contributions"))
                breakdown = breakdown.limitContributions(20);
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
        uuid = canonicalSecurityIds.getOrDefault(uuid, uuid);
        var instrumentUuid = uuid;
        var security = scope.client.getSecurities().stream().filter(s -> instrumentUuid.equals(s.getUUID())).findFirst().orElse(null);
        if (security == null)
            return new Instrument(uuid, null, null, Values.Quote.divider(), 0, null, null, null, null, null, 0, List.of(), Map.of(),
                            "This instrument is no longer available in the loaded portfolio.", SecurityActivityService.empty(),
                            List.of(), List.of());

        var sourceIds = combinedSecurityGroups.get(uuid);
        if (sourceIds != null && sourceIds.size() > 1)
        {
            var atEnd = ClientSnapshot.create(portfolio.client(), converter, closingDate);
            var positions = atEnd.getAssetPositions().filter(p -> p.getInvestmentVehicle() instanceof Security s
                            && sourceIds.contains(s.getUUID())).toList();
            long shares = positions.stream().mapToLong(p -> p.getPosition().getShares()).sum();
            Long value = positions.isEmpty() ? null : positions.stream().mapToLong(p -> p.getValuation().getAmount()).sum();
            return new Instrument(uuid, security.getName(), security.getCurrencyCode(), Values.Quote.divider(), shares,
                            value, null, null, null, null, 0, List.of(), Map.of(),
                            "This combined holding uses source-specific saved prices.",
                            SecurityActivityService.empty(), List.of(), List.of());
        }

        var atEnd = ClientSnapshot.create(portfolio.client(), converter, closingDate);
        var position = atEnd.getAssetPositions().filter(p -> security.equals(p.getInvestmentVehicle())).findFirst().orElse(null);
        var points = security.getPricesIncludingLatest().stream().filter(p -> !p.getDate().isAfter(closingDate))
                        .filter(p -> !p.getDate().isBefore(openingDate)).sorted(Comparator.comparing(p -> p.getDate()))
                        .map(p -> instrumentPoint(p, security.getCurrencyCode(), converter)).toList();
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
        var chart = InstrumentChartService.calculate(portfolio.client(), security, converter, openingDate, closingDate,
                        record != null, atStart.getAssetPositions().anyMatch(p -> security.equals(p.getSecurity())), position != null);
        return new Instrument(security.getUUID(), security.getName(), security.getCurrencyCode(), Values.Quote.divider(),
                        record == null ? 0 : record.getSharesHeld(),
                        position == null || !hasClosingQuote ? null : position.getValuation().getAmount(),
                        hasClosingQuote ? closingQuoteDate.toString() : null,
                        record == null || !validPeriod ? null : finite(record.getTrueTimeWeightedRateOfReturn()),
                        record == null || !validPeriod ? null : finite(record.getIrr()),
                        record == null || !validPeriod ? null : record.getDelta().getAmount(),
                        record == null ? 0 : record.getSumOfDividends().getAmount() - nonCashDividends, points, Map.of(),
                        !validPeriod ? (hasClosingQuote ? "The opening quote is missing or invalid; period performance is unavailable."
                                        : "No stored quote on or before the selected closing date is usable; valuation and period performance are unavailable.") :
                                        points.isEmpty() ? "No stored quotes in the selected period." : null,
                        SecurityActivityService.calculate(portfolio.client(), security, converter, openingDate, closingDate),
                        chart.totalReturn(), chart.events());
    }

    static InstrumentPoint instrumentPoint(SecurityPrice price, String currency, CurrencyConverter converter)
    {
        Long converted = currency == null ? null
                        : converter.convertIfAvailable(price.getDate(), Quote.of(currency, price.getValue()))
                                        .map(Quote::getAmount).orElse(null);
        return new InstrumentPoint(price.getDate().toString(), price.getValue(), converted);
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

    private record CombinedIndex(LocalDate[] dates, long[] totals, long[] inbound, long[] outbound,
                    long[] invested, double[] accumulated)
    {
    }

    private Dashboard combinedDashboard(LocalDate openingDate, LocalDate closingDate, String benchmark,
                    String instrumentId, boolean includeRankings, String waterfallMode, boolean includeDailyRankings)
    {
        if (!combinedOle.converter.getTermCurrency().equals(combinedYing.converter.getTermCurrency()))
            throw new IllegalArgumentException("Source reporting currencies must match for a combined view");
        var earliest = closingDate.minusDays(3660);
        if (inceptionDate.isBefore(earliest))
            earliest = inceptionDate;
        if (!openingDate.isBefore(closingDate) || closingDate.isAfter(LocalDate.now(clock)) || openingDate.isBefore(earliest))
            throw new IllegalArgumentException("Choose an opening date before the closing date, no future dates, and a range within 10 years or the portfolio's recorded history.");
        validateReportingRates(reportingRateStart(openingDate, closingDate), closingDate);
        var group = instrumentId == null ? List.<String>of() : combinedSecurityGroups.getOrDefault(
                        canonicalSecurityIds.getOrDefault(instrumentId, instrumentId), List.of(instrumentId));
        var oleInstrumentId = sourceSecurity(combinedOle, group);
        var yingInstrumentId = sourceSecurity(combinedYing, group);
        var oleDashboard = sourceDashboard(combinedOle, openingDate, closingDate, benchmark, oleInstrumentId, waterfallMode);
        var yingDashboard = sourceDashboard(combinedYing, openingDate, closingDate, benchmark, yingInstrumentId, waterfallMode);
        var warnings = new LinkedHashSet<String>();
        warnings.addAll(oleDashboard.warnings());
        warnings.addAll(yingDashboard.warnings());
        var index = combinedIndex(openingDate, closingDate, warnings);
        var dayIndex = combinedIndex(index(combinedOle, previousTradingDay(closingDate), closingDate, warnings),
                        index(combinedYing, previousTradingDay(closingDate), closingDate, warnings));
        var weekIndex = combinedIndex(index(combinedOle, closingDate.minusWeeks(1), closingDate, warnings),
                        index(combinedYing, closingDate.minusWeeks(1), closingDate, warnings));
        var points = new ArrayList<Point>();
        for (int ii = 0; ii < index.dates().length; ii++)
            points.add(new Point(index.dates()[ii].toString(), index.totals()[ii], finite(index.accumulated()[ii]), index.invested()[ii]));
        var holdings = combinedHoldings(oleDashboard.holdings(), yingDashboard.holdings());
        long total = oleDashboard.totalValue() + yingDashboard.totalValue();
        var securityHoldings = holdings.stream().filter(h -> h.type().equals("security")).toList();
        var quoteDates = securityHoldings.stream().map(Holding::quoteDate).filter(java.util.Objects::nonNull).sorted().toList();
        var coverage = new PriceCoverage(closingDate.toString(), securityHoldings.size(), securityHoldings.size() - quoteDates.size(),
                        (int) quoteDates.stream().filter(date -> LocalDate.parse(date).isBefore(closingDate.minusDays(7))).count(),
                        quoteDates.isEmpty() ? null : quoteDates.getFirst(), quoteDates.isEmpty() ? null : quoteDates.getLast());
        var dividends = new ArrayList<Dividend>(oleDashboard.dividends());
        dividends.addAll(yingDashboard.dividends());
        dividends.sort(Comparator.comparing(Dividend::date));
        var instrument = instrumentId == null ? null : combinedInstrument(instrumentId, group, oleDashboard, yingDashboard,
                        oleDashboard.instrument(), yingDashboard.instrument(), openingDate, closingDate);
        var inception = combinedOle.inceptionDate.isBefore(combinedYing.inceptionDate)
                        ? combinedOle.inceptionDate : combinedYing.inceptionDate;
        var benchmarks = java.util.stream.Stream.concat(oleDashboard.benchmarks().stream(), yingDashboard.benchmarks().stream()).distinct().toList();
        var benchmarkResult = oleDashboard.benchmark() != null ? oleDashboard.benchmark() : yingDashboard.benchmark();
        var combinedWaterfall = waterfallMode == null ? null
                        : combinedWaterfall(oleDashboard.waterfall(), yingDashboard.waterfall(), waterfallMode);
        var combinedFxVersion = oleDashboard.fxVersion() == null && yingDashboard.fxVersion() == null ? null
                        : String.valueOf(oleDashboard.fxVersion()) + ":" + String.valueOf(yingDashboard.fxVersion());
        var rankings = includeRankings ? combinedRankings(openingDate, closingDate) : null;
        var dailyRankings = includeDailyRankings ? combinedRankings(previousTradingDay(closingDate), closingDate) : null;
        return new Dashboard(portfolio.label(), portfolio.revision(), portfolio.loadedAt().toString(),
                        oleDashboard.demo() && yingDashboard.demo(), converter.getTermCurrency(), closingDate.toString(), total,
                        combinedPeriod(dayIndex), combinedPeriod(weekIndex),
                        combinedPeriod(index), List.copyOf(points), holdings, List.copyOf(dividends), List.copyOf(warnings),
                        benchmarks, benchmarkResult, coverage, combinedFxVersion, instrument, rankings, dailyRankings, combinedWaterfall,
                        combinedDrawdown(points), inception.toString(), defaultClosingDate().toString(), iconUrls,
                        scope.selection(exclusions), quoteKeys);
    }

    private String sourceSecurity(DashboardService service, List<String> ids)
    {
        return service.portfolio.client().getSecurities().stream().map(Security::getUUID).filter(ids::contains).findFirst().orElse(null);
    }

    private Dashboard sourceDashboard(DashboardService service, LocalDate openingDate, LocalDate closingDate,
                    String benchmark, String instrumentId, String waterfallMode)
    {
        var earliest = closingDate.minusDays(3660);
        if (service.inceptionDate.isBefore(earliest))
            earliest = service.inceptionDate;
        var effectiveOpening = openingDate.isBefore(earliest) ? earliest : openingDate;
        if (!effectiveOpening.isBefore(closingDate))
            effectiveOpening = closingDate.minusDays(1);
        return service.dashboard(effectiveOpening, closingDate, benchmark, instrumentId, false, waterfallMode, false,
                        false);
    }

    private SecurityRankingService.Rankings combinedRankings(LocalDate from, LocalDate to)
    {
        var oleEntries = rankingEntries(combinedOle, from, to);
        var yingEntries = rankingEntries(combinedYing, from, to);
        var entries = new ArrayList<SecurityRankingService.Entry>();
        for (var group : combinedSecurityGroups.entrySet())
        {
            var ids = group.getValue();
            var oleId = sourceSecurity(combinedOle, ids);
            var yingId = sourceSecurity(combinedYing, ids);
            var oleEntry = oleId == null ? null : oleEntries.get(oleId);
            var yingEntry = yingId == null ? null : yingEntries.get(yingId);
            if (oleEntry == null && yingEntry == null)
                continue;
            var name = oleEntry != null ? oleEntry.name() : yingEntry.name();
            Long gain = oleEntry != null && oleEntry.gain() == null || yingEntry != null && yingEntry.gain() == null
                            ? null : (oleEntry == null ? 0 : oleEntry.gain()) + (yingEntry == null ? 0 : yingEntry.gain());
            Double rate;
            Double factor;
            if (oleId != null && yingId != null)
            {
                try
                {
                    var index = combinedIndex(investmentIndex(combinedOle, oleId, from, to),
                                    investmentIndex(combinedYing, yingId, from, to));
                    int last = index.totals().length - 1;
                    rate = finite(index.accumulated()[last]);
                    double denominator = last > 0 ? (double) index.totals()[last - 1] + index.inbound()[last] : 0;
                    factor = denominator == 0 ? null : finite((1 + index.accumulated()[last - 1]) / denominator);
                }
                catch (RuntimeException unavailable)
                {
                    rate = null;
                    factor = null;
                }
            }
            else
            {
                var source = oleEntry != null ? oleEntry : yingEntry;
                rate = source.rate();
                factor = source.closingValueReturnFactor();
            }
            entries.add(new SecurityRankingService.Entry(group.getKey(), name, rate, gain, factor));
        }
        int unavailable = (int) entries.stream().filter(entry -> entry.rate() == null || entry.gain() == null).count();
        int neutral = (int) entries.stream().filter(entry -> entry.rate() != null && entry.gain() != null
                        && entry.rate() == 0d && entry.gain() == 0L).count();
        return new SecurityRankingService.Rankings(List.copyOf(entries), unavailable, neutral);
    }

    private Map<String, SecurityRankingService.Entry> rankingEntries(DashboardService service, LocalDate from, LocalDate to)
    {
        return SecurityRankingService.calculate(service.portfolio.client(), service.converter, from, to).securities().stream()
                        .collect(java.util.stream.Collectors.toMap(SecurityRankingService.Entry::uuid, entry -> entry));
    }

    private PerformanceIndex investmentIndex(DashboardService service, String uuid, LocalDate from, LocalDate to)
    {
        var security = service.portfolio.client().getSecurities().stream().filter(s -> uuid.equals(s.getUUID())).findFirst().orElseThrow();
        return PerformanceIndex.forInvestment(service.portfolio.client(), service.converter, security,
                        Interval.of(from, to), new ArrayList<>());
    }

    private Waterfall combinedWaterfall(Waterfall ole, Waterfall ying, String mode)
    {
        if (ole == null || ying == null || ole.message() != null || ying.message() != null)
            return new Waterfall(List.of(), "Waterfall details are unavailable for the combined view.");
        record WaterfallKey(String kind, String type, String uuid, String label)
        {
        }
        var entries = new LinkedHashMap<WaterfallKey, WaterfallEntry>();
        java.util.stream.Stream.concat(ole.entries().stream(), ying.entries().stream()).forEach(entry -> {
            var uuid = entry.uuid() == null ? null : canonicalSecurityIds.getOrDefault(entry.uuid(), entry.uuid());
            var key = new WaterfallKey(entry.kind(), entry.type(), uuid, uuid == null ? entry.label() : null);
            var previous = entries.get(key);
            entries.put(key, previous == null ? new WaterfallEntry(entry.kind(), entry.type(), entry.label(), entry.amount(), uuid)
                            : new WaterfallEntry(previous.kind(), previous.type(), previous.label(),
                                            previous.amount() + entry.amount(), previous.uuid()));
        });
        var result = new ArrayList<>(entries.values());
        if (mode.equals("contributions"))
        {
            var securities = result.stream().filter(entry -> entry.type().equals("SECURITY")).toList();
            var included = new java.util.HashSet<>(securities.stream()
                            .sorted(Comparator.comparingLong((WaterfallEntry entry) -> absolute(entry.amount())).reversed()
                                            .thenComparing(WaterfallEntry::label, String.CASE_INSENSITIVE_ORDER))
                            .limit(20).toList());
            long otherAmount = 0;
            for (var entry : securities)
            {
                if (!included.contains(entry))
                    otherAmount += entry.amount();
            }
            if (!included.containsAll(securities))
            {
                result.removeIf(entry -> entry.type().equals("SECURITY") && !included.contains(entry));
                int otherIndex = -1;
                for (int index = 0; index < result.size(); index++)
                {
                    if (result.get(index).type().equals("OTHER") && result.get(index).kind().equals("CHANGE"))
                    {
                        otherIndex = index;
                        break;
                    }
                }
                if (otherIndex >= 0)
                {
                    var previous = result.get(otherIndex);
                    result.set(otherIndex, new WaterfallEntry(previous.kind(), previous.type(), previous.label(),
                                    previous.amount() + otherAmount, null));
                }
                else if (otherAmount != 0)
                {
                    result.add(new WaterfallEntry("CHANGE", "OTHER", "Other", otherAmount, null));
                }
            }
        }
        result.sort(Comparator.comparingInt(entry -> switch (entry.kind())
        {
            case "START" -> 0;
            case "CHANGE" -> 1;
            case "TOTAL" -> 2;
            default -> 3;
        }));
        return new Waterfall(List.copyOf(result), null);
    }

    private long absolute(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    static final class MissingReportingRateException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        MissingReportingRateException(String currency, String reportingCurrency, LocalDate date)
        {
            super("Missing exchange rate for " + currency + " to " + reportingCurrency + " on " + date + ".");
        }
    }

    private void validateReportingRates(LocalDate from, LocalDate to)
    {
        if (!(converter instanceof AvailableCurrencyConverter))
            return;
        var cacheKey = from + ":" + to + ":" + converter.getTermCurrency();
        if (validatedRateRanges.contains(cacheKey))
            return;
        var currencies = new LinkedHashSet<String>();
        scope.client.getAccounts().forEach(account -> {
            currencies.add(account.getCurrencyCode());
            account.getTransactions().forEach(transaction -> currencies.add(transaction.getCurrencyCode()));
        });
        scope.client.getPortfolios().forEach(portfolio -> portfolio.getTransactions()
                        .forEach(transaction -> currencies.add(transaction.getCurrencyCode())));
        scope.client.getSecurities().forEach(security -> currencies.add(security.getCurrencyCode()));
        currencies.remove(null);
        currencies.remove(converter.getTermCurrency());
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
        {
            for (var currency : currencies)
            {
                if (converter.getRateIfAvailable(date, currency).isEmpty())
                    throw new MissingReportingRateException(currency, converter.getTermCurrency(), date);
            }
        }
        validatedRateRanges.add(cacheKey);
    }

    private LocalDate reportingRateStart(LocalDate openingDate, LocalDate closingDate)
    {
        var start = openingDate.isBefore(closingDate.minusWeeks(1)) ? openingDate : closingDate.minusWeeks(1);
        var priorTradingDay = previousTradingDay(closingDate);
        return priorTradingDay.isBefore(start) ? priorTradingDay : start;
    }

    DashboardService withCurrency(String currency) throws IOException
    {
        if (converter.getTermCurrency().equals(currency) && converter instanceof AvailableCurrencyConverter)
            return this;
        var loaded = new PortfolioSource.LoadedPortfolio(ClientFactory.duplicate(portfolio.client()), portfolio.label(),
                        portfolio.revision(), portfolio.loadedAt(), portfolio.demo());
        var factory = rates == null ? new ExchangeRateProviderFactory(loaded.client()) : rates.factory(loaded.client());
        var reportingConverter = new AvailableCurrencyConverter(factory, currency);
        var result = new DashboardService(loaded, clock, startupWarnings, rates, new PortfolioScope(loaded.client()),
                        List.of(), reportingConverter);
        result.quoteKeys = quoteKeys;
        result.iconUrls = iconUrls;
        result.iconImages = iconImages;
        result.combinedSecurityGroups = combinedSecurityGroups;
        result.canonicalSecurityIds = canonicalSecurityIds;
        if (combinedOle != null)
        {
            result.combinedOle = combinedOle.withCurrency(currency);
            result.combinedYing = combinedYing.withCurrency(currency);
        }
        return result;
    }

    private CombinedIndex combinedIndex(LocalDate from, LocalDate to, Set<String> warnings)
    {
        return combinedIndex(index(combinedOle, from, to, warnings), index(combinedYing, from, to, warnings));
    }

    private PerformanceIndex index(DashboardService service, LocalDate from, LocalDate to, Set<String> warnings)
    {
        return service.index(from, to, warnings);
    }

    private CombinedIndex combinedIndex(PerformanceIndex ole, PerformanceIndex ying)
    {
        if (ole.getDates().length != ying.getDates().length)
            throw new IllegalArgumentException("Source portfolio ranges do not align");
        int size = ole.getDates().length;
        var dates = ole.getDates().clone();
        var totals = new long[size];
        var inbound = new long[size];
        var outbound = new long[size];
        var invested = new long[size];
        var accumulated = new double[size];
        var oleInvested = ole.calculateInvestedCapital();
        var yingInvested = ying.calculateInvestedCapital();
        for (int ii = 0; ii < size; ii++)
        {
            if (!dates[ii].equals(ying.getDates()[ii]))
                throw new IllegalArgumentException("Source portfolio dates do not align");
            totals[ii] = ole.getTotals()[ii] + ying.getTotals()[ii];
            inbound[ii] = ole.getInboundTransferals()[ii] + ying.getInboundTransferals()[ii];
            outbound[ii] = ole.getOutboundTransferals()[ii] + ying.getOutboundTransferals()[ii];
            invested[ii] = oleInvested[ii] + yingInvested[ii];
            if (ii > 0)
            {
                long opening = totals[ii - 1] + inbound[ii];
                double daily = opening == 0 ? 0 : (totals[ii] + outbound[ii]) / (double) opening - 1;
                accumulated[ii] = (1 + accumulated[ii - 1]) * (1 + daily) - 1;
            }
        }
        return new CombinedIndex(dates, totals, inbound, outbound, invested, accumulated);
    }

    private Period combinedPeriod(CombinedIndex index)
    {
        return period(index.dates(), index.totals(), index.inbound(), index.outbound(), index.accumulated());
    }

    private Period combinedPeriod(PerformanceIndex ole, PerformanceIndex ying)
    {
        return combinedPeriod(combinedIndex(ole, ying));
    }

    private Period period(LocalDate[] dates, long[] totals, long[] inbound, long[] outbound, double[] accumulated)
    {
        int last = totals.length - 1;
        long flows = 0;
        for (int ii = 1; ii < totals.length; ii++)
            flows += inbound[ii] - outbound[ii];
        double denominator = last > 0 ? (double) totals[last - 1] + inbound[last] : 0;
        Double closingFactor = denominator == 0 ? null : finite((1 + accumulated[last - 1]) / denominator);
        long valueChange = totals[last] - totals[0];
        return new Period(dates[0].toString(), dates[last].toString(), valueChange, flows, valueChange - flows,
                        finite(accumulated[last]), closingFactor);
    }

    private List<Holding> combinedHoldings(List<Holding> ole, List<Holding> ying)
    {
        var all = new ArrayList<Holding>(ole);
        all.addAll(ying);
        long total = all.stream().mapToLong(Holding::value).sum();
        var rows = new LinkedHashMap<String, Holding>();
        var values = new LinkedHashMap<String, long[]>();
        for (var holding : all)
        {
            var canonical = holding.uuid() == null ? null : canonicalSecurityIds.get(holding.uuid());
            var key = canonical == null ? "other:" + holding.id() : "security:" + canonical;
            var old = rows.get(key);
            if (old == null || holding.uuid() != null && holding.uuid().equals(canonical))
                rows.put(key, holding);
            var pair = values.computeIfAbsent(key, ignored -> new long[2]);
            pair[0] += holding.value();
            pair[1] += holding.shares();
        }
        var result = new ArrayList<Holding>();
        rows.forEach((key, row) -> {
            var pair = values.get(key);
            var canonical = row.uuid() == null ? null : canonicalSecurityIds.get(row.uuid());
            var quoteDate = canonical == null ? row.quoteDate()
                            : all.stream().filter(h -> h.uuid() != null && canonical.equals(canonicalSecurityIds.get(h.uuid())) && h.quoteDate() != null)
                                            .map(Holding::quoteDate).max(Comparator.naturalOrder()).orElse(row.quoteDate());
            result.add(new Holding(row.name(), canonical == null ? row.uuid() : canonical, pair[0],
                            total == 0 ? null : pair[0] / (double) total, quoteDate, row.type(), row.icon(),
                            canonical == null ? row.id() : canonical, pair[1], row.quoteCurrency(), row.symbol(), row.valuationFxRate()));
        });
        return result.stream().sorted(Comparator.comparingLong(Holding::value).reversed()).toList();
    }

    private Instrument combinedInstrument(String uuid, List<String> ids,
                    Dashboard oleDashboard, Dashboard yingDashboard, Instrument oleInstrument, Instrument yingInstrument,
                    LocalDate openingDate, LocalDate closingDate)
    {
        if (ids.size() < 2)
            return oleInstrument != null ? oleInstrument : yingInstrument != null ? yingInstrument
                            : new Instrument(uuid, null, null, Values.Quote.divider(), 0, null, null, null, null, null, 0,
                                            List.of(), Map.of(), "This instrument is no longer available in the loaded portfolio.",
                                            SecurityActivityService.empty(), List.of(), List.of());
        var name = oleInstrument != null ? oleInstrument.name() : yingInstrument == null ? null : yingInstrument.name();
        var currency = oleInstrument != null ? oleInstrument.currency() : yingInstrument == null ? null : yingInstrument.currency();
        var shares = (oleInstrument == null ? 0 : oleInstrument.shares()) + (yingInstrument == null ? 0 : yingInstrument.shares());
        var holdingRows = java.util.stream.Stream.concat(oleDashboard.holdings().stream(), yingDashboard.holdings().stream())
                        .filter(h -> h.uuid() != null && ids.contains(h.uuid())).toList();
        Long value = holdingRows.isEmpty() ? null : holdingRows.stream().mapToLong(Holding::value).sum();
        var quoteDate = holdingRows.stream().map(Holding::quoteDate).filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null);
        var sourcePrices = new LinkedHashMap<String, List<InstrumentPoint>>();
        var oleSecurity = sourceSecurity(combinedOle, ids);
        var yingSecurity = sourceSecurity(combinedYing, ids);
        if (oleSecurity != null)
            sourcePrices.put("Ole", sourcePrices(combinedOle, oleSecurity, openingDate, closingDate));
        if (yingSecurity != null)
            sourcePrices.put("Ying", sourcePrices(combinedYing, yingSecurity, openingDate, closingDate));

        var combinedReturn = new ArrayList<ReturnPoint>();
        Double twror = null;
        try
        {
            var oleIndex = oleSecurity == null ? null : investmentIndex(combinedOle, oleSecurity, openingDate, closingDate);
            var yingIndex = yingSecurity == null ? null : investmentIndex(combinedYing, yingSecurity, openingDate, closingDate);
            if (oleIndex != null && yingIndex != null)
            {
                var index = combinedIndex(oleIndex, yingIndex);
                for (int ii = 0; ii < index.dates().length; ii++)
                    combinedReturn.add(new ReturnPoint(index.dates()[ii].toString(), finite(index.accumulated()[ii])));
                twror = finite(index.accumulated()[index.accumulated().length - 1]);
            }
            else
            {
                var index = oleIndex == null ? yingIndex : oleIndex;
                for (int ii = 0; ii < index.getDates().length; ii++)
                    combinedReturn.add(new ReturnPoint(index.getDates()[ii].toString(),
                                    finite(index.getAccumulatedPercentage()[ii])));
                twror = finite(index.getFinalAccumulatedPercentage());
            }
        }
        catch (RuntimeException unavailable)
        {
            combinedReturn.clear();
        }

        var activity = combineActivity(oleInstrument == null ? null : oleInstrument.activity(),
                        yingInstrument == null ? null : yingInstrument.activity());
        var events = Stream.concat(oleInstrument == null ? Stream.empty() : oleInstrument.events().stream(),
                        yingInstrument == null ? Stream.empty() : yingInstrument.events().stream())
                        .sorted(Comparator.comparing(ChartEvent::date)).toList();
        long dividends = reportedDividends(oleInstrument) + reportedDividends(yingInstrument);
        Long gain = combineGain(oleInstrument, yingInstrument);
        String message = combinedReturn.isEmpty() ? "Combined performance is unavailable for this period; source price histories remain available."
                        : "Performance and activity combine both source holdings; price histories are shown separately by source.";
        return new Instrument(uuid, name, currency, Values.Quote.divider(), shares, value, quoteDate, twror, null,
                        gain, dividends, List.of(), Map.copyOf(sourcePrices), message, activity,
                        List.copyOf(combinedReturn), events);
    }

    private List<InstrumentPoint> sourcePrices(DashboardService service, String uuid, LocalDate from, LocalDate to)
    {
        var security = service.portfolio.client().getSecurities().stream().filter(s -> uuid.equals(s.getUUID())).findFirst()
                        .orElseThrow();
        return security.getPricesIncludingLatest().stream().filter(price -> !price.getDate().isAfter(to))
                        .filter(price -> !price.getDate().isBefore(from))
                        .sorted(Comparator.comparing(SecurityPrice::getDate))
                        .map(price -> instrumentPoint(price, security.getCurrencyCode(), service.converter)).toList();
    }

    private Long combineGain(Instrument ole, Instrument ying)
    {
        if (ole == null && ying == null)
            return null;
        if (hasUnavailableGain(ole) || hasUnavailableGain(ying))
            return null;
        return (ole == null || ole.gain() == null ? 0 : ole.gain())
                        + (ying == null || ying.gain() == null ? 0 : ying.gain());
    }

    private boolean hasUnavailableGain(Instrument instrument)
    {
        return instrument != null && instrument.gain() == null
                        && (instrument.shares() != 0 || !instrument.activity().transactions().isEmpty());
    }

    private long reportedDividends(Instrument instrument)
    {
        if (instrument == null || instrument.dividends() != 0 || instrument.activity().dividends().isEmpty())
            return instrument == null ? 0 : instrument.dividends();
        var payments = instrument.activity().dividends();
        if (payments.stream().anyMatch(payment -> payment.reportingNet() == null))
            return instrument.dividends();
        return payments.stream().mapToLong(SecurityActivityService.Payment::reportingNet).sum();
    }

    private SecurityActivityService.Activity combineActivity(SecurityActivityService.Activity ole,
                    SecurityActivityService.Activity ying)
    {
        var dividends = Stream.concat(ole == null ? Stream.empty() : ole.dividends().stream(),
                        ying == null ? Stream.empty() : ying.dividends().stream())
                        .sorted(Comparator.comparing(SecurityActivityService.Payment::date).reversed()).toList();
        var transactions = Stream.concat(ole == null ? Stream.empty() : ole.transactions().stream(),
                        ying == null ? Stream.empty() : ying.transactions().stream())
                        .sorted(Comparator.comparing(SecurityActivityService.Entry::date).reversed()).toList();
        return new SecurityActivityService.Activity(dividends, transactions);
    }

    private Drawdown combinedDrawdown(List<Point> points)
    {
        double peak = Double.NEGATIVE_INFINITY;
        double maximum = 0;
        String peakDate = points.isEmpty() ? null : points.getFirst().date();
        String currentPeakDate = peakDate;
        String troughDate = peakDate;
        double current = 0;
        long longest = 0;
        int highIndex = 0;
        var series = new ArrayList<DrawdownPoint>();
        for (int ii = 0; ii < points.size(); ii++)
        {
            var wealth = 1 + points.get(ii).rate();
            if (wealth >= peak)
            {
                peak = wealth;
                currentPeakDate = points.get(ii).date();
                highIndex = ii;
            }
            current = peak == 0 ? 0 : wealth / peak - 1;
            if (-current > maximum)
            {
                maximum = -current;
                peakDate = currentPeakDate;
                troughDate = points.get(ii).date();
            }
            longest = Math.max(longest, java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.parse(points.get(highIndex).date()), LocalDate.parse(points.get(ii).date())));
            series.add(new DrawdownPoint(points.get(ii).date(), finite(current)));
        }
        return new Drawdown(finite(maximum), peakDate, troughDate, finite(current), longest, List.copyOf(series));
    }
}
