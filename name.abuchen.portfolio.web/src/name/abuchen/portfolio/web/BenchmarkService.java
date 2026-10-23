package name.abuchen.portfolio.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Quote;

/** Stored price-series growth, independently rebased to the selected opening valuation. */
@SuppressWarnings("nls")
final class BenchmarkService
{
    record Option(String uuid, String name, String currency, String firstQuoteDate, String lastQuoteDate)
    {
    }

    record Point(String date, double rate)
    {
    }

    record Result(String uuid, String name, String sourceCurrency, String reportingCurrency, String openingDate,
                    String closingDate, String openingQuoteDate, String closingQuoteDate, Double rate,
                    List<Point> series, List<String> warnings, String error)
    {
    }

    private BenchmarkService()
    {
    }

    static List<Option> options(Client client)
    {
        var options = new ArrayList<Option>();
        for (var security : client.getSecurities())
        {
            var prices = security.getPricesIncludingLatest();
            if (!prices.isEmpty())
                options.add(new Option(security.getUUID(), security.getName(), security.getCurrencyCode(),
                                prices.getFirst().getDate().toString(), prices.getLast().getDate().toString()));
        }
        options.sort(Comparator.comparing(Option::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Option::uuid));
        return List.copyOf(options);
    }

    static Result calculate(Client client, CurrencyConverter converter, LocalDate from, LocalDate to, String uuid)
    {
        if (uuid == null || uuid.isEmpty())
            return null;
        var security = client.getSecurities().stream().filter(s -> uuid.equals(s.getUUID())).findFirst().orElse(null);
        if (security == null)
            return unavailable(uuid, null, converter, from, to, "This benchmark is no longer in the portfolio. Choose another series.");

        var prices = security.getPricesIncludingLatest();
        var series = new ArrayList<Point>();
        var warnings = new ArrayList<String>();
        if (security.getCurrencyCode() == null)
            warnings.add("The series has no currency specified. It is shown in native index units without FX adjustment.");
        int cursor = -1;
        long openingValue = 0;
        String openingQuote = null;
        String closingQuote = null;
        boolean stale = false;
        for (var date = from; !date.isAfter(to); date = date.plusDays(1))
        {
            // Do not use Security.getSecurityPrice here: before inception it may select a future quote.
            while (cursor + 1 < prices.size() && !prices.get(cursor + 1).getDate().isAfter(date))
                cursor++;
            if (cursor < 0)
                return unavailable(uuid, security, converter, from, to, "No quote is available on or before the opening date. Choose a later start date.");
            var price = prices.get(cursor);
            long value = price.getValue();
            if (value <= 0)
                return unavailable(uuid, security, converter, from, to, "The selected period contains a zero or negative benchmark price.");
            if (security.getCurrencyCode() != null)
            {
                var converted = converter.convertIfAvailable(date, Quote.of(security.getCurrencyCode(), value));
                if (converted.isEmpty())
                    return unavailable(uuid, security, converter, from, to, "Exchange rates are missing for this period. The benchmark cannot be compared in "
                                    + converter.getTermCurrency() + ".");
                value = converted.get().getAmount();
                if (value <= 0)
                    return unavailable(uuid, security, converter, from, to, "The benchmark price could not be converted to a positive value.");
            }
            if (date.equals(from))
            {
                openingValue = value;
                openingQuote = price.getDate().toString();
            }
            closingQuote = price.getDate().toString();
            stale |= price.getDate().isBefore(date.minusDays(7));
            series.add(new Point(date.toString(), value / (double) openingValue - 1));
        }
        if (stale)
            warnings.add("Some benchmark points use quotes older than seven calendar days. Review the price history before comparing returns.");
        return new Result(uuid, security.getName(), security.getCurrencyCode(),
                        security.getCurrencyCode() == null ? null : converter.getTermCurrency(), from.toString(),
                        to.toString(), openingQuote, closingQuote, series.getLast().rate(), List.copyOf(series),
                        List.copyOf(warnings), null);
    }

    private static Result unavailable(String uuid, Security security, CurrencyConverter converter, LocalDate from,
                    LocalDate to, String error)
    {
        return new Result(uuid, security == null ? "Unavailable benchmark" : security.getName(),
                        security == null ? null : security.getCurrencyCode(),
                        security == null || security.getCurrencyCode() == null ? null : converter.getTermCurrency(),
                        from.toString(), to.toString(), null, null, null, List.of(), List.of(), error);
    }
}
