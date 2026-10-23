package name.abuchen.portfolio.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Publishes complete calculation contexts; a request keeps its captured revision during a reload. */
@SuppressWarnings("nls")
final class PortfolioRefresh implements AutoCloseable
{
    record Status(boolean automatic, int intervalSeconds, String lastCheckedAt, boolean pending, String error)
    {
    }

    record Snapshot(DashboardService service, String revision, Status status)
    {
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final PortfolioSource source;
    private final Clock clock;
    private final List<String> startupWarnings;
    private final int intervalSeconds;
    private final Duration settlingTime;
    private ScheduledExecutorService scheduler;
    private String pendingRevision;
    private Instant pendingSince;
    private volatile boolean closed;

    PortfolioRefresh(PortfolioSource source, Clock clock, List<String> warnings, int intervalSeconds,
                    Duration settlingTime) throws IOException
    {
        this.source = source;
        this.clock = clock;
        this.startupWarnings = List.copyOf(warnings);
        this.intervalSeconds = intervalSeconds;
        this.settlingTime = settlingTime;
        try
        {
            var loaded = source.load();
            var service = validated(loaded);
            source.accept(loaded);
            current.set(new Snapshot(service, loaded.revision(), status(false, null)));
        }
        catch (IOException | RuntimeException failure)
        {
            try
            {
                var recovered = source.lastGood();
                current.set(new Snapshot(validated(recovered), recovered.revision(), status(false, failureMessage())));
            }
            catch (IOException | RuntimeException recoveryFailure)
            {
                throw new IOException("No readable portfolio or recovery snapshot is available", recoveryFailure);
            }
        }
    }

    private PortfolioRefresh(DashboardService service)
    {
        source = null;
        clock = Clock.systemUTC();
        startupWarnings = List.of();
        intervalSeconds = 0;
        settlingTime = Duration.ZERO;
        current.set(new Snapshot(service, null, status(false, null)));
    }

    static PortfolioRefresh fixed(DashboardService service)
    {
        return new PortfolioRefresh(service);
    }

    Snapshot snapshot()
    {
        return current.get();
    }

    synchronized void start()
    {
        if (intervalSeconds == 0 || scheduler != null || closed)
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "portfolio-file-refresh");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refresh, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    synchronized void refresh()
    {
        if (source == null || intervalSeconds == 0 || closed)
            return;
        var previous = current.get();
        try
        {
            var revision = source.revision();
            if (revision == null)
                throw new IOException("Source does not support revision checks");
            if (previous.revision().equals(revision))
            {
                pendingRevision = null;
                current.set(new Snapshot(previous.service(), previous.revision(), status(false, null)));
                return;
            }
            if (!java.util.Objects.equals(pendingRevision, revision))
            {
                pendingRevision = revision;
                pendingSince = clock.instant();
            }
            if (Duration.between(pendingSince, clock.instant()).compareTo(settlingTime) < 0)
            {
                current.set(new Snapshot(previous.service(), previous.revision(), status(true, previous.status().error())));
                return;
            }
            var loaded = source.load();
            if (!loaded.revision().equals(revision))
                throw new IOException("Portfolio changed during refresh");
            var service = validated(loaded);
            if (closed)
                return;
            source.accept(loaded);
            current.set(new Snapshot(service, loaded.revision(), status(false, null)));
            pendingRevision = null;
        }
        catch (IOException | RuntimeException failure)
        {
            pendingRevision = null;
            current.set(new Snapshot(previous.service(), previous.revision(), status(false, failureMessage())));
        }
    }

    private DashboardService validated(PortfolioSource.LoadedPortfolio loaded)
    {
        var service = new DashboardService(loaded, clock, startupWarnings);
        var to = LocalDate.now(clock);
        service.dashboard(to.minusYears(1), to);
        return service;
    }

    private Status status(boolean pending, String error)
    {
        return new Status(intervalSeconds > 0, intervalSeconds, clock.instant().toString(), pending, error);
    }

    private String failureMessage()
    {
        return "The latest portfolio could not be loaded. Showing the last working snapshot; updates will be retried.";
    }

    @Override
    public void close()
    {
        closed = true;
        if (scheduler != null)
            scheduler.shutdownNow();
    }
}
