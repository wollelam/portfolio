package name.abuchen.portfolio.cli;

import java.io.IOException;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.money.ExchangeRateProvider;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;

/** Loads and refreshes the exchange-rate providers used by the CLI. */
final class ExchangeRateCache
{
    private final List<ExchangeRateProvider> providers;
    private boolean loaded;

    ExchangeRateCache()
    {
        this(ExchangeRateProviderFactory.getProviders());
    }

    ExchangeRateCache(List<? extends ExchangeRateProvider> providers)
    {
        this.providers = List.copyOf(providers);
    }

    static ExchangeRateCache disabled()
    {
        return new ExchangeRateCache(List.of());
    }

    synchronized void load() throws IOException
    {
        if (loaded)
            return;

        // Mark the cache as loaded even if one provider fails. This mirrors the
        // desktop lifecycle: a failed local load must not prevent an online
        // refresh from repairing the cache during QUPD.
        loaded = true;

        IOException failure = null;
        for (ExchangeRateProvider provider : providers)
        {
            try
            {
                provider.load(new NullProgressMonitor());
            }
            catch (Exception e)
            {
                failure = combine(failure, asIOException("load", provider, e)); //$NON-NLS-1$
            }
        }

        if (failure != null)
            throw failure;
    }

    synchronized void refresh() throws IOException
    {
        IOException failure = null;
        try
        {
            load();
        }
        catch (IOException e)
        {
            // Continue with update/save, just as the desktop job does after a
            // failed local load.
            failure = e;
        }

        for (ExchangeRateProvider provider : providers)
        {
            try
            {
                provider.update(new NullProgressMonitor());
            }
            catch (IOException e)
            {
                failure = combine(failure, asIOException("update", provider, e)); //$NON-NLS-1$
            }
        }

        for (ExchangeRateProvider provider : providers)
        {
            try
            {
                provider.save(new NullProgressMonitor());
            }
            catch (IOException e)
            {
                failure = combine(failure, asIOException("save", provider, e)); //$NON-NLS-1$
            }
        }

        if (failure != null)
            throw failure;
    }

    private IOException asIOException(String operation, ExchangeRateProvider provider, Exception cause)
    {
        return new IOException("Could not " + operation + " exchange rates from " + provider.getName(), cause); //$NON-NLS-1$
    }

    private IOException combine(IOException first, IOException next)
    {
        if (first == null)
            return next;

        first.addSuppressed(next);
        return first;
    }
}
