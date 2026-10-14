package name.abuchen.portfolio.ui.editor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.zip.ZipFile;

/* package */ final class FileContentSnapshot
{
    private static final byte[] ENCRYPTED_SIGNATURE = "PORTFOLIO".getBytes(StandardCharsets.US_ASCII); //$NON-NLS-1$
    private static final byte[] ZIP_SIGNATURE = { 80, 75, 3, 4 };
    private static final int ENCRYPTED_HEADER_LENGTH = 25;

    private FileContentSnapshot()
    {
    }

    static byte[] read(Path path) throws IOException
    {
        // Plain XML needs no content reads at all.
        if (path.getFileName().toString().endsWith(".xml")) //$NON-NLS-1$
            return readAttributes(path);

        byte[] header;
        try (var input = Files.newInputStream(path))
        {
            header = input.readNBytes(ENCRYPTED_HEADER_LENGTH);
        }
        if (startsWith(header, ENCRYPTED_SIGNATURE))
        {
            if (header.length != ENCRYPTED_HEADER_LENGTH)
                throw new IOException("Incomplete encrypted portfolio header"); //$NON-NLS-1$
            // Includes the initialization vector, freshly generated on every save.
            return header;
        }
        if (startsWith(header, ZIP_SIGNATURE))
            return readZipDirectory(path);

        // Includes XML files with a nonstandard extension.
        return readAttributes(path);
    }

    private static boolean startsWith(byte[] value, byte[] prefix)
    {
        return value.length >= prefix.length && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] readAttributes(Path path) throws IOException
    {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return (attributes.size() + ":" + attributes.lastModifiedTime()).getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
    }

    private static byte[] readZipDirectory(Path path) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes); var zip = new ZipFile(path.toFile()))
        {
            // ZipFile reads the central directory; no portfolio entry is decompressed.
            output.writeUTF("ZIP"); //$NON-NLS-1$
            var entries = zip.entries();
            while (entries.hasMoreElements())
            {
                var entry = entries.nextElement();
                output.writeUTF(entry.getName());
                output.writeLong(entry.getCrc());
                output.writeLong(entry.getSize());
                output.writeLong(entry.getCompressedSize());
            }
        }
        return bytes.toByteArray();
    }
}
