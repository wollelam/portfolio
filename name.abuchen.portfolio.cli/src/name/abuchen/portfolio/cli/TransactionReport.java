package name.abuchen.portfolio.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.TransactionOwner;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.util.Interval;

/** Renders a complete, filterable transaction ledger for a client. */
public final class TransactionReport
{
    private record Entry(String owner, String ownerType, String counterparty, Transaction transaction)
    {
    }

    private TransactionReport()
    {
    }

    /**
     * Renders transactions in {@code interval}. Empty or {@code null} filters
     * match every value; security and owner filters are case-insensitive
     * substrings. Type filters are exact transaction-type names (or the
     * singular {@code dividend} alias).
     */
    public static List<String> render(Client client, Interval interval, String securityFilter, String ownerFilter,
                    String typeFilter)
    {
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), client.getBaseCurrency());
        var entries = new ArrayList<Entry>();
        String normalizedTypeFilter = normalizedTypeFilter(typeFilter);

        for (Account account : client.getAccounts())
            for (AccountTransaction transaction : account.getTransactions())
                if (!isLinkedAccountBuyOrSell(transaction))
                    entries.add(new Entry(value(account.getName()), "Account", counterparty(transaction), //$NON-NLS-1$
                                    transaction));
        for (Portfolio portfolio : client.getPortfolios())
            for (PortfolioTransaction transaction : portfolio.getTransactions())
                entries.add(new Entry(value(portfolio.getName()), "Portfolio", counterparty(transaction), //$NON-NLS-1$
                                transaction));

        entries.removeIf(entry -> !interval.contains(entry.transaction().getDateTime())
                        || !matches(securityFilterText(entry.transaction()), securityFilter)
                        || !matches(entry.owner() + " " + entry.counterparty(), ownerFilter) //$NON-NLS-1$
                        || !matchesType(type(entry.transaction()), normalizedTypeFilter));
        entries.sort(Comparator.comparing((Entry entry) -> entry.transaction().getDateTime())
                        .thenComparing(Entry::owner)
                        .thenComparing(Entry::ownerType).thenComparing(entry -> type(entry.transaction()))
                        .thenComparing(entry -> security(entry.transaction()))
                        .thenComparingLong(entry -> entry.transaction().getAmount()));

        var lines = new ArrayList<String>();
        lines.add("Transaction ledger " + interval.getStart() + " to " + interval.getEnd() //$NON-NLS-1$ //$NON-NLS-2$
                        + ":"); //$NON-NLS-1$
        lines.add("Owner | Owner type | Counterparty | Date | Type | Security | Shares | Original | Base" //$NON-NLS-1$
                        + " | Fees | Taxes | Note"); //$NON-NLS-1$
        if (entries.isEmpty())
            lines.add("No transactions found."); //$NON-NLS-1$
        else
            entries.forEach(entry -> lines.add(render(entry, converter)));
        lines.add("Count: " + entries.size()); //$NON-NLS-1$
        return lines;
    }

    private static boolean isLinkedAccountBuyOrSell(AccountTransaction transaction)
    {
        return transaction.getCrossEntry() != null && (transaction.getType() == AccountTransaction.Type.BUY
                        || transaction.getType() == AccountTransaction.Type.SELL);
    }

    private static String render(Entry entry, CurrencyConverter converter)
    {
        Transaction transaction = entry.transaction();
        return String.join(" | ", entry.owner(), entry.ownerType(), entry.counterparty(), //$NON-NLS-1$
                        transaction.getDateTime().toLocalDate().toString(), type(transaction), security(transaction),
                        shares(transaction), CliFormatter.money(transaction.getMonetaryAmount()),
                        CliFormatter.money(baseAmount(transaction, converter)),
                        CliFormatter.money(transaction.getUnitSum(Transaction.Unit.Type.FEE)),
                        CliFormatter.money(transaction.getUnitSum(Transaction.Unit.Type.TAX)), note(transaction));
    }

    private static Money baseAmount(Transaction transaction, CurrencyConverter converter)
    {
        if (transaction instanceof PortfolioTransaction portfolioTransaction)
            return portfolioTransaction.getMonetaryAmount(converter);
        return converter.convert(transaction.getDateTime(), transaction.getMonetaryAmount());
    }

    private static String type(Transaction transaction)
    {
        return transaction instanceof AccountTransaction accountTransaction ? accountTransaction.getType().name()
                        : ((PortfolioTransaction) transaction).getType().name();
    }

    private static String security(Transaction transaction)
    {
        return transaction.getSecurity() == null ? "" : value(transaction.getSecurity().getName()); //$NON-NLS-1$
    }

    private static String securityFilterText(Transaction transaction)
    {
        Security security = transaction.getSecurity();
        if (security == null)
            return ""; //$NON-NLS-1$
        return String.join(" ", value(security.getName()), value(security.getIsin()), //$NON-NLS-1$
                        value(security.getWkn()), value(security.getTickerSymbol()));
    }

    private static String counterparty(Transaction transaction)
    {
        if (transaction.getCrossEntry() == null)
            return ""; //$NON-NLS-1$

        TransactionOwner<? extends Transaction> owner = transaction.getCrossEntry().getCrossOwner(transaction);
        if (owner instanceof Account account)
            return value(account.getName());
        if (owner instanceof Portfolio portfolio)
            return value(portfolio.getName());
        return ""; //$NON-NLS-1$
    }

    private static String shares(Transaction transaction)
    {
        return transaction.getShares() == 0 ? "" : CliFormatter.share(transaction.getShares()); //$NON-NLS-1$
    }

    private static String note(Transaction transaction)
    {
        return transaction.getNote() == null ? "" : transaction.getNote(); //$NON-NLS-1$
    }

    private static boolean matches(String value, String filter)
    {
        return filter == null || filter.isBlank()
                        || value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesType(String type, String normalizedFilter)
    {
        return normalizedFilter == null || type.equals(normalizedFilter);
    }

    private static String normalizedTypeFilter(String typeFilter)
    {
        if (typeFilter == null || typeFilter.isBlank())
            return null;

        String result = typeFilter.strip().toUpperCase(Locale.ROOT);
        if ("DIVIDEND".equals(result)) //$NON-NLS-1$
            result = "DIVIDENDS"; //$NON-NLS-1$

        if (!transactionTypes().contains(result))
            throw new IllegalArgumentException("Unknown transaction type: " + typeFilter); //$NON-NLS-1$
        return result;
    }

    private static Set<String> transactionTypes()
    {
        var result = new HashSet<String>();
        for (AccountTransaction.Type type : AccountTransaction.Type.values())
            result.add(type.name());
        for (PortfolioTransaction.Type type : PortfolioTransaction.Type.values())
            result.add(type.name());
        return result;
    }

    private static String value(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
