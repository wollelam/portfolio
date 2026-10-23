package name.abuchen.portfolio.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

final class PrivateFiles
{
    private PrivateFiles()
    {
    }

    static void directory(Path directory) throws IOException
    {
        Files.createDirectories(directory);
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) //$NON-NLS-1$
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); //$NON-NLS-1$
    }

    static void write(Path file, byte[] content) throws IOException
    {
        directory(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), "pending-", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(temporary, content);
            promote(temporary, file);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    static void promote(Path source, Path target) throws IOException
    {
        if (source.getFileSystem().supportedFileAttributeViews().contains("posix")) //$NON-NLS-1$
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------")); //$NON-NLS-1$
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
