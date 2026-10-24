package name.abuchen.portfolio.web;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyAccount;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyClient;

/** Recognizes a reviewed, balanced non-cash spin-off without hiding ordinary cash dividends. */
@SuppressWarnings("nls")
final class NonCashDistributions
{
    static final String PREFIX = "PP-NONCASH-SPINOFF:";

    private NonCashDistributions()
    {
    }

    static boolean isSpinOff(Client client, Account account, AccountTransaction transaction)
    {
        var note = transaction.getNote();
        if (transaction.getType() != AccountTransaction.Type.DIVIDENDS || note == null || !note.startsWith(PREFIX)
                        || note.length() < PREFIX.length() + 36)
            return false;
        if (client instanceof ReadOnlyClient)
        {
            // Native filters recreate/scale transactions and give wrappers fresh IDs.
            // Validate the marked original before classifying its derived copy.
            var originalClient = ReadOnlyClient.unwrap(client);
            var originalAccount = ReadOnlyAccount.unwrap(account);
            return originalAccount.getTransactions().stream().filter(original ->
                            original.getType() == transaction.getType() && note.equals(original.getNote())
                                            && original.getDateTime().equals(transaction.getDateTime())
                                            && java.util.Objects.equals(original.getSecurity(), transaction.getSecurity())
                                            && original.getCurrencyCode().equals(transaction.getCurrencyCode()))
                            .anyMatch(original -> isSpinOff(originalClient, originalAccount, original));
        }
        var uuid = note.substring(PREFIX.length(), PREFIX.length() + 36);
        if (!note.startsWith(PREFIX + uuid + " distribution (non-cash)"))
            return false;
        return client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream()).anyMatch(purchase ->
                        uuid.equals(purchase.getUUID()) && purchase.getType() == PortfolioTransaction.Type.BUY
                                        && purchase.getCrossEntry() instanceof BuySellEntry pair && pair.getAccount() == account
                                        && purchase.getMonetaryAmount().equals(transaction.getMonetaryAmount())
                                        && purchase.getDateTime().equals(transaction.getDateTime())
                                        && purchase.getNote() != null && purchase.getNote().startsWith(PREFIX + uuid + " acquisition"));
    }
}
