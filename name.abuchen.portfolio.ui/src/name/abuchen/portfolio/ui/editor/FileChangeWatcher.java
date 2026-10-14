package name.abuchen.portfolio.ui.editor;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchService;

/** Watches the parent directory so atomic replacements are detected too. */
/* package */ final class FileChangeWatcher implements AutoCloseable
{
    private final Path file;
    private final WatchService service;

    FileChangeWatcher(Path file, Runnable onChange) throws IOException
    {
        this.file = file.toAbsolutePath().normalize();
        service = file.getFileSystem().newWatchService();
        try
        {
            this.file.getParent().register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        }
        catch (IOException | RuntimeException e)
        {
            service.close();
            throw e;
        }
        Thread thread = new Thread(() -> watch(onChange), "portfolio-file-watcher"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    Path getFile()
    {
        return file;
    }

    private void watch(Runnable onChange)
    {
        try
        {
            while (true)
            {
                var key = service.take();
                boolean changed = false;
                for (var event : key.pollEvents())
                    changed |= event.kind() == OVERFLOW || file.getFileName().equals(event.context());
                boolean valid = key.reset();
                if (changed || !valid)
                    onChange.run();
                if (!valid)
                    return;
            }
        }
        catch (ClosedWatchServiceException e)
        {
            // Expected when the editor closes or Save As changes its path.
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException
    {
        service.close();
    }
}
