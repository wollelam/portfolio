package name.abuchen.portfolio.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
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
        lines.add("Total value       " + CliFormatter.money(snapshot.getMonetaryAssets()));
        lines.add(CliFormatter.format("Return (TTWROR)   %+.2f%%", index.getFinalAccumulatedPercentage() * 100));
        lines.add("Return (IRR, annualized) " + percent(index.getPerformanceIRR()));
        lines.add("Performance       " + CliFormatter.money(performance.getAbsoluteDelta()));
        lines.add("Net deposits      " + CliFormatter.money(performance.getValue(CategoryType.TRANSFERS)));
        lines.add("Cash              " + CliFormatter.money(Money.of(client.getBaseCurrency(), cash)));
        lines.add("Earnings          " + CliFormatter.money(performance.getValue(CategoryType.EARNINGS)));
        lines.add("Fees / taxes      " + CliFormatter.money(performance.getValue(CategoryType.FEES)) + " / "
                        + CliFormatter.money(performance.getValue(CategoryType.TAXES)));
        lines.add("Largest positions (including cash):");
        positions.stream().limit(INSTRUMENT_LIMIT).forEach(p -> lines.add(CliFormatter.format("  %-32s %18s  %s",
                        abbreviate(p.getDescription(), 32), CliFormatter.money(p.getValuation()),
                        snapshot.getMonetaryAssets().isZero() ? "n/a"
                                        : CliFormatter.format("%.1f%%", p.getShare() * 100))));
        var contributors = PerformerRanking.sortByCurrencyPerformance(
                        PerformerRanking.rank(client, converter, interval, index, -1));
        long totalPerformance = performance.getAbsoluteDelta().getAmount();
        double portfolioReturn = index.getFinalAccumulatedPercentage();
        addContributors(lines, contributors, client.getBaseCurrency(), totalPerformance, portfolioReturn, true);
        addContributors(lines, contributors, client.getBaseCurrency(), totalPerformance, portfolioReturn, false);
        return List.copyOf(lines);
    }

    private static void addContributors(List<String> lines, List<PerformerRanking.Performer> contributors,
                    String currency, long totalPerformance, double portfolioReturn, boolean positive)
    {
        lines.add(positive ? "Top contributors:" : "Top detractors:");
        lines.add(CliFormatter.format("  %-32s %10s %10s %18s %10s", "Instrument", "Return", "IRR p.a.",
                        "Contribution", "Impact"));
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
            String impact = portfolioImpact(contributor.currencyPerformance(), totalPerformance, portfolioReturn);
            lines.add(CliFormatter.format("  %-32s %10s %10s %18s %10s", abbreviate(contributor.name(), 32),
                            percent(contributor.currencyPerformancePercent()), percent(contributor.irr()),
                            signedMoney(Money.of(currency, contributor.currencyPerformance())), impact));
        }
    }

    static String portfolioImpact(long contribution, long totalPerformance, double portfolioReturn)
    {
        if (totalPerformance == 0)
            return "n/a";
        return CliFormatter.format("%+.2f pp", contribution / (double) totalPerformance * portfolioReturn * 100);
    }

    private static String percent(double value)
    {
        return Double.isFinite(value) ? CliFormatter.format("%+.2f%%", value * 100) : "n/a";
    }

    private static String signedMoney(Money value)
    {
        String formatted = CliFormatter.money(value);
        return value.isPositive() ? "+" + formatted : formatted;
    }

    private static String abbreviate(String value, int width)
    {
        return value.length() <= width ? value : value.substring(0, width - 1) + "…";
    }
}
