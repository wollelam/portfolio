package name.abuchen.portfolio.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityEvent;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;

/** Native investment return series and booked events for an instrument chart. */
@SuppressWarnings("nls")
final class InstrumentChartService
{
    record Data(List<DashboardService.ReturnPoint> totalReturn, List<DashboardService.ChartEvent> events)
    {
    }

    private InstrumentChartService()
    {
    }

    static Data calculate(Client client, Security security, CurrencyConverter converter, LocalDate from, LocalDate to,
                    boolean hasPerformanceRecord, boolean heldAtStart, boolean heldAtEnd)
    {
        return new Data(totalReturn(client, security, converter, from, to, hasPerformanceRecord, heldAtStart, heldAtEnd),
                        events(client, security, from, to));
    }

    private static List<DashboardService.ReturnPoint> totalReturn(Client client, Security security,
                    CurrencyConverter converter, LocalDate from, LocalDate to, boolean hasPerformanceRecord,
                    boolean heldAtStart, boolean heldAtEnd)
    {
        if (!hasPerformanceRecord || !SecurityRankingService.pricesAvailable(security, from, to, heldAtStart, heldAtEnd))
            return List.of();

        try
        {
            var failures = new ArrayList<Exception>();
            var index = PerformanceIndex.forInvestment(client, converter, security, Interval.of(from, to), failures);
            if (!failures.isEmpty())
                return List.of();
            var dates = index.getDates();
            var values = index.getAccumulatedPercentage();
            if (dates.length != values.length)
                return List.of();
            var result = new ArrayList<DashboardService.ReturnPoint>(dates.length);
            for (int i = 0; i < dates.length; i++)
            {
                if (!Double.isFinite(values[i]))
                    return List.of();
                result.add(new DashboardService.ReturnPoint(dates[i].toString(), values[i]));
            }
            return List.copyOf(result);
        }
        catch (RuntimeException e)
        {
            return List.of();
        }
    }

    private static List<DashboardService.ChartEvent> events(Client client, Security security, LocalDate from, LocalDate to)
    {
        var result = new ArrayList<DashboardService.ChartEvent>();
        var acquisitions = new HashMap<String, PortfolioTransaction>();
        var nonCash = new ArrayList<AccountTransaction>();

        for (var account : client.getAccounts())
        {
            for (var transaction : account.getTransactions())
            {
                if (transaction.getType() == AccountTransaction.Type.DIVIDENDS
                                && NonCashDistributions.isSpinOff(client, account, transaction))
                {
                    nonCash.add(transaction);
                    String id = acquisitionId(transaction.getNote());
                    if (id != null)
                        client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                                        .filter(p -> p.getType() == PortfolioTransaction.Type.BUY
                                                        && hasAcquisitionNote(p.getNote(), id)
                                                        && p.getDateTime().equals(transaction.getDateTime())
                                                        && p.getMonetaryAmount().equals(transaction.getMonetaryAmount()))
                                        .findFirst().ifPresent(p -> acquisitions.put(id, p));
                }
                else if (security.equals(transaction.getSecurity()) && transaction.getType() == AccountTransaction.Type.DIVIDENDS
                                && inRange(transaction.getDateTime().toLocalDate(), from, to))
                {
                    long gross = transaction.getGrossValue().getAmount();
                    long net = transaction.getAmount();
                    long taxes = transaction.getUnitSum(Transaction.Unit.Type.TAX).getAmount();
                    long fees = transaction.getUnitSum(Transaction.Unit.Type.FEE).getAmount();
                    String label = "Dividend payment · gross " + money(gross, transaction.getCurrencyCode()) + " · net "
                                    + money(net, transaction.getCurrencyCode()) + " · tax " + money(taxes, transaction.getCurrencyCode())
                                    + " · fee " + money(fees, transaction.getCurrencyCode());
                    result.add(new DashboardService.ChartEvent(transaction.getDateTime().toLocalDate().toString(), "dividend", label));
                }
            }
        }

        Set<String> acquisitionTransactionUuids = new HashSet<>();
        for (var distribution : nonCash)
        {
            if (security.equals(distribution.getSecurity())
                            && inRange(distribution.getDateTime().toLocalDate(), from, to))
                result.add(new DashboardService.ChartEvent(distribution.getDateTime().toLocalDate().toString(), "corporate",
                                "Spin-off distribution (non-cash)"));
        }
        acquisitions.forEach((id, transaction) -> {
            acquisitionTransactionUuids.add(transaction.getUUID());
            var distribution = nonCash.stream().filter(t -> id.equals(acquisitionId(t.getNote()))).findFirst().orElse(null);
            if (distribution != null && security.equals(transaction.getSecurity())
                            && inRange(distribution.getDateTime().toLocalDate(), from, to))
                result.add(new DashboardService.ChartEvent(distribution.getDateTime().toLocalDate().toString(), "corporate",
                                "Spin-off acquisition · " + Values.Share.format(transaction.getShares()) + " shares"));
        });

        for (var pair : security.getTransactions(client))
        {
            if (!(pair.getOwner() instanceof Portfolio) || !(pair.getTransaction() instanceof PortfolioTransaction transaction))
                continue;
            var date = transaction.getDateTime().toLocalDate();
            if (!inRange(date, from, to))
                continue;
            if (acquisitionTransactionUuids.contains(transaction.getUUID()))
                continue;
            String kind = switch (transaction.getType())
            {
                case BUY -> "buy";
                case SELL -> "sell";
                default -> null;
            };
            if (kind != null)
            {
                result.add(new DashboardService.ChartEvent(date.toString(), kind,
                                transaction.getType().name() + " · " + Values.Share.format(transaction.getShares())
                                                + " shares · " + money(transaction.getAmount(), transaction.getCurrencyCode())));
            }
        }

        for (SecurityEvent event : security.getEvents())
        {
            if (event.getDate() == null || !inRange(event.getDate(), from, to))
                continue;
            if (event.getType() == SecurityEvent.Type.STOCK_SPLIT)
                result.add(new DashboardService.ChartEvent(event.getDate().toString(), "corporate",
                                "Stock split" + details(event.getDetails())));
            else if (event.getType() == SecurityEvent.Type.NOTE)
                result.add(new DashboardService.ChartEvent(event.getDate().toString(), "corporate",
                                "Note: " + event.getDetails()));
        }

        result.sort(Comparator.comparing(DashboardService.ChartEvent::date));
        return List.copyOf(result);
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to)
    {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    private static String money(long amount, String currency)
    {
        return currency + " " + Values.Amount.format(amount);
    }

    private static String details(String value)
    {
        return value == null || value.isBlank() ? "" : " · " + value;
    }

    private static String acquisitionId(String note)
    {
        if (note == null || !note.startsWith(NonCashDistributions.PREFIX)
                        || note.length() < NonCashDistributions.PREFIX.length() + 36)
            return null;
        return note.substring(NonCashDistributions.PREFIX.length(), NonCashDistributions.PREFIX.length() + 36);
    }

    private static boolean hasAcquisitionNote(String note, String id)
    {
        return note != null && note.startsWith(NonCashDistributions.PREFIX + id + " acquisition");
    }
}
