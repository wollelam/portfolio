package name.abuchen.portfolio.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit.Type;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;

/** Read-only booked security activity, with the same opening-exclusive period as performance. */
final class SecurityActivityService
{
    record Payment(String uuid, String date, String account, String currency, long gross, long taxes, long fees,
                    long net, Long reportingNet)
    {
    }

    record Entry(String uuid, String date, String type, String owner, Long shares, String currency, long amount,
                    long taxes, long fees, Long reportingAmount)
    {
    }

    record Activity(List<Payment> dividends, List<Entry> transactions)
    {
    }

    private SecurityActivityService()
    {
    }

    static Activity empty()
    {
        return new Activity(List.of(), List.of());
    }

    static Activity calculate(Client client, Security security, CurrencyConverter converter, LocalDate from,
                    LocalDate to)
    {
        var dividends = new ArrayList<Payment>();
        var transactions = new ArrayList<Entry>();
        // The native lookup returns portfolio trades and related account activity without the cash-side trade copies.
        var pairs = security.getTransactions(client).stream().sorted(TransactionPair.BY_DATE.reversed()).toList();
        for (var pair : pairs)
        {
            var transaction = pair.getTransaction();
            var date = transaction.getDateTime().toLocalDate();
            if (!date.isAfter(from) || date.isAfter(to))
                continue;

            String owner = pair.getOwner() instanceof Account account ? account.getName()
                            : ((Portfolio) pair.getOwner()).getName();
            boolean nonCash = transaction instanceof AccountTransaction at && pair.getOwner() instanceof Account cashOwner
                            && NonCashDistributions.isSpinOff(client, cashOwner, at);
            String type = nonCash ? "Spin-off distribution (non-cash)" : transaction instanceof AccountTransaction account ? account.getType().toString()
                            : ((PortfolioTransaction) transaction).getType().toString();
            long taxes = transaction.getUnitSum(Type.TAX).getAmount();
            long fees = transaction.getUnitSum(Type.FEE).getAmount();
            var reportingAmount = reportingAmount(converter, date, transaction.getMonetaryAmount());
            transactions.add(new Entry(transaction.getUUID(), transaction.getDateTime().toString(), type, owner,
                            transaction.getShares() == 0 ? null : transaction.getShares(), transaction.getCurrencyCode(),
                            transaction.getAmount(), taxes, fees, reportingAmount));

            if (!nonCash && transaction instanceof AccountTransaction account && account.getType() == AccountTransaction.Type.DIVIDENDS)
            {
                dividends.add(new Payment(transaction.getUUID(), date.toString(), owner, transaction.getCurrencyCode(),
                                transaction.getGrossValue().getAmount(), taxes, fees, transaction.getAmount(), reportingAmount));
            }
        }
        return new Activity(List.copyOf(dividends), List.copyOf(transactions));
    }

    private static Long reportingAmount(CurrencyConverter converter, LocalDate date, Money amount)
    {
        if (!amount.isZero() && !amount.getCurrencyCode().equals(converter.getTermCurrency())
                        && converter.getRateIfAvailable(date, amount.getCurrencyCode()).isEmpty())
            return null;
        return converter.convert(date, amount).getAmount();
    }
}
