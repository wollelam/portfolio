package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;

@SuppressWarnings("nls")
public class QuoteJobsTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final Clock clock = Clock.fixed(Instant.parse("2025-06-12T12:00:00Z"), ZoneOffset.UTC);
    private final String source = "/input/test.portfolio";

    @Test
    public void duplicateRequestsAreCoalescedAndCompletedRequestsHaveACooldown() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        worker(directory, clock, "idle", null, source);
        var jobs = new QuoteJobs(directory, clock, source);
        assertEquals(4, jobs.status().providerFailures());
        assertTrue(jobs.status().canRequest());
        var first = jobs.request();
        assertEquals(202, first.httpStatus());
        assertEquals("queued", first.quotes().state());
        var id = first.quotes().requestId();
        assertEquals(id, jobs.request().quotes().requestId());
        var request = new Gson().fromJson(Files.readString(directory.resolve("request.json")), QuoteJobs.Request.class);
        assertEquals(source, request.source());
        worker(directory, clock, "complete", id, source);
        assertEquals(429, jobs.request().httpStatus());
        var later = Clock.offset(clock, Duration.ofMinutes(16));
        worker(directory, later, "complete", id, source);
        assertEquals(202, new QuoteJobs(directory, later, source).request().httpStatus());
    }

    @Test
    public void staleHeartbeatKeepsLastConfirmedResultButDisablesRequests() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        worker(directory, clock, "partial", null, source);
        var jobs = new QuoteJobs(directory, Clock.offset(clock, Duration.ofMinutes(4)), source);
        assertFalse(jobs.status().available());
        assertFalse(jobs.status().canRequest());
        assertNotNull(jobs.status().lastSuccessAt());
        assertEquals(4, jobs.status().providerFailures());
        assertEquals(503, jobs.request().httpStatus());
        assertFalse(Files.exists(directory.resolve("request.json")));
    }

    @Test
    public void anUnconfirmedUpdateNeedsVerificationBeforeAnotherRequest() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        worker(directory, clock, "unconfirmed", null, source);
        var jobs = new QuoteJobs(directory, clock, source);
        assertFalse(jobs.status().canRequest());
        assertEquals(429, jobs.request().httpStatus());
        assertFalse(Files.exists(directory.resolve("request.json")));
    }

    @Test
    public void anotherPortfolioAndMalformedStatusCannotTriggerAnUpdate() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        worker(directory, clock, "idle", null, "/input/other.portfolio");
        var jobs = new QuoteJobs(directory, clock, source);
        assertEquals(503, jobs.request().httpStatus());
        Files.writeString(directory.resolve("status.json"), "not JSON");
        assertEquals(503, jobs.request().httpStatus());
        assertFalse(Files.exists(directory.resolve("request.json")));
    }

    static void worker(Path directory, Clock clock, String state, String requestId, String source) throws Exception
    {
        var worker = new QuoteJobs.Worker(clock.instant().toString().replace("Z", "+00:00"), true, false, false,
                        clock.instant().minusSeconds(3600).toString(), 4, "partial", "Daily 06:41", requestId, state,
                        source, null);
        Files.writeString(directory.resolve("status.json"), new Gson().toJson(worker));
    }
}
