package name.abuchen.portfolio.web;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Supplier;

import name.abuchen.portfolio.model.Client;

/** A source and optional durable recovery copy, independent of HTTP hosting. */
@FunctionalInterface
interface PortfolioSource
{
    record LoadedPortfolio(Client client, String label, String revision, Instant loadedAt, boolean demo)
    {
    }

    LoadedPortfolio load() throws IOException;

    default String revision() throws IOException
    {
        return null;
    }

    default void accept(LoadedPortfolio loaded) throws IOException
    {
        // Sources without a recovery copy need no persistence.
    }

    default LoadedPortfolio lastGood() throws IOException
    {
        throw new IOException("No recovery snapshot available"); //$NON-NLS-1$
    }

    static PortfolioSource file(Path path, char[] password)
    {
        return file(path, () -> password == null ? null : password.clone(), null);
    }

    static PortfolioSource file(Path path, Supplier<char[]> password, Path cache)
    {
        return new FilePortfolioSource(path, password, cache);
    }
}
