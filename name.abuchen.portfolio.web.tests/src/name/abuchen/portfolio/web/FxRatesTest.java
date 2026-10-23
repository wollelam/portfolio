package name.abuchen.portfolio.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.impl.ECBExchangeRateProvider;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;

@SuppressWarnings("nls")
public class FxRatesTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final Clock clock = Clock.fixed(Instant.parse("2025-06-12T12:00:00Z"), ZoneOffset.UTC);

    @Test
    public void publishesAnIndependentProviderAndKeepsInFlightRatesUnchanged() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        try (var fx = new FxRates(directory, null, clock, provider -> change(provider, "2", LocalDate.now(clock))))
        {
            var before = fx.view();
            var beforeRate = chf(before);
            fx.request(true).get(5, TimeUnit.SECONDS);
            assertNotEquals(before.version(), fx.view().version());
            assertEquals(beforeRate, chf(before));
            assertEquals(0, BigDecimal.valueOf(2).compareTo(chf(fx.view())));
            assertNotNull(fx.status().lastSuccessAt());
            assertNull(fx.status().error());
            assertNull(fx.request(true));
            assertEquals(LocalDate.now(clock).toString(), fx.status().rateDate());
        }
    }

    @Test
    public void failedRefreshRetainsBothCurrentRatesAndDurableCache() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        try (var first = new FxRates(directory, null, clock, provider -> change(provider, "2", LocalDate.now(clock))))
        {
            first.request(true).get(5, TimeUnit.SECONDS);
        }
        var bytes = Files.readAllBytes(directory.resolve("ecb_exchange_rates.pb"));
        try (var failed = new FxRates(directory, null, Clock.offset(clock, Duration.ofMinutes(16)), provider -> {
            change(provider, "3", LocalDate.now(clock));
            throw new IOException("provider exception with a private URL");
        }))
        {
            var before = failed.view();
            failed.request(true).get(5, TimeUnit.SECONDS);
            assertEquals(before.version(), failed.view().version());
            assertEquals(0, BigDecimal.valueOf(2).compareTo(chf(failed.view())));
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("ecb_exchange_rates.pb")));
            assertNotNull(failed.status().error());
            org.junit.Assert.assertFalse(failed.status().error().contains("private URL"));
            try (var files = Files.list(directory))
            {
                assertEquals(0, files.filter(file -> file.getFileName().toString().startsWith("incoming-")).count());
            }
        }
    }

    @Test
    public void concurrentRefreshRequestsDoNotStartAnotherDownload() throws Exception
    {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fx = new FxRates(temporary.newFolder().toPath(), null, clock, provider -> {
            entered.countDown();
            try
            {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new IOException("Timed out waiting for test");
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }))
        {
            var running = fx.request(true);
            org.junit.Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNull(fx.request(true));
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aNewFxContextPreservesTheOldPortfolioView() throws Exception
    {
        try (var fx = new FxRates(temporary.newFolder().toPath(), null, clock,
                        provider -> change(provider, "2", LocalDate.now(clock))))
        {
            var loaded = DemoPortfolio.create(LocalDate.now(clock));
            var first = new DashboardService(loaded, clock, List.of(), fx.view());
            var oldVersion = first.fxVersion();
            fx.request(true).get(5, TimeUnit.SECONDS);
            var updated = first.withRates(fx.view());
            assertEquals(oldVersion, first.fxVersion());
            assertNotEquals(oldVersion, updated.fxVersion());
            assertEquals(first.dashboard(LocalDate.now(clock).minusYears(1), LocalDate.now(clock)).revision(),
                            updated.dashboard(LocalDate.now(clock).minusYears(1), LocalDate.now(clock)).revision());
        }
    }

    private void change(ECBExchangeRateProvider provider, String value, LocalDate date) throws IOException
    {
        provider.getAvailableTimeSeries(new Client()).stream().filter(series -> series.getTermCurrency().equals("CHF"))
                        .map(ExchangeRateTimeSeriesImpl.class::cast).findFirst().orElseThrow()
                        .addRate(new ExchangeRate(date, new BigDecimal(value)));
        // The real ECBUpdater sets this flag after accepting a feed. Simulate that without network I/O.
        try
        {
            var field = ECBExchangeRateProvider.class.getDeclaredField("data");
            field.setAccessible(true);
            var data = field.get(provider);
            var dirty = data.getClass().getDeclaredMethod("setDirty", boolean.class);
            dirty.setAccessible(true);
            dirty.invoke(data, true);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IOException(e);
        }
    }

    private BigDecimal chf(FxRates.View view)
    {
        return view.factory(new Client()).getTimeSeries("EUR", "CHF").lookupRateIfAvailable(LocalDate.now(clock))
                        .orElseThrow().getValue();
    }
}
