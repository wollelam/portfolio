package name.abuchen.portfolio.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

/** Per-security period results from the same native calculation engine as instrument details. */
final class SecurityRankingService
{
    record Entry(String uuid, String name, Double rate, Long gain, Double closingValueReturnFactor)
    {
    }

    record Rankings(List<Entry> securities, int unavailable, int neutral)
    {
    }

    private SecurityRankingService()
    {
    }

    static Rankings calculate(Client client, CurrencyConverter converter, LocalDate from, LocalDate to)
    {
        var opening = ClientSnapshot.create(client, converter, from);
        var closing = ClientSnapshot.create(client, converter, to);
        var heldAtOpening = heldSecurities(opening);
        var heldAtClosing = heldSecurities(closing);
        var snapshot = LazySecurityPerformanceSnapshot.create(client, converter, Interval.of(from, to), opening, closing);
        var rows = new ArrayList<Entry>();

        for (var record : snapshot.getRecords())
        {
            var security = record.getSecurity();
            if (record.getLineItems().isEmpty() && !heldAtOpening.contains(security) && !heldAtClosing.contains(security))
                continue;

            Double rate = null;
            Long gain = null;
            Double closingValueReturnFactor = null;
            // A missing quote can make the native valuation zero. Check ownership independently of value.
            if (pricesAvailable(security, from, to, heldAtOpening.contains(security), heldAtClosing.contains(security)))
            {
                var index = safely(() -> PerformanceIndex.forInvestment(client, converter, security, Interval.of(from, to),
                                new ArrayList<>()));
                rate = index == null ? null : safely(index::getFinalAccumulatedPercentage);
                if (index != null)
                {
                    int last = index.getTotals().length - 1;
                    double denominator = last > 0 ? (double) index.getTotals()[last - 1] + index.getInboundTransferals()[last] : 0;
                    if (denominator != 0)
                    {
                        double factor = (1 + index.getAccumulatedPercentage()[last - 1]) / denominator;
                        if (Double.isFinite(factor))
                            closingValueReturnFactor = factor;
                    }
                }
                if (rate != null && !Double.isFinite(rate))
                    rate = null;
                var delta = safely(record::getDelta);
                gain = delta == null ? null : delta.getAmount();
            }
            rows.add(new Entry(security.getUUID(), security.getName(), rate, gain, closingValueReturnFactor));
        }

        int unavailable = (int) rows.stream().filter(row -> row.rate() == null || row.gain() == null).count();
        int neutral = (int) rows.stream().filter(row -> row.rate() != null && row.gain() != null
                        && row.rate() == 0d && row.gain() == 0L).count();
        return new Rankings(List.copyOf(rows), unavailable, neutral);
    }

    private static Set<Security> heldSecurities(ClientSnapshot snapshot)
    {
        return snapshot.getAssetPositions().map(position -> position.getSecurity()).filter(Objects::nonNull)
                        .collect(Collectors.toSet());
    }

    static boolean pricesAvailable(Security security, LocalDate from, LocalDate to, boolean heldAtOpening, boolean heldAtClosing)
    {
        return (!heldAtOpening || usableQuote(security, from)) && (!heldAtClosing || usableQuote(security, to));
    }

    static boolean usableQuote(Security security, LocalDate date)
    {
        return security.getPricesIncludingLatest().stream().filter(price -> !price.getDate().isAfter(date))
                        .max(Comparator.comparing(price -> price.getDate())).map(price -> price.getValue() > 0).orElse(false);
    }

    private static <T> T safely(Supplier<T> calculation)
    {
        try
        {
            return calculation.get();
        }
        catch (RuntimeException failure)
        {
            // One unavailable metric must not hide other securities or their independently valid metrics.
            return null;
        }
    }
}
