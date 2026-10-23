package name.abuchen.portfolio.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@SuppressWarnings("nls")
public class PortfolioRefreshTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final MutableClock clock = new MutableClock();

    @Test
    public void waitsForAStableRevisionAndKeepsCapturedRequestsConsistent() throws Exception
    {
        var file = fixture();
        var source = source(file);
        try (var refresh = refresh(source))
        {
            var first = refresh.snapshot();
            var updated = update(file);
            refresh.refresh();
            assertTrue(refresh.snapshot().status().pending());
            assertEquals(first.revision(), refresh.snapshot().revision());
            clock.advance(4);
            refresh.refresh();
            assertEquals(first.revision(), refresh.snapshot().revision());
            clock.advance(1);
            refresh.refresh();
            assertNotEquals(first.revision(), refresh.snapshot().revision());
            assertFalse(refresh.snapshot().status().pending());
            assertNull(refresh.snapshot().status().error());
            assertEquals(first.revision(), report(first).revision());
            assertEquals(refresh.snapshot().revision(), report(refresh.snapshot()).revision());
            assertArrayEquals(updated, Files.readAllBytes(file));
        }
    }

    @Test
    public void partialOrMissingFilesKeepLastGoodDataAndRecoverWithoutRestart() throws Exception
    {
        var file = fixture();
        var originalBytes = Files.readAllBytes(file);
        var source = source(file);
        try (var refresh = refresh(source))
        {
            var first = refresh.snapshot();
            Files.writeString(file, "<client>");
            refresh.refresh();
            clock.advance(5);
            refresh.refresh();
            assertSame(first.service(), refresh.snapshot().service());
            assertNotNull(refresh.snapshot().status().error());
            assertFalse(refresh.snapshot().status().error().contains(file.toString()));
            try (var restarted = refresh(source))
            {
                assertEquals(first.revision(), restarted.snapshot().revision());
                assertNotNull(restarted.snapshot().status().error());
            }
            Files.delete(file);
            refresh.refresh();
            assertEquals(first.revision(), refresh.snapshot().revision());
            assertNotNull(refresh.snapshot().status().error());
            Files.write(file, originalBytes);
            update(file);
            refresh.refresh();
            clock.advance(5);
            refresh.refresh();
            assertNotEquals(first.revision(), refresh.snapshot().revision());
            assertNull(refresh.snapshot().status().error());
            assertFalse(refresh.snapshot().status().pending());
        }
    }

    @Test
    public void unchangedChecksDoNotDeserializeThePortfolioAgain() throws Exception
    {
        var delegate = source(fixture());
        var loads = new AtomicInteger();
        var counted = new PortfolioSource()
        {
            @Override
            public LoadedPortfolio load() throws IOException
            {
                loads.incrementAndGet();
                return delegate.load();
            }

            @Override
            public String revision() throws IOException
            {
                return delegate.revision();
            }
        };
        try (var refresh = refresh(counted))
        {
            for (int ii = 0; ii < 3; ii++)
            {
                clock.advance(30);
                refresh.refresh();
            }
            assertEquals(1, loads.get());
            assertEquals(clock.instant().toString(), refresh.snapshot().status().lastCheckedAt());
        }
    }

    @Test
    public void aSourceChangeBeforeAcceptanceCannotOverwriteRecoveryData() throws Exception
    {
        var file = fixture();
        var source = source(file);
        var first = source.load();
        source.accept(first);
        update(file);
        assertThrows(IOException.class, () -> source.accept(first));
        assertEquals(first.revision(), source.lastGood().revision());
    }

    @Test
    public void recoveryCopyCannotBeUsedForADifferentSourceFile() throws Exception
    {
        var cache = temporary.newFolder().toPath();
        var first = PortfolioSource.file(fixture(), () -> null, cache);
        first.accept(first.load());
        var other = PortfolioSource.file(temporary.getRoot().toPath().resolve("missing.portfolio"), () -> null, cache);
        assertThrows(IOException.class, () -> refresh(other));
    }

    @Test
    public void aRevisionThatChangesDuringLoadIsNotPublished() throws Exception
    {
        var original = DemoPortfolio.create(LocalDate.now(clock));
        var source = new PortfolioSource()
        {
            private int loads;

            @Override
            public LoadedPortfolio load()
            {
                return loads++ == 0 ? original : new LoadedPortfolio(original.client(), original.label(), "raced",
                                original.loadedAt(), true);
            }

            @Override
            public String revision()
            {
                return "expected";
            }
        };
        try (var refresh = refresh(source))
        {
            refresh.refresh();
            clock.advance(5);
            refresh.refresh();
            assertEquals(original.revision(), refresh.snapshot().revision());
            assertNotNull(refresh.snapshot().status().error());
        }
    }

    private DashboardService.Dashboard report(PortfolioRefresh.Snapshot snapshot)
    {
        var to = LocalDate.now(clock);
        return snapshot.service().dashboard(to.minusYears(1), to);
    }

    private PortfolioRefresh refresh(PortfolioSource source) throws IOException
    {
        return new PortfolioRefresh(source, clock, List.of(), 30, Duration.ofSeconds(5));
    }

    private PortfolioSource source(Path file) throws IOException
    {
        return PortfolioSource.file(file, () -> null, temporary.newFolder().toPath());
    }

    private Path fixture() throws IOException
    {
        var file = temporary.newFile().toPath();
        try (var input = getClass().getResourceAsStream("/scenarios/currency_sample.xml"))
        {
            Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private byte[] update(Path file) throws IOException
    {
        var xml = Files.readString(file).replace("Apple", "Updated Apple");
        Files.writeString(file, xml);
        return Files.readAllBytes(file);
    }

    private static final class MutableClock extends Clock
    {
        private Instant now = Instant.parse("2025-06-12T12:00:00Z");

        void advance(long seconds)
        {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
