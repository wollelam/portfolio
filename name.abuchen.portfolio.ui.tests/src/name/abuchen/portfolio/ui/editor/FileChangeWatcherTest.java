package name.abuchen.portfolio.ui.editor;

import static org.junit.Assert.assertNotNull;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileChangeWatcherTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void detectsModification() throws Exception
    {
        var file = temporaryFolder.newFile("client.xml").toPath(); //$NON-NLS-1$
        var events = new LinkedBlockingQueue<Boolean>();
        try (var watcher = new FileChangeWatcher(file, () -> events.offer(Boolean.TRUE)))
        {
            Files.writeString(file, "changed"); //$NON-NLS-1$
            assertNotNull(events.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void detectsReplacement() throws Exception
    {
        var file = temporaryFolder.newFile("client.xml").toPath(); //$NON-NLS-1$
        var replacement = temporaryFolder.newFile("replacement.xml").toPath(); //$NON-NLS-1$
        Files.writeString(replacement, "changed"); //$NON-NLS-1$
        var events = new LinkedBlockingQueue<Boolean>();
        try (var watcher = new FileChangeWatcher(file, () -> events.offer(Boolean.TRUE)))
        {
            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            assertNotNull(events.poll(5, TimeUnit.SECONDS));
        }
    }
}
