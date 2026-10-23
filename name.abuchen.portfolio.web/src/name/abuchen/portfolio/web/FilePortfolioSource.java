package name.abuchen.portfolio.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.model.ClientFactory;

/** Reads the source without writing it; only validated revisions become the recovery copy. */
@SuppressWarnings("nls")
final class FilePortfolioSource implements PortfolioSource
{
    private final Path path;
    private final Supplier<char[]> password;
    private final Path cache;

    FilePortfolioSource(Path path, Supplier<char[]> password, Path cache)
    {
        this.path = path;
        this.password = password;
        this.cache = cache;
    }

    @Override
    public String revision() throws IOException
    {
        return digest(path);
    }

    @Override
    public LoadedPortfolio load() throws IOException
    {
        return read(path);
    }

    private LoadedPortfolio read(Path input) throws IOException
    {
        var before = digest(input);
        var secret = password.get();
        try
        {
            var client = ClientFactory.load(input.toFile(), secret, new NullProgressMonitor());
            if (!before.equals(digest(input)))
                throw new IOException("Portfolio changed while loading");
            return new LoadedPortfolio(client, path.getFileName().toString(), before, Instant.now(), false);
        }
        finally
        {
            if (secret != null)
                Arrays.fill(secret, '\0');
        }
    }

    @Override
    public void accept(LoadedPortfolio loaded) throws IOException
    {
        if (cache == null)
            return;
        boolean posix = cache.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix)
            Files.createDirectories(cache, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else
            Files.createDirectories(cache);
        var temporary = Files.createTempFile(cache, "candidate-", ".portfolio");
        try
        {
            if (posix)
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try (var input = Files.newInputStream(path); var output = Files.newOutputStream(temporary))
            {
                input.transferTo(output);
            }
            if (!loaded.revision().equals(digest(temporary)) || !loaded.revision().equals(revision()))
                throw new IOException("Portfolio changed before acceptance");
            Files.move(temporary, recoveryPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public LoadedPortfolio lastGood() throws IOException
    {
        if (cache == null)
            return PortfolioSource.super.lastGood();
        var recovered = read(recoveryPath());
        return new LoadedPortfolio(recovered.client(), recovered.label(), recovered.revision(),
                        Files.getLastModifiedTime(recoveryPath()).toInstant(), false);
    }

    private Path recoveryPath()
    {
        var identity = sha256().digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        return cache.resolve("last-good-" + HexFormat.of().formatHex(identity) + ".portfolio");
    }

    private static String digest(Path path) throws IOException
    {
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(path), digest))
        {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
