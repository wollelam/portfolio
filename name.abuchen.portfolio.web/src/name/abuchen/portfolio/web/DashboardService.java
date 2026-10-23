package name.abuchen.portfolio.web;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit.Type;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;
import name.abuchen.portfolio.util.TradeCalendarManager;

/** All monetary API values are integer minor units; returns are decimal ratios. */
@SuppressWarnings("nls")
final class DashboardService
{
    record Period(String openingDate, String closingDate, long valueChange, long netFlows, long gain, Double rate)
    {
    }

    record Point(String date, long value, Double rate)
    {
    }

    record Holding(String name, long value, Double weight, String quoteDate, String type)
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
                    BenchmarkService.Result benchmark, PriceCoverage priceCoverage, String fxVersion)
    {
    }

    private final PortfolioSource.LoadedPortfolio portfolio;
    private final CurrencyConverterImpl converter;
    private final Clock clock;
    private final List<String> startupWarnings;
    private final FxRates.View rates;

    DashboardService(PortfolioSource.LoadedPortfolio portfolio, Clock clock, List<String> startupWarnings)
    {
        this(portfolio, clock, startupWarnings, null);
    }

    DashboardService(PortfolioSource.LoadedPortfolio portfolio, Clock clock, List<String> startupWarnings,
                    FxRates.View rates)
    {
        this.portfolio = portfolio;
        this.clock = clock;
        this.startupWarnings = List.copyOf(startupWarnings);
        this.rates = rates;
        this.converter = new CurrencyConverterImpl(rates == null ? new ExchangeRateProviderFactory(portfolio.client())
                        : rates.factory(portfolio.client()),
                        portfolio.client().getBaseCurrency());
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
        if (!openingDate.isBefore(closingDate) || closingDate.isAfter(LocalDate.now(clock))
                        || ChronoUnit.DAYS.between(openingDate, closingDate) > 3660)
            throw new IllegalArgumentException("Choose an opening date before the closing date, no future dates, and at most 10 years.");

        var warnings = new LinkedHashSet<>(startupWarnings);
        var index = index(openingDate, closingDate, warnings);
        var day = period(index(previousTradingDay(closingDate), closingDate, warnings));
        var week = period(index(closingDate.minusWeeks(1), closingDate, warnings));
        var snapshot = ClientSnapshot.create(portfolio.client(), converter, closingDate);
        long total = snapshot.getMonetaryAssets().getAmount();
        var holdings = snapshot.getAssetPositions().map(position -> {
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
            return new Holding(position.getDescription(), position.getValuation().getAmount(),
                            total == 0 ? null : position.getValuation().getAmount() / (double) total, quoteDate,
                            position.getInvestmentVehicle() instanceof Security ? "security" : "cash");
        }).sorted(Comparator.comparingLong(Holding::value).reversed()).toList();

        var series = new ArrayList<Point>();
        for (int ii = 0; ii < index.getDates().length; ii++)
            series.add(new Point(index.getDates()[ii].toString(), index.getTotals()[ii],
                            finite(index.getAccumulatedPercentage()[ii])));

        var dividends = new ArrayList<Dividend>();
        for (var account : portfolio.client().getAccounts())
        {
            for (var transaction : account.getTransactions())
            {
                var date = transaction.getDateTime().toLocalDate();
                if (transaction.getType() != AccountTransaction.Type.DIVIDENDS || !date.isAfter(openingDate)
                                || date.isAfter(closingDate))
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
        return new Dashboard(portfolio.label(), portfolio.revision(), portfolio.loadedAt().toString(), portfolio.demo(),
                        converter.getTermCurrency(), closingDate.toString(), total, day, week, period(index), series,
                        holdings, List.copyOf(dividends), List.copyOf(warnings), BenchmarkService.options(portfolio.client()),
                        BenchmarkService.calculate(portfolio.client(), converter, openingDate, closingDate, benchmark),
                        coverage, fxVersion());
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
        return new Period(index.getActualInterval().getStart().toString(), index.getActualInterval().getEnd().toString(),
                        valueChange, flows, valueChange - flows, finite(index.getFinalAccumulatedPercentage()));
    }

    private Double finite(double value)
    {
        return Double.isFinite(value) ? value : null;
    }
}
