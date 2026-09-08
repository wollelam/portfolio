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
        return renderReport(client, interval).lines().stream().map(CliLine::text).toList();
    }

    static Report renderReport(Client client, Interval interval)
    {
        var warnings = new ArrayList<Exception>();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), client.getBaseCurrency());
        var performance = new ClientPerformanceSnapshot(client, converter, interval);
        var snapshot = performance.getEndClientSnapshot();
        var index = PerformanceIndex.forClient(client, converter, interval, warnings);
        var positions = snapshot.getAssetPositions().sorted(Comparator.comparing(AssetPosition::getValuation).reversed())
                        .toList();
        long cash = positions.stream().filter(p -> p.getSecurity() == null)
                        .mapToLong(p -> p.getValuation().getAmount()).sum();
        var output = new ArrayList<CliLine>();
        output.add(CliLine.plain("PORTFOLIO SUMMARY  " + interval.getStart() + " to " + interval.getEnd()));
        output.add(CliLine.plain("Base currency: " + client.getBaseCurrency() + " | valuation date: " + interval.getEnd()));
        output.add(CliLine.plain("Total value       " + CliFormatter.money(snapshot.getMonetaryAssets())));
        output.add(CliLine.plain(CliFormatter.format("Return (TTWROR)   %+.2f%%", index.getFinalAccumulatedPercentage() * 100)));
        output.add(CliLine.plain("Return (IRR, annualized) " + CliFormatter.irr(index.getPerformanceIRR())));
        output.add(CliLine.plain("Performance       " + CliFormatter.money(performance.getAbsoluteDelta())));
        output.add(CliLine.plain("Net deposits      " + CliFormatter.money(performance.getValue(CategoryType.TRANSFERS))));
        output.add(CliLine.plain("Cash              " + CliFormatter.money(Money.of(client.getBaseCurrency(), cash))));
        output.add(CliLine.plain("Earnings          " + CliFormatter.money(performance.getValue(CategoryType.EARNINGS))));
        output.add(CliLine.plain("Fees / taxes      " + CliFormatter.money(performance.getValue(CategoryType.FEES)) + " / "
                        + CliFormatter.money(performance.getValue(CategoryType.TAXES))));
        output.add(CliLine.plain("Largest positions (including cash):"));
        positions.stream().limit(INSTRUMENT_LIMIT).forEach(p -> output.add(CliLine.plain(CliFormatter.format("  %-32s %18s  %s",
                        abbreviate(p.getDescription(), 32), CliFormatter.money(p.getValuation()),
                        snapshot.getMonetaryAssets().isZero() ? "n/a"
                                        : CliFormatter.format("%.1f%%", p.getShare() * 100)))));
        var contributors = PerformerRanking.sortByCurrencyPerformance(
                        PerformerRanking.rank(client, converter, interval, index, -1));
        long totalPerformance = performance.getAbsoluteDelta().getAmount();
        double portfolioReturn = index.getFinalAccumulatedPercentage();
        addContributors(output, contributors, client.getBaseCurrency(), totalPerformance, portfolioReturn, true);
        addContributors(output, contributors, client.getBaseCurrency(), totalPerformance, portfolioReturn, false);
        return new Report(List.copyOf(output));
    }

    private static void addContributors(List<CliLine> lines, List<PerformerRanking.Performer> contributors,
                    String currency, long totalPerformance, double portfolioReturn, boolean positive)
    {
        lines.add(CliLine.plain(positive ? "Top contributors:" : "Top detractors:"));
        lines.add(CliLine.plain(CliFormatter.format("  %-32s %16s %10s %10s %18s %10s", "Instrument", "Quote", "Return",
                        "IRR p.a.", "Contribution", "Impact")));
        var matching = contributors.stream().filter(p -> positive ? p.currencyPerformance() > 0
                        : p.currencyPerformance() < 0).toList();
        if (matching.isEmpty())
        {
            lines.add(CliLine.plain("  None"));
            return;
        }

        int count = Math.min(INSTRUMENT_LIMIT, matching.size());
        for (int index = 0; index < count; index++)
        {
            int position = positive ? index : matching.size() - index - 1;
            var contributor = matching.get(position);
            double impactValue = portfolioImpactValue(contributor.currencyPerformance(), totalPerformance, portfolioReturn);
            String formattedReturn = percent(contributor.currencyPerformancePercent());
            String formattedIrr = CliFormatter.irr(contributor.irr());
            String formattedContribution = signedMoney(Money.of(currency, contributor.currencyPerformance()));
            String impact = portfolioImpact(impactValue);
            lines.add(CliLine.builder().append("  ").appendLeft(abbreviate(contributor.name(), 32), 32).append(" ") //$NON-NLS-1$ //$NON-NLS-2$
                            .appendRight(contributor.quote(), 16).append(" ") //$NON-NLS-1$
                            .appendValue(formattedReturn, 10, contributor.currencyPerformancePercent(), CliLine.Metric.RETURN)
                            .append(" ") //$NON-NLS-1$
                            .appendValue(formattedIrr, 10, contributor.irr(), CliLine.Metric.IRR).append(" ") //$NON-NLS-1$
                            .appendValue(formattedContribution, 18, contributor.currencyPerformance(), CliLine.Metric.CONTRIBUTION)
                            .append(" ").appendValue(impact, 10, impactValue, CliLine.Metric.IMPACT).build()); //$NON-NLS-1$
        }
    }

    static String portfolioImpact(long contribution, long totalPerformance, double portfolioReturn)
    {
        return portfolioImpact(portfolioImpactValue(contribution, totalPerformance, portfolioReturn));
    }

    private static String portfolioImpact(double value)
    {
        if (!Double.isFinite(value))
            return "n/a";
        return CliFormatter.format("%+.2f pp", value * 100);
    }

    private static double portfolioImpactValue(long contribution, long totalPerformance, double portfolioReturn)
    {
        if (totalPerformance == 0)
            return Double.NaN;
        return contribution / (double) totalPerformance * portfolioReturn;
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

    record Report(List<CliLine> lines)
    {
    }
}
