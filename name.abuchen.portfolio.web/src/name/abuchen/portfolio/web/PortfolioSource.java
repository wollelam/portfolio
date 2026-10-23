package name.abuchen.portfolio.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;

/** Source boundary for a future watched file or remote snapshot provider. */
@FunctionalInterface
interface PortfolioSource
{
    record LoadedPortfolio(Client client, String label, String revision, Instant loadedAt, boolean demo)
    {
    }

    LoadedPortfolio load() throws IOException;

    static PortfolioSource file(Path path, char[] password)
    {
        return () -> {
            var before = revision(path);
            var client = ClientFactory.load(path.toFile(), password, new NullProgressMonitor());
            if (!before.equals(revision(path)))
                throw new IOException("Portfolio changed while loading; retry after the save completes."); //$NON-NLS-1$
            return new LoadedPortfolio(client, path.getFileName().toString(), before, Instant.now(), false);
        };
    }

    private static String revision(Path path) throws IOException
    {
        try
        {
            var digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            try (var input = new DigestInputStream(Files.newInputStream(path), digest))
            {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
