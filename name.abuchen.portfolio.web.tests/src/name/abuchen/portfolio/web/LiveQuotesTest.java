package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;

@SuppressWarnings("nls")
public class LiveQuotesTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private int revision;

    @Test
    public void simulationIsNeverReportedAsABrokerConnection() throws Exception
    {
        var file = temporary.newFolder().toPath().resolve("latest.json");
        write(file, frame("demo", clock.instant(), clock.instant()));
        var status = new LiveQuotes(file, clock).status();
        assertTrue(status.configured());
        assertTrue(status.available());
        assertEquals("demo", status.mode());
        assertFalse(status.connected());
        assertFalse(status.quotes().getFirst().stale());
        assertEquals(100d, status.quotes().getFirst().quote().last(), 0);
        assertEquals(98d, status.quotes().getFirst().quote().previousClose(), 0);
        assertEquals(105d, status.quotes().getFirst().quote().dayHigh(), 0);
        assertEquals(95d, status.quotes().getFirst().quote().dayLow(), 0);
    }

    @Test
    public void missingOrCorruptSnapshotsRetainLastPricesButMarkThemUnavailable() throws Exception
    {
        var file = temporary.newFolder().toPath().resolve("latest.json");
        write(file, frame("ibkr", clock.instant(), clock.instant()));
        var quotes = new LiveQuotes(file, clock);
        assertTrue(quotes.status().connected());
        Files.writeString(file, "private malformed broker text");
        var broken = quotes.status();
        assertFalse(broken.available());
        assertFalse(broken.connected());
        assertTrue(broken.quotes().getFirst().stale());
        assertFalse(broken.error().contains("private"));
        Files.delete(file);
        assertEquals(1, quotes.status().quotes().size());
    }

    @Test
    public void collectorHeartbeatAndIndividualQuoteFreshnessAreIndependent() throws Exception
    {
        var file = temporary.newFolder().toPath().resolve("latest.json");
        write(file, frame("ibkr", clock.instant(), clock.instant().minusSeconds(61)));
        var fresh = new LiveQuotes(file, clock).status();
        assertTrue(fresh.available());
        assertTrue(fresh.connected());
        assertTrue(fresh.quotes().getFirst().stale());
        var stopped = new LiveQuotes(file, Clock.offset(clock, Duration.ofSeconds(9))).status();
        assertFalse(stopped.available());
        assertFalse(stopped.connected());
        assertEquals("stale", stopped.state());
    }

    @Test
    public void invalidPricesFutureDataAndDuplicateKeysCannotBePublished() throws Exception
    {
        var file = temporary.newFolder().toPath().resolve("latest.json");
        var normal = frame("demo", clock.instant(), clock.instant());
        var gson = new Gson();
        for (var invalid : List.of(gson.toJson(normal).replace("100.0", "-1.0"),
                        gson.toJson(frame("demo", clock.instant().plusSeconds(31), clock.instant())),
                        gson.toJson(new LiveQuotes.Frame(1, "demo", "test", clock.instant().toString(), false, "running", null,
                                        List.of(normal.quotes().getFirst(), normal.quotes().getFirst())))))
        {
            Files.writeString(file, invalid);
            assertFalse(new LiveQuotes(file, clock).status().available());
        }
    }

    @Test
    public void fullWatchlistWithHistoriesIsAcceptedButExcessIsRejected() throws Exception
    {
        var file = temporary.newFolder().toPath().resolve("latest.json");
        var normal = frame("ibkr", clock.instant(), clock.instant());
        var samples = java.util.stream.IntStream.range(0, 90)
                        .mapToObj(i -> new LiveQuotes.Sample(clock.instant().minusSeconds(89 - i).toString(), 100d)).toList();
        var quotes = java.util.stream.IntStream.range(0, 100).mapToObj(i -> new LiveQuotes.Quote("stock-" + i,
                        "Stock " + i, "TEST", "USD", 123L + i, "delayed", "active", clock.instant().toString(),
                        100d, 99d, 101d, 98d, 105d, 95d, "STK", samples)).toList();
        write(file, new LiveQuotes.Frame(1, "ibkr", "full", normal.generatedAt(), true, "connected", null, quotes));
        assertTrue(Files.size(file) > 262144);
        assertEquals(100, new LiveQuotes(file, clock).status().quotes().size());
        var oversized = new java.util.ArrayList<>(quotes);
        oversized.add(new LiveQuotes.Quote("excess", "Excess", "TEST", "USD", 999L, "delayed", "waiting", null,
                        null, null, null, null, null, null, "STK", List.of()));
        write(file, new LiveQuotes.Frame(1, "ibkr", "full", normal.generatedAt(), true, "connected", null, oversized));
        assertFalse(new LiveQuotes(file, clock).status().available());
    }

    @Test
    public void disabledFeedDoesNotAccessFilesOrReportAConnection()
    {
        var status = new LiveQuotes(null, clock).status();
        assertFalse(status.configured());
        assertFalse(status.connected());
        assertTrue(status.quotes().isEmpty());
    }

    private LiveQuotes.Frame frame(String mode, Instant generated, Instant received)
    {
        var quote = new LiveQuotes.Quote("test", "Example", "TEST", "USD", mode.equals("demo") ? null : 123L,
                        "real_time", "active", received.toString(), 100d, 99d, 101d, 98d, 105d, 95d, "STK",
                        List.of(new LiveQuotes.Sample(received.toString(), 100d)));
        return new LiveQuotes.Frame(1, mode, "test-session", generated.toString(), true,
                        mode.equals("demo") ? "running" : "connected", null, List.of(quote));
    }

    private void write(Path file, LiveQuotes.Frame frame) throws Exception
    {
        Files.writeString(file, new Gson().toJson(frame));
        Files.setLastModifiedTime(file, FileTime.from(clock.instant().plusSeconds(++revision)));
    }
}
