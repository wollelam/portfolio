package name.abuchen.portfolio.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.AssetPosition;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.CategoryType;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;

/** A compact overview using the production valuation and performance engines. */
@SuppressWarnings("nls")
public final class SummaryReport
{
    private static final int INSTRUMENT_LIMIT = 5;

    private SummaryReport()
    {
    }

    public static List<String> render(Client client, Interval interval)
    {
        var lines = new ArrayList<String>();
        var warnings = new ArrayList<Exception>();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), client.getBaseCurrency());
        var performance = new ClientPerformanceSnapshot(client, converter, interval);
        var snapshot = performance.getEndClientSnapshot();
        var index = PerformanceIndex.forClient(client, converter, interval, warnings);
        var positions = snapshot.getAssetPositions().sorted(Comparator.comparing(AssetPosition::getValuation).reversed())
                        .toList();
        long cash = positions.stream().filter(p -> p.getSecurity() == null)
                        .mapToLong(p -> p.getValuation().getAmount()).sum();
        lines.add("PORTFOLIO SUMMARY  " + interval.getStart() + " to " + interval.getEnd());
        lines.add("Base currency: " + client.getBaseCurrency() + " | valuation date: " + interval.getEnd());
        lines.add("Total value       " + Values.Money.format(snapshot.getMonetaryAssets()));
        lines.add(String.format("Return (TTWROR)   %+.2f%%", index.getFinalAccumulatedPercentage() * 100));
        lines.add("Performance       " + Values.Money.format(performance.getAbsoluteDelta()));
        lines.add("Net deposits      " + Values.Money.format(performance.getValue(CategoryType.TRANSFERS)));
        lines.add("Cash              " + Values.Money.format(Money.of(client.getBaseCurrency(), cash)));
        lines.add("Earnings          " + Values.Money.format(performance.getValue(CategoryType.EARNINGS)));
        lines.add("Fees / taxes      " + Values.Money.format(performance.getValue(CategoryType.FEES)) + " / "
                        + Values.Money.format(performance.getValue(CategoryType.TAXES)));
        lines.add("Largest positions (including cash):");
        positions.stream().limit(INSTRUMENT_LIMIT).forEach(p -> lines.add(String.format("  %-32s %18s  %s", p.getDescription(),
                        Values.Money.format(p.getValuation()), snapshot.getMonetaryAssets().isZero() ? "n/a"
                                        : String.format("%.1f%%", p.getShare() * 100))));
        var contributors = PerformerRanking.sortByCurrencyPerformance(
                        PerformerRanking.rank(client, converter, interval, index, -1));
        addContributors(lines, contributors, client.getBaseCurrency(), true);
        addContributors(lines, contributors, client.getBaseCurrency(), false);
        return List.copyOf(lines);
    }

    private static void addContributors(List<String> lines, List<PerformerRanking.Performer> contributors,
                    String currency, boolean positive)
    {
        lines.add(positive ? "Top contributors:" : "Top detractors:");
        var matching = contributors.stream().filter(p -> positive ? p.currencyPerformance() > 0
                        : p.currencyPerformance() < 0).toList();
        if (matching.isEmpty())
        {
            lines.add("  None");
            return;
        }

        int count = Math.min(INSTRUMENT_LIMIT, matching.size());
        for (int index = 0; index < count; index++)
        {
            int position = positive ? index : matching.size() - index - 1;
            var contributor = matching.get(position);
            lines.add(String.format("  %-32s %18s  %+.2f%%", contributor.name(),
                            Values.Money.format(Money.of(currency, contributor.currencyPerformance())),
                            contributor.currencyPerformancePercent() * 100));
        }
    }
}
