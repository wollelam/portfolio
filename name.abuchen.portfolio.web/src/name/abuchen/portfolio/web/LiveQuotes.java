package name.abuchen.portfolio.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;

/** Validated, read-only view of the independent quote collector. It never mutates a portfolio. */
@SuppressWarnings("nls")
final class LiveQuotes
{
    record Sample(String time, double value)
    {
    }

    record Quote(String key, String name, String symbol, String currency, Long conId, String marketDataType,
                    String status, String receivedAt, Double last, Double bid, Double ask, Double previousClose, Double dayHigh, Double dayLow, String secType, List<Sample> history)
    {
    }

    record Frame(int schemaVersion, String mode, String session, String generatedAt, boolean connected, String state,
                    String error, List<Quote> quotes)
    {
    }

    record DisplayQuote(Quote quote, boolean stale)
    {
    }

    record Status(boolean configured, boolean available, String mode, String state, boolean connected,
                    String generatedAt, String error, List<DisplayQuote> quotes)
    {
    }

    private static final Set<String> TYPES = Set.of("real_time", "delayed", "frozen", "delayed_frozen", "unknown");
    private static final Set<String> STATES = Set.of("running", "connecting", "connected", "disconnected", "stopped");
    private static final Set<String> QUOTE_STATES = Set.of("active", "waiting", "unavailable", "unresolved", "not_subscribed");
    private final Path file;
    private final Clock clock;
    private final Gson gson = new Gson();
    private Frame accepted;
    private FileTime acceptedTime;
    private long acceptedSize;

    LiveQuotes(Path file, Clock clock)
    {
        this.file = file;
        this.clock = clock;
    }

    synchronized Status status()
    {
        if (file == null)
            return new Status(false, false, "disabled", "disabled", false, null, null, List.of());
        String failure = null;
        try
        {
            var size = Files.size(file);
            var modified = Files.getLastModifiedTime(file);
            if (size > 2097152)
                throw new IOException("Oversized quote snapshot");
            if (!modified.equals(acceptedTime) || size != acceptedSize)
            {
                var candidate = gson.fromJson(Files.readString(file), Frame.class);
                validate(candidate);
                accepted = candidate;
                acceptedTime = modified;
                acceptedSize = size;
            }
        }
        catch (IOException | RuntimeException error)
        {
            failure = "The quote collector snapshot is unavailable. Last received prices, if any, are retained.";
        }
        if (accepted == null)
            return new Status(true, false, "unknown", "waiting", false, null, failure, List.of());

        boolean fresh = !clock.instant().isAfter(Instant.parse(accepted.generatedAt()).plusSeconds(8));
        boolean available = failure == null && fresh && !accepted.state().equals("stopped");
        boolean connected = available && accepted.mode().equals("ibkr") && accepted.connected();
        boolean usable = available && (accepted.mode().equals("demo") || connected);
        String error = failure != null ? failure : !fresh ? "The collector has stopped updating. Last received quotes are shown."
                        : accepted.error();
        var quotes = accepted.quotes().stream().map(quote -> new DisplayQuote(quote,
                        !usable || !quote.status().equals("active") || quote.receivedAt() == null
                                        || clock.instant().isAfter(Instant.parse(quote.receivedAt()).plusSeconds(60)))).toList();
        return new Status(true, available, accepted.mode(), available ? accepted.state() : "stale", connected,
                        accepted.generatedAt(), error, quotes);
    }

    private void validate(Frame frame)
    {
        if (frame == null || frame.schemaVersion() != 1 || !("demo".equals(frame.mode()) || "ibkr".equals(frame.mode()))
                        || !STATES.contains(frame.state()) || frame.quotes() == null || frame.quotes().size() > 100)
            throw new IllegalArgumentException("Invalid quote snapshot");
        requireText(frame.session(), 80);
        var generated = Instant.parse(frame.generatedAt());
        if (generated.isAfter(clock.instant().plusSeconds(30)))
            throw new IllegalArgumentException("Future quote snapshot");
        if (frame.error() != null)
            requireText(frame.error(), 240);
        var keys = new HashSet<String>();
        for (var quote : frame.quotes())
        {
            requireText(quote.key(), 100);
            requireText(quote.name(), 100);
            requireText(quote.symbol(), 100);
            if (!keys.add(quote.key()) || quote.currency() == null || !quote.currency().matches("[A-Z]{3}")
                            || !TYPES.contains(quote.marketDataType()) || !QUOTE_STATES.contains(quote.status())
                            || quote.conId() != null && quote.conId() <= 0 || quote.history() == null || quote.history().size() > 90)
                throw new IllegalArgumentException("Invalid quote identity");
            if (quote.receivedAt() != null && Instant.parse(quote.receivedAt()).isAfter(generated.plusSeconds(1)))
                throw new IllegalArgumentException("Future quote receipt");
            if (quote.secType() != null && !Set.of("STK", "CASH").contains(quote.secType()))
                throw new IllegalArgumentException("Invalid quote security type");
            positive(quote.last());
            positive(quote.bid());
            positive(quote.ask());
            positive(quote.previousClose());
            positive(quote.dayHigh());
            positive(quote.dayLow());
            Instant previous = null;
            for (var sample : quote.history())
            {
                positive(sample.value());
                var time = Instant.parse(sample.time());
                if (time.isAfter(generated.plusSeconds(1)) || previous != null && !time.isAfter(previous))
                    throw new IllegalArgumentException("Invalid quote history");
                previous = time;
            }
        }
    }

    private void requireText(String value, int limit)
    {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("Invalid quote label");
    }

    private void positive(Double value)
    {
        if (value != null && (!Double.isFinite(value) || value <= 0))
            throw new IllegalArgumentException("Invalid quote price");
    }
}
