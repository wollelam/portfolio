package name.abuchen.portfolio.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;

/** Fixed-purpose request mailbox. The host worker owns SSH and the existing guarded writer. */
@SuppressWarnings("nls")
final class QuoteJobs
{
    record Request(String id, String requestedAt, String source)
    {
    }

    record Worker(String checkedAt, boolean available, boolean sourceBusy, boolean requiresVerification,
                    String lastSuccessAt, int providerFailures, String lastResult, String schedule, String requestId,
                    String state, String source, String nextAllowedAt)
    {
    }

    record Status(boolean available, String state, String checkedAt, String lastSuccessAt, int providerFailures,
                    String schedule, String requestId, String requestedAt, String nextAllowedAt, boolean canRequest,
                    String error)
    {
    }

    record Outcome(int httpStatus, Status quotes)
    {
    }

    private static final Set<String> STATES = Set.of("idle", "running", "complete", "partial", "failed", "busy", "unconfirmed");
    private final Path directory;
    private final Clock clock;
    private final Gson gson = new Gson();
    private final String source;

    QuoteJobs(Path directory, Clock clock)
    {
        this(directory, clock, null);
    }

    QuoteJobs(Path directory, Clock clock, String source)
    {
        this.directory = directory;
        this.clock = clock;
        this.source = source;
    }

    Status status()
    {
        if (directory == null)
            return unavailable("Price updates are not configured for this instance.");
        try
        {
            var worker = read("status.json", Worker.class);
            if (worker == null)
                return unavailable("The price updater is unavailable or has not checked in recently.");
            if (source != null && !source.equals(worker.source()))
                return unavailable("This updater is configured for a different portfolio.");
            if (!worker.available() || !fresh(worker.checkedAt()))
                return new Status(false, "unavailable", timestamp(worker.checkedAt()), timestamp(worker.lastSuccessAt()),
                                Math.max(0, worker.providerFailures()), safeSchedule(worker.schedule()), worker.requestId(),
                                null, null, false, "The price updater is unavailable. Its last confirmed result is shown.");
            var request = read("request.json", Request.class);
            var state = STATES.contains(worker.state()) ? worker.state() : "unconfirmed";
            boolean queued = request != null && !request.id().equals(worker.requestId());
            if (queued)
                state = "queued";
            else if (worker.sourceBusy())
                state = "running";
            String next = request == null ? null : Instant.parse(request.requestedAt()).plusSeconds(900).toString();
            if (worker.nextAllowedAt() != null && (next == null || Instant.parse(worker.nextAllowedAt()).isAfter(Instant.parse(next))))
                next = Instant.parse(worker.nextAllowedAt()).toString();
            String error = null;
            if (worker.requiresVerification())
                error = "A pending portfolio update needs verification before prices can be refreshed.";
            else if (state.equals("failed") || state.equals("unconfirmed"))
                error = "The last requested update could not be confirmed. Existing prices remain available; check the updater before retrying.";
            else if (state.equals("busy"))
                error = "The existing updater was busy. No new quote update was started.";
            boolean canRequest = !queued && !state.equals("running") && !state.equals("unconfirmed") && !worker.requiresVerification()
                            && (next == null || !clock.instant().isBefore(Instant.parse(next)));
            return new Status(true, state, Instant.parse(worker.checkedAt()).toString(), timestamp(worker.lastSuccessAt()),
                            Math.max(0, worker.providerFailures()), safeSchedule(worker.schedule()),
                            request == null ? worker.requestId() : request.id(), request == null ? null : request.requestedAt(),
                            next, canRequest, error);
        }
        catch (IOException | RuntimeException failure)
        {
            return unavailable("The price updater status could not be read. Existing prices remain available.");
        }
    }

    synchronized Outcome request() throws IOException
    {
        var before = status();
        if (!before.available())
            return new Outcome(503, before);
        if (before.state().equals("queued") || before.state().equals("running"))
            return new Outcome(202, before);
        if (!before.canRequest())
            return new Outcome(429, before);
        var request = new Request(UUID.randomUUID().toString(), clock.instant().toString(), source);
        PrivateFiles.write(directory.resolve("request.json"), gson.toJson(request).getBytes(StandardCharsets.UTF_8));
        return new Outcome(202, status());
    }

    private boolean fresh(String value)
    {
        var checked = Instant.parse(value);
        return !checked.isBefore(clock.instant().minusSeconds(180)) && !checked.isAfter(clock.instant().plusSeconds(30));
    }

    private String timestamp(String value)
    {
        return value == null ? null : Instant.parse(value).toString();
    }

    private String safeSchedule(String value)
    {
        return value == null || value.length() > 120 ? "Existing updater schedule" : value;
    }

    private <T> T read(String name, Class<T> type) throws IOException
    {
        var file = directory.resolve(name);
        if (!Files.exists(file))
            return null;
        if (Files.size(file) > 65536)
            throw new IOException("Invalid updater status size");
        return gson.fromJson(Files.readString(file), type);
    }

    private Status unavailable(String message)
    {
        return new Status(false, "unavailable", null, null, 0, "Existing updater schedule", null, null, null, false, message);
    }
}
