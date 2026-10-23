package name.abuchen.portfolio.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.Gson;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.impl.ECBExchangeRateProvider;

/** Downloads into an independent provider. Published contexts never observe a provider being updated. */
@SuppressWarnings("nls")
final class FxRates implements AutoCloseable
{
    record View(String version, List<ExchangeRateTimeSeries> series)
    {
        ExchangeRateProviderFactory factory(Client client)
        {
            return new ExchangeRateProviderFactory(client)
            {
                @Override
                public List<ExchangeRateTimeSeries> getAvailableTimeSeries()
                {
                    var result = new ArrayList<>(super.getAvailableTimeSeries());
                    result.removeIf(s -> s.getProvider().orElse(null) instanceof ECBExchangeRateProvider);
                    result.addAll(series);
                    return result;
                }
            };
        }
    }

    record Status(boolean enabled, boolean running, String lastAttemptAt, String lastSuccessAt, String rateDate,
                    String nextAttemptAt, String error, String version)
    {
    }

    @FunctionalInterface
    interface Fetcher
    {
        void update(ECBExchangeRateProvider provider) throws IOException;
    }

    private final Path directory;
    private final Path seed;
    private final Clock clock;
    private final Fetcher fetcher;
    private final AtomicReference<View> current = new AtomicReference<>();
    private final AtomicReference<Status> status = new AtomicReference<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "portfolio-fx-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private volatile boolean cacheReadable;

    FxRates(Path directory, Path seed, Clock clock, Fetcher fetcher) throws IOException
    {
        this.directory = directory;
        this.seed = seed;
        this.clock = clock;
        this.fetcher = fetcher;
        PrivateFiles.directory(directory);
        var provider = new ECBExchangeRateProvider(() -> directory);
        String error = null;
        try
        {
            if (!Files.exists(cache()) && seed != null && Files.isRegularFile(seed))
                PrivateFiles.write(cache(), Files.readAllBytes(seed));
            provider.load(new NullProgressMonitor());
            cacheReadable = true;
        }
        catch (IOException failure)
        {
            provider = seed != null && Files.isRegularFile(seed) ? new ECBExchangeRateProvider(() -> seed.getParent())
                            : new ECBExchangeRateProvider();
            try
            {
                provider.load(new NullProgressMonitor());
            }
            catch (IOException ignored)
            {
                provider = new ECBExchangeRateProvider();
            }
            error = "The FX cache could not be loaded; earlier saved rates are being used.";
        }
        var view = view(provider);
        current.set(view);
        String success = null;
        try
        {
            var previous = new Gson().fromJson(Files.readString(directory.resolve("status.json")), Status.class);
            if (previous != null && previous.lastSuccessAt() != null)
                success = Instant.parse(previous.lastSuccessAt()).toString();
        }
        catch (IOException | RuntimeException ignored)
        {
            // Cache contents are usable even when no prior check time is available.
        }
        status.set(new Status(true, false, null, success, rateDate(view), success == null ? null
                        : Instant.parse(success).plus(Duration.ofHours(6)).toString(), error, view.version()));
    }

    View view()
    {
        return current.get();
    }

    Status status()
    {
        return status.get();
    }

    void start()
    {
        executor.scheduleWithFixedDelay(() -> request(false), 5, 60, TimeUnit.SECONDS);
    }

    synchronized Future<?> request(boolean manual)
    {
        var previous = status.get();
        if (closed || previous.running())
            return null;
        var now = clock.instant();
        if (previous.lastAttemptAt() != null && now.isBefore(Instant.parse(previous.lastAttemptAt()).plusSeconds(900)))
            return null;
        if (!manual && previous.nextAttemptAt() != null && now.isBefore(Instant.parse(previous.nextAttemptAt())))
            return null;
        status.set(new Status(true, true, now.toString(), previous.lastSuccessAt(), previous.rateDate(), null, null,
                        previous.version()));
        return executor.submit(this::update);
    }

    private void update()
    {
        var previous = status.get();
        Path staging = null;
        try
        {
            staging = Files.createTempDirectory(directory, "incoming-");
            var working = staging;
            var input = cacheReadable && Files.isRegularFile(cache()) ? cache() : seed;
            if (input != null && Files.isRegularFile(input))
                Files.copy(input, staging.resolve("ecb_exchange_rates.pb"));
            var provider = new ECBExchangeRateProvider(() -> working);
            provider.load(new NullProgressMonitor());
            fetcher.update(provider);
            var candidate = view(provider);
            if (rateDate(candidate) == null)
                throw new IOException("No FX rates returned");
            for (var series : candidate.series())
                for (var rate : series.getRates())
                    if (rate.getValue().signum() <= 0 || rate.getTime().isAfter(LocalDate.now(clock)))
                        throw new IOException("Invalid FX rate returned");
            provider.save(new NullProgressMonitor());
            if (closed)
                return;
            PrivateFiles.promote(staging.resolve("ecb_exchange_rates.pb"), cache());
            var now = clock.instant();
            var completed = new Status(true, false, previous.lastAttemptAt(), now.toString(), rateDate(candidate),
                            now.plus(Duration.ofHours(6)).toString(), null, candidate.version());
            PrivateFiles.write(directory.resolve("status.json"), new Gson().toJson(completed).getBytes(StandardCharsets.UTF_8));
            current.set(candidate);
            cacheReadable = true;
            status.set(completed);
        }
        catch (IOException | RuntimeException failure)
        {
            status.set(new Status(true, false, previous.lastAttemptAt(), previous.lastSuccessAt(), previous.rateDate(),
                            clock.instant().plusSeconds(900).toString(),
                            "FX refresh failed. The last working rates are still in use; another check will be attempted.",
                            previous.version()));
        }
        finally
        {
            if (staging != null)
            {
                try
                {
                    Files.deleteIfExists(staging.resolve("ecb_exchange_rates.pb"));
                    Files.deleteIfExists(staging);
                }
                catch (IOException ignored)
                {
                    // A complete current cache remains separate from staging.
                }
            }
        }
    }

    private View view(ECBExchangeRateProvider provider)
    {
        return new View(UUID.randomUUID().toString(), List.copyOf(provider.getAvailableTimeSeries(new Client())));
    }

    private String rateDate(View view)
    {
        return view.series().stream().flatMap(series -> series.getRates().stream())
                        .map(rate -> rate.getTime()).max(LocalDate::compareTo).map(LocalDate::toString).orElse(null);
    }

    private Path cache()
    {
        return directory.resolve("ecb_exchange_rates.pb");
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        executor.shutdownNow();
    }
}
