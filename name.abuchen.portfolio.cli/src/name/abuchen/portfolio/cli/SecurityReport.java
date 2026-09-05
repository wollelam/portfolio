package name.abuchen.portfolio.cli;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.CostMethod;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.TaxesAndFees;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.AssetPosition;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceRecord;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

/** Renders a compact, read-only security report for a reporting interval. */
public final class SecurityReport
{
    private SecurityReport()
    {
    }

    public static List<String> render(Client client, String query, Interval interval)
    {
        Objects.requireNonNull(client, "client"); //$NON-NLS-1$
        Objects.requireNonNull(query, "query"); //$NON-NLS-1$
        Objects.requireNonNull(interval, "interval"); //$NON-NLS-1$

        Security security = find(client, query);
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), client.getBaseCurrency());
        ClientSnapshot valuation = ClientSnapshot.create(client, converter, interval.getEnd());
        LazySecurityPerformanceRecord performance = LazySecurityPerformanceSnapshot.create(client, converter, interval)
                        .getRecord(security).orElse(null);

        AssetPosition position = valuation.getAssetPositions().filter(p -> security.equals(p.getSecurity())).findFirst()
                        .orElse(null);
        SecurityPrice quote = security.getPricesIncludingLatest().stream().filter(price -> !price.getDate().isAfter(interval.getEnd()))
                        .max(Comparator.comparing(SecurityPrice::getDate)).orElse(null);

        List<String> lines = new ArrayList<>();
        lines.add("Security: " + security.getName()); //$NON-NLS-1$
        lines.add("Identifiers: " + identifiers(security)); //$NON-NLS-1$
        lines.add("Period: " + interval.getStart() + " to " + interval.getEnd()); //$NON-NLS-1$ //$NON-NLS-2$
        lines.add("Holding at end: " + holding(performance, position)); //$NON-NLS-1$
        lines.add("Quote currency: " + security.getCurrencyCode()); //$NON-NLS-1$
        lines.add("Latest quote as of " + interval.getEnd() + ": " + quote(quote, interval)); //$NON-NLS-1$ //$NON-NLS-2$
        lines.add("Period basis (FIFO, held shares; opening valuation + purchases incl. charges): " + money(performance, //$NON-NLS-1$
                        p -> p.getCost(CostMethod.FIFO, TaxesAndFees.INCLUDED)));
        lines.add("TTWROR (period, " + client.getBaseCurrency() + "): " + percent(performance)); //$NON-NLS-1$ //$NON-NLS-2$
        lines.add("Currency performance / delta (period): " + money(performance, LazySecurityPerformanceRecord::getDelta)); //$NON-NLS-1$
        lines.add("Dividends (period): " + money(performance, LazySecurityPerformanceRecord::getSumOfDividends)); //$NON-NLS-1$
        lines.add("Fees (period): " + money(performance, LazySecurityPerformanceRecord::getFees)); //$NON-NLS-1$
        lines.add("Taxes (period): " + money(performance, LazySecurityPerformanceRecord::getTaxes)); //$NON-NLS-1$
        lines.add("Realized capital gains (FIFO, period): " + (performance == null ? "n/a" //$NON-NLS-1$
                        : CliFormatter.money(performance.getRealizedCapitalGains(CostMethod.FIFO).getCapitalGains())));
        lines.add("Unrealized capital gains (FIFO, period): " + (performance == null ? "n/a" //$NON-NLS-1$
                        : CliFormatter.money(performance.getUnrealizedCapitalGains(CostMethod.FIFO).getCapitalGains())));
        return List.copyOf(lines);
    }

    private static Security find(Client client, String query)
    {
        String needle = query.trim();
        if (needle.isEmpty())
            throw new IllegalArgumentException("Security query must not be empty."); //$NON-NLS-1$

        List<Security> exact = client.getSecurities().stream().filter(security -> exact(security, needle)).toList();
        if (exact.size() == 1)
            return exact.get(0);
        if (exact.size() > 1)
            throw ambiguous(needle, exact);

        String lower = needle.toLowerCase(Locale.ROOT);
        List<Security> partial = client.getSecurities().stream().filter(security -> matches(security, lower))
                        .sorted(Comparator.comparing(Security::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (partial.size() == 1)
            return partial.get(0);
        if (partial.isEmpty())
            throw new IllegalArgumentException("No security matches '" + needle + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        throw ambiguous(needle, partial);
    }

    private static boolean exact(Security security, String query)
    {
        return query.equalsIgnoreCase(security.getName()) || query.equalsIgnoreCase(security.getIsin())
                        || query.equalsIgnoreCase(security.getWkn()) || query.equalsIgnoreCase(security.getTickerSymbol());
    }

    private static boolean matches(Security security, String query)
    {
        return contains(security.getName(), query) || contains(security.getIsin(), query) || contains(security.getWkn(), query)
                        || contains(security.getTickerSymbol(), query);
    }

    private static boolean contains(String value, String query)
    {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static IllegalArgumentException ambiguous(String query, List<Security> candidates)
    {
        String names = candidates.stream().map(Security::getName).sorted(String.CASE_INSENSITIVE_ORDER)
                        .reduce((left, right) -> left + ", " + right).orElse(""); //$NON-NLS-1$ //$NON-NLS-2$
        return new IllegalArgumentException("Ambiguous security '" + query + "': " + names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String identifiers(Security security)
    {
        List<String> values = new ArrayList<>();
        if (security.getIsin() != null)
            values.add("ISIN=" + security.getIsin()); //$NON-NLS-1$
        if (security.getWkn() != null)
            values.add("WKN=" + security.getWkn()); //$NON-NLS-1$
        if (security.getTickerSymbol() != null)
            values.add("Ticker=" + security.getTickerSymbol()); //$NON-NLS-1$
        return values.isEmpty() ? "none" : String.join(", ", values); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String weight(AssetPosition position)
    {
        return position == null || !Double.isFinite(position.getShare()) ? "n/a" //$NON-NLS-1$
                        : CliFormatter.percent(position.getShare()); //$NON-NLS-1$
    }

    private static String holding(LazySecurityPerformanceRecord performance, AssetPosition position)
    {
        if (performance == null)
            return "n/a"; //$NON-NLS-1$
        return "shares " + CliFormatter.share(performance.getSharesHeld()) + ", value " //$NON-NLS-1$ //$NON-NLS-2$
                        + CliFormatter.money(position == null ? performance.getMarketValue() : position.getValuation())
                        + ", weight " + weight(position); //$NON-NLS-1$
    }

    private static String quote(SecurityPrice quote, Interval interval)
    {
        if (quote == null)
            return "n/a (no quote on or before period end)"; //$NON-NLS-1$
        long age = ChronoUnit.DAYS.between(quote.getDate(), interval.getEnd());
        return CliFormatter.quote(quote.getValue()) + " (price date " + quote.getDate() + ", " + age + " days old)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String percent(LazySecurityPerformanceRecord performance)
    {
        return performance == null ? "n/a" : CliFormatter.percent(performance.getTrueTimeWeightedRateOfReturn()); //$NON-NLS-1$
    }

    private static String money(LazySecurityPerformanceRecord performance,
                    java.util.function.Function<LazySecurityPerformanceRecord, name.abuchen.portfolio.money.Money> value)
    {
        return performance == null ? "n/a" : CliFormatter.money(value.apply(performance)); //$NON-NLS-1$
    }
}
