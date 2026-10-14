package name.abuchen.portfolio.ui.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileContentSnapshotTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void xmlDetectsModificationTimeChange() throws Exception
    {
        var file = temporaryFolder.newFile("client.xml").toPath(); //$NON-NLS-1$
        Files.writeString(file, "<client/>"); //$NON-NLS-1$
        var before = FileContentSnapshot.read(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1));
        assertFalse(Arrays.equals(before, FileContentSnapshot.read(file)));
    }

    @Test
    public void xmlDetectsSizeChangeWithUnchangedTimestamp() throws Exception
    {
        var file = temporaryFolder.newFile("client.xml").toPath(); //$NON-NLS-1$
        Files.writeString(file, "<client/>"); //$NON-NLS-1$
        var timestamp = Files.getLastModifiedTime(file);
        var before = FileContentSnapshot.read(file);
        Files.writeString(file, "<client></client>"); //$NON-NLS-1$
        Files.setLastModifiedTime(file, timestamp);
        assertFalse(Arrays.equals(before, FileContentSnapshot.read(file)));
    }

    @Test
    public void xmlDoesNotInspectContentWhenSizeAndTimestampMatch() throws Exception
    {
        var file = temporaryFolder.newFile("client.xml").toPath(); //$NON-NLS-1$
        Files.writeString(file, "before"); //$NON-NLS-1$
        var timestamp = Files.getLastModifiedTime(file);
        var before = FileContentSnapshot.read(file);
        Files.writeString(file, "after!"); //$NON-NLS-1$
        Files.setLastModifiedTime(file, timestamp);
        assertArrayEquals(before, FileContentSnapshot.read(file));
    }

    @Test
    public void zipDetectsChangedEntryWithUnchangedTimestamp() throws Exception
    {
        var file = temporaryFolder.newFile("client.portfolio").toPath(); //$NON-NLS-1$
        writeZip(file, "before"); //$NON-NLS-1$
        var timestamp = Files.getLastModifiedTime(file);
        var before = FileContentSnapshot.read(file);
        writeZip(file, "after!"); //$NON-NLS-1$
        Files.setLastModifiedTime(file, timestamp);
        assertFalse(Arrays.equals(before, FileContentSnapshot.read(file)));
    }

    @Test
    public void zipIgnoresFilesystemTimestampOnlyChanges() throws Exception
    {
        var file = temporaryFolder.newFile("client.portfolio").toPath(); //$NON-NLS-1$
        writeZip(file, "unchanged"); //$NON-NLS-1$
        var before = FileContentSnapshot.read(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1));
        assertArrayEquals(before, FileContentSnapshot.read(file));
    }

    @Test
    public void encryptedDetectsNewInitializationVector() throws Exception
    {
        var file = temporaryFolder.newFile("client.portfolio").toPath(); //$NON-NLS-1$
        byte[] header = encryptedHeader();
        Files.write(file, header);
        var timestamp = Files.getLastModifiedTime(file);
        var before = FileContentSnapshot.read(file);
        header[24]++;
        Files.write(file, header);
        Files.setLastModifiedTime(file, timestamp);
        assertFalse(Arrays.equals(before, FileContentSnapshot.read(file)));
    }

    @Test
    public void encryptedReadsOnlyHeader() throws Exception
    {
        var file = temporaryFolder.newFile("client.portfolio").toPath(); //$NON-NLS-1$
        var bytes = Arrays.copyOf(encryptedHeader(), 1024);
        Files.write(file, bytes);
        assertArrayEquals(encryptedHeader(), FileContentSnapshot.read(file));
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsIncompleteEncryptedHeader() throws Exception
    {
        var file = temporaryFolder.newFile("client.portfolio").toPath(); //$NON-NLS-1$
        Files.write(file, Arrays.copyOf(encryptedHeader(), 12));
        FileContentSnapshot.read(file);
    }

    private byte[] encryptedHeader()
    {
        var header = Arrays.copyOf("PORTFOLIO".getBytes(StandardCharsets.US_ASCII), 25); //$NON-NLS-1$
        header[9] = 1;
        return header;
    }

    private void writeZip(Path file, String content) throws Exception
    {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file)))
        {
            zip.putNextEntry(new ZipEntry("data.portfolio")); //$NON-NLS-1$
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
