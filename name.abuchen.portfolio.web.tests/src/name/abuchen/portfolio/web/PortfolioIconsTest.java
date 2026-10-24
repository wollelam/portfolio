package name.abuchen.portfolio.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;

@SuppressWarnings("nls")
public class PortfolioIconsTest
{
    static String png(int color) throws Exception
    {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        var output = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(output))
        {
            ImageIO.write(image, "png", stream);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private PortfolioSource.LoadedPortfolio fixture(LocalDate today, int color) throws Exception
    {
        var loaded = DemoPortfolio.create(today);
        var securityLogo = loaded.client().getSettings().getOptionalLogoAttributeType(Security.class).orElseThrow();
        loaded.client().getSecurities().getFirst().getAttributes().put(securityLogo, png(color));
        var accountLogo = loaded.client().getSettings().getOptionalLogoAttributeType(Account.class).orElseThrow();
        loaded.client().getAccounts().getFirst().getAttributes().put(accountLogo, png(color));
        return loaded;
    }

    @Test
    public void embeddedLogosDoNotChangeFinancialResults() throws Exception
    {
        var today = LocalDate.of(2026, 10, 23);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var loaded = DemoPortfolio.create(today);
        var before = new DashboardService(loaded, clock, List.of()).dashboard(today.minusYears(1), today);
        var logo = loaded.client().getSettings().getOptionalLogoAttributeType(Security.class).orElseThrow();
        loaded.client().getSecurities().getFirst().getAttributes().put(logo, png(0xff00aa00));
        var after = new DashboardService(loaded, clock, List.of()).dashboard(today.minusYears(1), today);
        assertEquals(before.totalValue(), after.totalValue());
        assertEquals(before.day(), after.day());
        assertEquals(before.week(), after.week());
        assertEquals(before.period(), after.period());
        assertEquals(before.series(), after.series());
        assertEquals(before.dividends(), after.dividends());
        assertEquals(before.warnings(), after.warnings());
        assertEquals(before.holdings().stream().map(DashboardService.Holding::value).toList(),
                        after.holdings().stream().map(DashboardService.Holding::value).toList());
    }

    @Test
    public void rejectsNonEmbeddedMalformedAndOversizedImages() throws Exception
    {
        assertNull(PortfolioIcons.decode(null));
        assertNull(PortfolioIcons.decode("https://example.invalid/logo.png"));
        assertNull(PortfolioIcons.decode("file:///private.png"));
        assertNull(PortfolioIcons.decode("data:image/svg+xml;base64,PHN2Zz4="));
        assertNull(PortfolioIcons.decode("data:image/png;base64,not-an-image"));
        assertNull(PortfolioIcons.decode("data:image/png;base64," + "A".repeat(180000)));
        var bytes = Base64.getDecoder().decode(png(0xff00aa00).split(",")[1]);
        ByteBuffer.wrap(bytes).putInt(16, 100000);
        assertNull(PortfolioIcons.decode("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)));
        assertNotNull(PortfolioIcons.decode(png(0xff00aa00)));
    }

    @Test
    public void servesOnlySnapshotLogosAndReplacesThemOnReload() throws Exception
    {
        var today = LocalDate.of(2026, 10, 23);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var loaded = new AtomicReference<>(fixture(today, 0xff00aa00));
        var source = new PortfolioSource()
        {
            @Override
            public LoadedPortfolio load()
            {
                return loaded.get();
            }

            @Override
            public String revision()
            {
                return loaded.get().revision();
            }
        };
        var first = loaded.get();
        var uuid = first.client().getSecurities().getFirst().getUUID();
        var service = new DashboardService(first, clock, List.of());
        var dashboard = service.dashboard(today.minusMonths(1), today);
        assertEquals(2, dashboard.icons().size());
        assertTrue(dashboard.icons().get(uuid).startsWith("/api/icon?id=" + uuid + "&v="));
        assertTrue(dashboard.holdings().stream().anyMatch(h -> h.type().equals("cash") && h.icon() != null && h.uuid() == null));
        var copy = service.icon(uuid);
        copy[0] = 0;
        assertEquals((byte) 0x89, service.icon(uuid)[0]);
        try (var portfolios = new PortfolioRefresh(source, clock, List.of(), 30, Duration.ZERO);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), portfolios);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            var url = base + dashboard.icons().get(uuid);
            var image = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, image.statusCode());
            assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", image.headers().firstValue("Cache-Control").orElseThrow());
            assertArrayEquals(service.icon(uuid), image.body());
            for (var query : List.of("", "?id=../private", "?id=" + uuid + "&id=" + uuid, "?file=logo.png"))
                assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + "/api/icon" + query)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "/api/icon?id=00000000-0000-0000-0000-000000000000")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(url)).header("Origin", "https://untrusted.example").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var replacement = ClientFactory.duplicate(first.client());
            var logo = replacement.getSettings().getOptionalLogoAttributeType(Security.class).orElseThrow();
            replacement.getSecurities().getFirst().getAttributes().put(logo, png(0xffaa0000));
            loaded.set(new PortfolioSource.LoadedPortfolio(replacement, "Updated demo", "new-icons", clock.instant(), true));
            portfolios.refresh();
            var updated = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, updated.statusCode());
            assertArrayEquals(new DashboardService(loaded.get(), clock, List.of()).icon(uuid), updated.body());
            assertTrue(!java.util.Arrays.equals(image.body(), updated.body()));
            assertTrue(!dashboard.icons().get(uuid).equals(new DashboardService(loaded.get(), clock, List.of())
                            .dashboard(today.minusMonths(1), today).icons().get(uuid)));
            loaded.set(new PortfolioSource.LoadedPortfolio(DemoPortfolio.create(today).client(), "No logos", "removed-icons", clock.instant(), true));
            portfolios.refresh();
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
