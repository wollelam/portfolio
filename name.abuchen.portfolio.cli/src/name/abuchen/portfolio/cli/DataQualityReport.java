package name.abuchen.portfolio.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;

/** Quote, exchange-rate, and performance-calculation diagnostics. */
@SuppressWarnings("nls")
public final class DataQualityReport
{
    private DataQualityReport()
    {
    }

    public static List<String> render(Client client, Interval interval)
    {
        var lines = new ArrayList<String>();
        var warnings = new ArrayList<Exception>();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), client.getBaseCurrency());
        var positions = ClientSnapshot.create(client, converter, interval.getEnd()).getAssetPositions().toList();

        lines.add("DATA QUALITY  " + interval.getStart() + " to " + interval.getEnd());
        lines.add("Quotes older than 7 calendar days are considered stale.");
        int beforeChecks = lines.size();
        for (var position : positions)
        {
            var security = position.getSecurity();
            if (security == null)
                continue;
            var quote = security.getPricesIncludingLatest().stream()
                            .filter(p -> !p.getDate().isAfter(interval.getEnd()))
                            .max(Comparator.comparing(p -> p.getDate()));
            if (quote.isEmpty())
                lines.add("  Missing quote: " + security.getName());
            else if (quote.get().getDate().isBefore(interval.getEnd().minusDays(7)))
                lines.add("  Stale quote: " + security.getName() + " (" + quote.get().getDate() + ")");
        }

        var currencies = positions.stream().map(p -> p.getInvestmentVehicle().getCurrencyCode())
                        .filter(c -> c != null).distinct().sorted().toList();
        for (String currency : currencies)
        {
            if (converter.getRateIfAvailable(interval.getEnd(), currency).isEmpty())
                lines.add("  Missing FX: " + currency + " -> " + client.getBaseCurrency()
                                + "; valuations may use fallback rates");
        }
        if (lines.size() == beforeChecks)
            lines.add("  No missing FX or stale/missing held-security quotes at period end.");

        PerformanceIndex.forClient(client, converter, interval, warnings);
        if (!warnings.isEmpty())
            lines.add("Performance calculation warnings: " + warnings.size());
        return List.copyOf(lines);
    }
}
