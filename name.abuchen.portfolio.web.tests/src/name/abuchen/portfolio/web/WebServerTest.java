package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProvider;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;

@SuppressWarnings("nls")
public class WebServerTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void liveQuoteStatusCannotChangeSavedPortfolioValuations() throws Exception
    {
        var today = LocalDate.of(2026, 10, 23);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var file = temporary.newFolder().toPath().resolve("latest.json");
        var service = new DashboardService(DemoPortfolio.create(today), clock, List.of());
        try (var market = new MarketUpdates(new QuoteJobs(null, clock), null, clock, new LiveQuotes(file, clock));
                        var portfolios = PortfolioRefresh.fixed(service);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), portfolios, market);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            var before = JsonParser.parseString(get(client, base + "/api/dashboard").body()).getAsJsonObject();
            Files.writeString(file, new Gson().toJson(new LiveQuotes.Frame(1, "demo", "test-session",
                            clock.instant().toString(), true, "running", null, List.of())));
            var status = JsonParser.parseString(get(client, base + "/api/price-updates").body()).getAsJsonObject();
            assertEquals("demo", status.getAsJsonObject("liveQuotes").get("mode").getAsString());
            assertEquals(false, status.getAsJsonObject("liveQuotes").get("connected").getAsBoolean());
            var after = JsonParser.parseString(get(client, base + "/api/dashboard").body()).getAsJsonObject();
            assertEquals(before.get("totalValue"), after.get("totalValue"));
            assertEquals(before.get("revision"), after.get("revision"));
            assertEquals(400, get(client, base + "/api/dashboard?waterfall=unknown").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?waterfall=calculation&waterfall=contributions").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?rankings=unknown").statusCode());
            var dailyResponse = get(client, base + "/api/dashboard?rankings=day");
            assertEquals(200, dailyResponse.statusCode());
            assertTrue(JsonParser.parseString(dailyResponse.body()).getAsJsonObject()
                            .getAsJsonObject("dailyRankings").getAsJsonArray("securities").size() > 0);
            var breakdownResponse = get(client, base + "/api/dashboard?waterfall=calculation");
            assertEquals(200, breakdownResponse.statusCode());
            var breakdown = JsonParser.parseString(breakdownResponse.body()).getAsJsonObject().getAsJsonObject("waterfall");
            assertTrue(breakdown.getAsJsonArray("entries").size() > 1);
        }
    }

    @Test
    public void priceRequestsRequireAnExplicitSameOriginActionAndCannotBeRepeated() throws Exception
    {
        var clock = Clock.fixed(LocalDate.of(2025, 6, 12).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var directory = temporary.newFolder().toPath();
        var source = "/input/test.portfolio";
        QuoteJobsTest.worker(directory, clock, "idle", null, source);
        var jobs = new QuoteJobs(directory, clock, source);
        var service = new DashboardService(DemoPortfolio.create(LocalDate.now(clock)), clock, List.of());
        try (var market = new MarketUpdates(jobs, null, clock);
                        var portfolios = PortfolioRefresh.fixed(service);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), portfolios, market);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            var uri = URI.create(base + "/api/price-updates");
            assertEquals(200, get(client, uri.toString()).statusCode());
            assertTrue(!Files.exists(directory.resolve("request.json")));
            assertEquals(403, client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(HttpRequest.newBuilder(uri).header("Origin", base)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(HttpRequest.newBuilder(uri).header("Origin", "https://untrusted.example")
                            .header("X-Portfolio-Action", "refresh-prices").POST(HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(uri).header("Origin", base)
                            .header("X-Portfolio-Action", "refresh-prices").POST(HttpRequest.BodyPublishers.ofString("data"))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(!Files.exists(directory.resolve("request.json")));
            var action = HttpRequest.newBuilder(uri).header("Origin", base)
                            .header("X-Portfolio-Action", "refresh-prices").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(202, client.send(action, HttpResponse.BodyHandlers.ofString()).statusCode());
            var identifier = jobs.status().requestId();
            assertEquals(202, client.send(action, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(identifier, jobs.status().requestId());
            QuoteJobsTest.worker(directory, clock, "complete", identifier, source);
            assertEquals(429, client.send(action, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, get(client, uri + "?request=anything").statusCode());
        }
    }

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
            assertTrue(JsonParser.parseString(dashboard.body()).getAsJsonObject().get("rankings").isJsonNull());
            assertEquals(200, get(client, base + "/api/dashboard?rankings=true").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?rankings=maybe").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?rankings=true&rankings=false").statusCode());
            assertEquals(today.toString(), JsonParser.parseString(dashboard.body()).getAsJsonObject().get("asOf").getAsString());
            assertTrue(JsonParser.parseString(dashboard.body()).getAsJsonObject().getAsJsonArray("series").size() > 300);
            assertEquals("no-store", dashboard.headers().firstValue("Cache-Control").orElseThrow());
            var body = JsonParser.parseString(dashboard.body()).getAsJsonObject();
            var benchmarkId = body.getAsJsonArray("benchmarks").get(0).getAsJsonObject().get("uuid").getAsString();
            var comparison = JsonParser.parseString(get(client, base + "/api/dashboard?benchmark=" + benchmarkId).body()).getAsJsonObject();
            assertEquals(benchmarkId, comparison.getAsJsonObject("benchmark").get("uuid").getAsString());
            assertEquals(body.getAsJsonArray("series").size(), comparison.getAsJsonObject("benchmark").getAsJsonArray("series").size());
            var instrumentId = body.getAsJsonArray("holdings").get(0).getAsJsonObject().get("uuid").getAsString();
            var instrument = JsonParser.parseString(get(client, base + "/api/dashboard?instrument=" + instrumentId).body()).getAsJsonObject();
            assertEquals(instrumentId, instrument.getAsJsonObject("instrument").get("uuid").getAsString());
            assertEquals(4, instrument.getAsJsonObject("instrument").getAsJsonObject("activity")
                            .getAsJsonArray("dividends").size());
            assertEquals(200, get(client, base + "/api/dashboard?benchmark=removed").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?benchmark=one&benchmark=two").statusCode());
            assertEquals(200, get(client, base + "/").statusCode());
            assertEquals(200, get(client, base + "/app.js").statusCode());
            assertEquals(200, get(client, base + "/app.css").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?to=bad").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?from=2025-01-01&from=2025-02-01").statusCode());
            assertEquals(200, get(client, base + "/api/dashboard?currency=USD").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?currency=EUR").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?instrument=malformed").statusCode());
            assertEquals(400, get(client, base + "/api/dashboard?instrument=" + instrumentId + "&instrument=" + instrumentId).statusCode());
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

    @Test
    public void selectsIndependentAndCombinedPortfolioViews() throws Exception
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var primaryPortfolio = DemoPortfolio.create(today);
        var secondaryPortfolio = DemoPortfolio.create(today);
        for (int ii = 0; ii < primaryPortfolio.client().getSecurities().size(); ii++)
        {
            primaryPortfolio.client().getSecurities().get(ii).setIsin("TEST-" + ii);
            secondaryPortfolio.client().getSecurities().get(ii).setIsin("TEST-" + ii);
        }
        var primary = new DashboardService(primaryPortfolio, clock, List.of());
        var secondary = new DashboardService(secondaryPortfolio, clock, List.of());
        try (var primaryRefresh = PortfolioRefresh.fixed(primary); var secondaryRefresh = PortfolioRefresh.fixed(secondary);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), primaryRefresh, secondaryRefresh, MarketUpdates.disabled());
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            var defaultView = JsonParser.parseString(get(client, base + "/api/dashboard").body()).getAsJsonObject();
            assertEquals("EUR", defaultView.get("currency").getAsString());
            assertEquals("[\"primary\",\"secondary\",\"combined\"]", defaultView.getAsJsonArray("views").toString());
            var secondaryView = JsonParser.parseString(get(client, base + "/api/dashboard?view=secondary").body()).getAsJsonObject();
            assertEquals(secondaryView.get("totalValue"), defaultView.get("totalValue"));
            var combined = JsonParser.parseString(get(client, base + "/api/dashboard?view=combined").body()).getAsJsonObject();
            assertEquals(defaultView.get("totalValue").getAsLong() * 2, combined.get("totalValue").getAsLong());
            assertEquals(1, combined.getAsJsonArray("holdings").asList().stream()
                            .filter(h -> "security".equals(h.getAsJsonObject().get("type").getAsString())).count());
            assertEquals(2, combined.getAsJsonArray("holdings").asList().stream()
                            .filter(h -> "cash".equals(h.getAsJsonObject().get("type").getAsString())).count());
            assertTrue(combined.getAsJsonObject("quoteKeys").size() > 0);
            var primarySecurity = primaryPortfolio.client().getSecurities().getFirst().getUUID();
            var secondarySecurity = secondaryPortfolio.client().getSecurities().getFirst().getUUID();
            var sharedKeys = "[\"" + primarySecurity + "\",\"" + secondarySecurity + "\"]";
            assertEquals(sharedKeys, defaultView.getAsJsonObject("quoteKeys").getAsJsonArray(primarySecurity).toString());
            assertEquals(sharedKeys, secondaryView.getAsJsonObject("quoteKeys").getAsJsonArray(secondarySecurity).toString());
            assertEquals("[\"" + primarySecurity + "\",\"" + secondarySecurity + "\"]",
                            combined.getAsJsonObject("quoteKeys").getAsJsonArray(primarySecurity).toString());
            var excluded = JsonParser.parseString(get(client, base + "/api/dashboard?view=combined&exclude=a-"
                            + primaryPortfolio.client().getAccounts().getFirst().getUUID()).body()).getAsJsonObject();
            assertEquals(combined.getAsJsonObject("quoteKeys"), excluded.getAsJsonObject("quoteKeys"));
            assertEquals(400, get(client, base + "/api/dashboard?view=unknown").statusCode());
        }
    }

    @Test
    public void selectedReportingCurrenciesConvertEachViewAndPreserveCombinedSums() throws Exception
    {
        var today = LocalDate.of(2025, 6, 12);
        var from = today.minusYears(1);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var primaryPortfolio = DemoPortfolio.create(today);
        var secondaryPortfolio = DemoPortfolio.create(today);
        for (int ii = 0; ii < primaryPortfolio.client().getSecurities().size(); ii++)
        {
            primaryPortfolio.client().getSecurities().get(ii).setIsin("CURRENCY-" + ii);
            secondaryPortfolio.client().getSecurities().get(ii).setIsin("CURRENCY-" + ii);
        }
        var rates = reportingCurrencyRates(today);
        var primary = new DashboardService(primaryPortfolio, clock, List.of(), rates);
        var secondary = new DashboardService(secondaryPortfolio, clock, List.of(), rates);
        var instrument = primaryPortfolio.client().getSecurities().getFirst().getUUID();
        var secondaryInstrument = secondaryPortfolio.client().getSecurities().getFirst().getUUID();
        try (var primaryRefresh = PortfolioRefresh.fixed(primary); var secondaryRefresh = PortfolioRefresh.fixed(secondary);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), primaryRefresh, secondaryRefresh,
                                        MarketUpdates.disabled());
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port() + "/api/dashboard";
            for (var currency : List.of("CHF", "DKK", "THB", "USD"))
            {
                var common = "&from=" + from + "&to=" + today + "&rankings=true&waterfall=contributions";
                var primaryReport = JsonParser.parseString(get(client, base + "?currency=" + currency + "&view=primary&instrument="
                                + instrument + common).body())
                                .getAsJsonObject();
                var secondaryReport = JsonParser.parseString(get(client, base + "?currency=" + currency + "&view=secondary&instrument="
                                + secondaryInstrument + common).body())
                                .getAsJsonObject();
                var combined = JsonParser.parseString(get(client, base + "?currency=" + currency + "&view=combined&instrument="
                                + instrument + common).body())
                                .getAsJsonObject();

                assertEquals(currency, combined.get("currency").getAsString());
                assertEquals(primaryReport.get("totalValue").getAsLong() + secondaryReport.get("totalValue").getAsLong(),
                                combined.get("totalValue").getAsLong());
                assertEquals(primaryReport.getAsJsonArray("holdings").asList().stream()
                                .mapToLong(row -> row.getAsJsonObject().get("value").getAsLong()).sum()
                                + secondaryReport.getAsJsonArray("holdings").asList().stream()
                                                .mapToLong(row -> row.getAsJsonObject().get("value").getAsLong()).sum(),
                                combined.getAsJsonArray("holdings").asList().stream()
                                                .mapToLong(row -> row.getAsJsonObject().get("value").getAsLong()).sum());
                for (var field : List.of("valueChange", "netFlows", "gain"))
                    assertEquals(primaryReport.getAsJsonObject("period").get(field).getAsLong()
                                    + secondaryReport.getAsJsonObject("period").get(field).getAsLong(),
                                    combined.getAsJsonObject("period").get(field).getAsLong());
                assertEquals(primaryReport.getAsJsonObject("instrument").get("gain").getAsLong()
                                + secondaryReport.getAsJsonObject("instrument").get("gain").getAsLong(),
                                combined.getAsJsonObject("instrument").get("gain").getAsLong());
                assertEquals(primaryReport.getAsJsonObject("instrument").get("dividends").getAsLong()
                                + secondaryReport.getAsJsonObject("instrument").get("dividends").getAsLong(),
                                combined.getAsJsonObject("instrument").get("dividends").getAsLong());
                var primaryRank = primaryReport.getAsJsonObject("rankings").getAsJsonArray("securities").asList().stream()
                                .map(element -> element.getAsJsonObject()).filter(entry -> entry.get("uuid").getAsString().equals(instrument))
                                .findFirst().orElseThrow();
                var secondaryRank = secondaryReport.getAsJsonObject("rankings").getAsJsonArray("securities").asList().stream()
                                .map(element -> element.getAsJsonObject()).filter(entry -> entry.get("uuid").getAsString().equals(secondaryInstrument))
                                .findFirst().orElseThrow();
                var combinedRank = combined.getAsJsonObject("rankings").getAsJsonArray("securities").asList().stream()
                                .map(element -> element.getAsJsonObject()).filter(entry -> entry.get("uuid").getAsString().equals(instrument))
                                .findFirst().orElseThrow();
                assertEquals(primaryRank.get("gain").getAsLong() + secondaryRank.get("gain").getAsLong(),
                                combinedRank.get("gain").getAsLong());
                var primaryWaterfallTotal = primaryReport.getAsJsonObject("waterfall").getAsJsonArray("entries").get(primaryReport
                                .getAsJsonObject("waterfall").getAsJsonArray("entries").size() - 1).getAsJsonObject()
                                .get("amount").getAsLong();
                var secondaryWaterfallTotal = secondaryReport.getAsJsonObject("waterfall").getAsJsonArray("entries").get(secondaryReport
                                .getAsJsonObject("waterfall").getAsJsonArray("entries").size() - 1).getAsJsonObject()
                                .get("amount").getAsLong();
                var combinedEntries = combined.getAsJsonObject("waterfall").getAsJsonArray("entries");
                assertEquals(primaryWaterfallTotal + secondaryWaterfallTotal,
                                combinedEntries.get(combinedEntries.size() - 1).getAsJsonObject().get("amount").getAsLong());
                var combinedChanges = combinedEntries.asList().stream().map(element -> element.getAsJsonObject())
                                .filter(entry -> entry.get("kind").getAsString().equals("CHANGE"))
                                .mapToLong(entry -> entry.get("amount").getAsLong()).sum();
                assertEquals(primaryWaterfallTotal + secondaryWaterfallTotal, combinedChanges);

                var frameQuery = "&frames=true&from=" + today.minusDays(3) + "&to=" + today.minusDays(1);
                var primaryFrames = JsonParser.parseString(get(client, base + "?currency=" + currency + "&view=primary" + frameQuery).body())
                                .getAsJsonObject().getAsJsonArray("frames");
                var secondaryFrames = JsonParser.parseString(get(client, base + "?currency=" + currency + "&view=secondary" + frameQuery).body())
                                .getAsJsonObject().getAsJsonArray("frames");
                var combinedFrames = JsonParser.parseString(get(client,
                                base + "?currency=" + currency + "&view=combined" + frameQuery).body())
                                .getAsJsonObject().getAsJsonArray("frames");
                assertEquals(primaryFrames.size(), combinedFrames.size());
                for (int ii = 0; ii < primaryFrames.size(); ii++)
                    assertEquals(primaryFrames.get(ii).getAsJsonObject().get("totalValue").getAsLong()
                                    + secondaryFrames.get(ii).getAsJsonObject().get("totalValue").getAsLong(),
                                    combinedFrames.get(ii).getAsJsonObject().get("totalValue").getAsLong());
            }
        }
    }

    private FxRates.View reportingCurrencyRates(LocalDate date)
    {
        ExchangeRateProvider provider = new ExchangeRateProvider()
        {
            private final List<ExchangeRateTimeSeries> series = List.of("CHF", "DKK", "THB", "USD").stream()
                            .map(currency -> {
                                var result = new ExchangeRateTimeSeriesImpl(this, "EUR", currency);
                                var rate = switch (currency)
                                {
                                    case "CHF" -> "0.94";
                                    case "DKK" -> "6.90";
                                    case "THB" -> "36.20";
                                    default -> "1.10";
                                };
                                result.addRate(new ExchangeRate(LocalDate.of(2020, 1, 1), new BigDecimal(rate)));
                                return (ExchangeRateTimeSeries) result;
                            }).toList();

            @Override
            public String getName()
            {
                return "test reporting currencies";
            }

            @Override
            public List<ExchangeRateTimeSeries> getAvailableTimeSeries(Client client)
            {
                return series;
            }
        };
        return new FxRates.View("reporting-currency-test-" + date, provider.getAvailableTimeSeries(new Client()));
    }

    @Test
    public void selectedReportingCurrencyRejectsMissingExchangeRates() throws Exception
    {
        var today = LocalDate.of(2025, 6, 12);
        var clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var loaded = DemoPortfolio.create(today);
        loaded.client().addSecurity(new Security("Unquoted currency", "ZZZ"));
        var service = new DashboardService(loaded, clock, List.of());
        try (var portfolios = PortfolioRefresh.fixed(service);
                        var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), portfolios);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var response = get(client, "http://127.0.0.1:" + server.port() + "/api/dashboard?currency=USD");
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("Missing exchange rate"));
        }
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception
    {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
