package name.abuchen.portfolio.cli;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.snapshot.AssetPosition;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

/** Calculates a deterministic ranking of securities currently held by a client. */
public final class PerformerRanking
{
    private PerformerRanking()
    {
    }

    public record Performer(Security security, String name, double performance, long currencyPerformance,
                    double currencyPerformancePercent, double irr, long value) {}

    /**
     * Returns holdings ordered from best to worst by cumulative TTWROR over
     * {@code interval}. Securities with no current position are omitted. The
     * calculation is read-only; warnings are appended to the supplied list.
     */
    public static List<Performer> rank(Client client, CurrencyConverter converter, Interval interval, int limit,
                    List<Exception> warnings)
    {
        if (limit == 0 || limit < -1)
            throw new IllegalArgumentException("limit must be positive or -1"); //$NON-NLS-1$

        PerformanceIndex clientIndex = PerformanceIndex.forClient(client, converter, interval, warnings);
        return rank(client, converter, interval, clientIndex, limit);
    }

    static List<Performer> rank(Client client, CurrencyConverter converter, Interval interval,
                    PerformanceIndex clientIndex, int limit)
    {
        if (limit == 0 || limit < -1)
            throw new IllegalArgumentException("limit must be positive or -1"); //$NON-NLS-1$

        ClientSnapshot snapshot = ClientSnapshot.create(client, converter, interval.getEnd());
        LazySecurityPerformanceSnapshot currencyPerformance = LazySecurityPerformanceSnapshot.create(client, converter,
                        interval);
        Set<Security> seen = new HashSet<>();
        List<Performer> result = new ArrayList<>();
        for (AssetPosition position : snapshot.getAssetPositions().toList())
        {
            Security security = position.getSecurity();
            if (security == null || !seen.add(security))
                continue;
            double performance = PerformanceIndex.forSecurity(clientIndex, security).getFinalAccumulatedPercentage();
            var currencyRecord = currencyPerformance.getRecord(security);
            long performanceInCurrency = currencyRecord.map(record -> record.getDelta().getAmount()).orElse(0L);
            double performanceInCurrencyPercent = currencyRecord.map(record -> record.getDeltaPercent()).orElse(0d);
            double irr = currencyRecord.map(record -> record.getIrr()).orElse(Double.NaN);
            result.add(new Performer(security, position.getDescription(), performance, performanceInCurrency,
                            performanceInCurrencyPercent, irr, position.getValuation().getAmount()));
        }
        result.sort(Comparator.comparingDouble(Performer::performance).reversed()
                        .thenComparing(Performer::name, String.CASE_INSENSITIVE_ORDER));
        if (limit > 0 && result.size() > limit)
            return List.copyOf(result.subList(0, limit));
        return List.copyOf(result);
    }

    /** Returns the supplied performers ordered from largest to smallest portfolio-currency performance. */
    public static List<Performer> sortByCurrencyPerformance(List<Performer> performers)
    {
        return performers.stream().sorted(Comparator.comparingLong(Performer::currencyPerformance).reversed()
                        .thenComparing(Performer::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public static List<Performer> rank(Client client, CurrencyConverter converter, LocalDate from, LocalDate to,
                    int limit, List<Exception> warnings)
    {
        return rank(client, converter, Interval.of(from, to), limit, warnings);
    }
}
