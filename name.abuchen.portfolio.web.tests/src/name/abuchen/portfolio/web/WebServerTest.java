package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.google.gson.JsonParser;

@SuppressWarnings("nls")
public class WebServerTest
{
    @Test
    public void dashboardUsesTheLatestAcceptedSnapshotAndIncludesRefreshStatus() throws Exception
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var loaded = new AtomicReference<>(DemoPortfolio.create(today));
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
        try (var portfolios = new PortfolioRefresh(source, clock, List.of(), 30, Duration.ZERO);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), portfolios);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var url = "http://127.0.0.1:" + server.port() + "/api/dashboard";
            var first = JsonParser.parseString(get(client, url).body()).getAsJsonObject();
            assertTrue(first.getAsJsonObject("refresh").get("automatic").getAsBoolean());
            assertEquals(30, first.getAsJsonObject("refresh").get("intervalSeconds").getAsInt());
            var replacement = DemoPortfolio.create(today);
            loaded.set(new PortfolioSource.LoadedPortfolio(replacement.client(), "Updated demo", "new-revision",
                            replacement.loadedAt(), true));
            portfolios.refresh();
            var updated = JsonParser.parseString(get(client, url).body()).getAsJsonObject();
            assertEquals("new-revision", updated.get("revision").getAsString());
            assertEquals("Updated demo", updated.get("label").getAsString());
        }
    }

    @Test
    public void servesDashboardAndAssetsAndRejectsCrossOriginAndInvalidRequests() throws Exception
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var service = new DashboardService(DemoPortfolio.create(today), clock, List.of());
        try (var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), service);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            var dashboard = get(client, base + "/api/dashboard");
            assertEquals(200, dashboard.statusCode());
            assertEquals(today.toString(), JsonParser.parseString(dashboard.body()).getAsJsonObject().get("asOf").getAsString());
            assertTrue(JsonParser.parseString(dashboard.body()).getAsJsonObject().getAsJsonArray("series").size() > 300);
            assertEquals("no-store", dashboard.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(200, get(client, base + "/").statusCode());
            assertEquals(200, get(client, base + "/app.js").statusCode());
            assertEquals(200, get(client, base + "/app.css").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?to=bad").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?from=2025-01-01&from=2025-02-01").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?currency=USD").statusCode());
            assertEquals(404, get(client, base + "/META-INF/MANIFEST.MF").statusCode());
            var crossOrigin = HttpRequest.newBuilder(URI.create(base + "/api/dashboard"))
                            .header("Origin", "https://untrusted.example").build();
            assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            var sameOrigin = HttpRequest.newBuilder(URI.create(base + "/api/dashboard"))
                            .header("Origin", base).build();
            assertEquals(200, client.send(sameOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            var post = HttpRequest.newBuilder(URI.create(base + "/api/dashboard"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception
    {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
