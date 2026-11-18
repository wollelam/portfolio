package name.abuchen.portfolio.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientSnapshot;

final class DailyAttribution
{
    record Position(String uuid, String quoteCurrency, long shares, double openingPrice, Double closingPrice,
                    double openingFxRate, Double closingFxRate, String quoteDate)
    {
    }

    record Inputs(int positions, List<Position> eligible)
    {
    }

    private DailyAttribution()
    {
    }

    static Inputs compare(ClientSnapshot opening, ClientSnapshot closing)
    {
        var openingPositions = opening.getPositionsByVehicle();
        var eligible = new ArrayList<Position>();
        int positions = 0;
        for (var entry : closing.getPositionsByVehicle().entrySet())
        {
            var vehicle = entry.getKey();
            var latest = entry.getValue();
            if (!(vehicle instanceof Security) && latest.getPosition().calculateValue().isZero())
                continue;
            positions++;
            var earlier = openingPositions.get(vehicle);
            if (earlier == null || earlier.getPosition().getShares() != latest.getPosition().getShares())
                continue;

            var currency = vehicle.getCurrencyCode();
            var openingRate = rate(opening, currency);
            if (openingRate == null)
                continue;
            var closingRate = rate(closing, currency);
            if (vehicle instanceof Security security)
            {
                var openingQuote = security.getSecurityPrice(opening.getTime());
                if (openingQuote.getValue() <= 0 || openingQuote.getDate().isAfter(opening.getTime()))
                    continue;
                var closingQuote = security.getSecurityPrice(closing.getTime());
                boolean saved = closingQuote.getValue() > 0 && !closingQuote.getDate().isAfter(closing.getTime());
                eligible.add(new Position(security.getUUID(), currency, latest.getPosition().getShares(),
                                openingQuote.getValue() / Values.Quote.divider(),
                                saved ? closingQuote.getValue() / Values.Quote.divider() : null, openingRate, closingRate,
                                saved ? closingQuote.getDate().toString() : null));
            }
            else
            {
                var balance = latest.getPosition().calculateValue();
                if (balance.equals(earlier.getPosition().calculateValue()))
                    eligible.add(new Position(null, currency, Values.Share.factor(),
                                    balance.getAmount() / Values.Amount.divider(),
                                    balance.getAmount() / Values.Amount.divider(), openingRate, closingRate, null));
            }
        }
        return new Inputs(positions, List.copyOf(eligible));
    }

    private static Double rate(ClientSnapshot snapshot, String currency)
    {
        CurrencyConverter converter = snapshot.getCurrencyConverter();
        LocalDate date = snapshot.getTime();
        return currency == null ? null : converter.getRateIfAvailable(date, currency)
                        .map(rate -> rate.getValue().doubleValue())
                        .filter(value -> Double.isFinite(value) && value > 0).orElse(null);
    }
}
