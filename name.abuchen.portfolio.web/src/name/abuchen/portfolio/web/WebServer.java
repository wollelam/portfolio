package name.abuchen.portfolio.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Same-origin, loopback-accessible, read-only transport, separate from the desktop REST API. */
@SuppressWarnings("nls")
final class WebServer implements AutoCloseable
{
    private record CombinedCache(DashboardService ole, DashboardService ying, String oleRevision, String yingRevision,
                    PortfolioRefresh.Snapshot snapshot)
    {
    }

    private record CurrencyCacheKey(String view, String revision, String fxVersion, String currency)
    {
    }

    private static final Map<String, String> ASSETS = Map.of("/", "index.html", "/app.js", "app.js", "/app.css", "app.css",
                    "/favicon.svg", "favicon.svg");
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final PortfolioRefresh portfolios;
    private final PortfolioRefresh yingPortfolios;
    private final MarketUpdates market;
    private volatile CombinedCache combinedCache;
    private final Map<CurrencyCacheKey, DashboardService> currencyCache = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    WebServer(InetSocketAddress address, DashboardService service) throws IOException
    {
        this(address, PortfolioRefresh.fixed(service));
    }

    WebServer(InetSocketAddress address, PortfolioRefresh portfolios) throws IOException
    {
        this(address, portfolios, MarketUpdates.disabled());
    }

    WebServer(InetSocketAddress address, PortfolioRefresh portfolios, MarketUpdates market) throws IOException
    {
        this(address, portfolios, null, market);
    }

    WebServer(InetSocketAddress address, PortfolioRefresh portfolios, PortfolioRefresh yingPortfolios,
                    MarketUpdates market) throws IOException
    {
        this.portfolios = portfolios;
        this.yingPortfolios = yingPortfolios;
        this.market = market;
        server = HttpServer.create(address, 16);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    void start()
    {
        server.start();
    }

    int port()
    {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        try (exchange)
        {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                            + "connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            var host = exchange.getRequestHeaders().getFirst("Host");
            var origins = exchange.getRequestHeaders().get("Origin");
            boolean allowedHost = host != null && (host.equals("localhost:" + port())
                            || host.equals("127.0.0.1:" + port()) || host.equals("[::1]:" + port()));
            if (!allowedHost || (origins != null && (origins.size() != 1 || !origins.getFirst().equals("http://" + host))))
            {
                json(exchange, 403, Map.of("message", "Only the local, same-origin application may access this server."));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            if ("/api/price-updates".equals(path))
            {
                if (exchange.getRequestURI().getRawQuery() != null)
                    json(exchange, 400, Map.of("message", "This action accepts no query parameters."));
                else if ("GET".equals(exchange.getRequestMethod()))
                    json(exchange, 200, market.status());
                else if ("POST".equals(exchange.getRequestMethod()))
                {
                    if (origins == null || !"refresh-prices".equals(exchange.getRequestHeaders().getFirst("X-Portfolio-Action")))
                        json(exchange, 403, Map.of("message", "A same-origin price-refresh request is required."));
                    else if (exchange.getRequestBody().readNBytes(1).length != 0)
                        json(exchange, 400, Map.of("message", "This action accepts no request body."));
                    else
                    {
                        try
                        {
                            int status = market.request();
                            if (status == 429)
                                headers.set("Retry-After", "900");
                            json(exchange, status, market.status());
                        }
                        catch (IOException failure)
                        {
                            json(exchange, 503, Map.of("message", "The update request could not be queued. Existing prices remain available."));
                        }
                    }
                }
                else
                {
                    headers.set("Allow", "GET, POST");
                    json(exchange, 405, Map.of("message", "Only status reads and price-refresh requests are supported."));
                }
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod()))
            {
                headers.set("Allow", "GET");
                json(exchange, 405, Map.of("message", "This application is read-only."));
                return;
            }
            if ("/api/icon".equals(path))
            {
                Map<String, String> iconParams;
                try
                {
                    iconParams = query(exchange.getRequestURI().getRawQuery());
                }
                catch (IllegalArgumentException malformed)
                {
                    iconParams = Map.of();
                }
                var view = iconParams.getOrDefault("view", "ole");
                if (iconParams.containsKey("view") && !validView(view) || !iconParams.keySet().stream().allMatch(k -> k.equals("id") || k.equals("v") || k.equals("view"))
                                || !iconParams.containsKey("id") || !iconParams.get("id").matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                                || (iconParams.containsKey("v") && !iconParams.get("v").matches("[0-9a-f]{64}")))
                    json(exchange, 400, Map.of("message", "A single icon UUID is required."));
                else
                {
                    var id = iconParams.get("id").toLowerCase(java.util.Locale.ROOT);
                    var image = view.equals("combined") ? portfolios.snapshot().service().icon(id) : null;
                    if (image == null)
                        image = snapshot(view).service().icon(id);
                    if (image == null && view.equals("combined"))
                        image = yingPortfolios.snapshot().service().icon(id);
                    if (image == null)
                        json(exchange, 404, Map.of("message", "Icon unavailable."));
                    else
                        send(exchange, 200, "image/png", image);
                }
            }
            else if ("/api/dashboard".equals(path))
            {
                try
                {
                    var params = query(exchange.getRequestURI().getRawQuery());
                    var view = params.getOrDefault("view", "ole");
                    if (!validView(view))
                        throw new IllegalArgumentException("Unknown portfolio view");
                    var snapshot = snapshot(view);
                    var reportingService = reportingCurrencyService(snapshot, view, params.get("currency"));
                    var service = reportingService.excluding(params.get("exclude"));
                    var to = params.containsKey("to") ? LocalDate.parse(params.get("to")) : service.defaultClosingDate();
                    var from = params.containsKey("from") ? LocalDate.parse(params.get("from")) : to.minusYears(1);
                    if ("true".equals(params.get("frames")))
                    {
                        json(exchange, 200, service.allocationFrames(from, to));
                        return;
                    }
                    var dashboard = gson.toJsonTree(service.dashboard(from, to, params.get("benchmark"), params.get("instrument"),
                                    Boolean.parseBoolean(params.getOrDefault("rankings", "false")), params.get("waterfall"), "day".equals(params.get("rankings")))).getAsJsonObject();
                    if (yingPortfolios != null && !view.equals("combined"))
                        dashboard.add("quoteKeys", gson.toJsonTree(quoteKeysForView(service)));
                    dashboard.add("refresh", gson.toJsonTree(snapshot.status()));
                    dashboard.add("market", gson.toJsonTree(market.status()));
                    dashboard.add("views", gson.toJsonTree(yingPortfolios == null ? java.util.List.of("ole") : java.util.List.of("ole", "ying", "combined")));
                    json(exchange, 200, dashboard);
                }
                catch (DashboardService.MissingReportingRateException e)
                {
                    json(exchange, 400, Map.of("message", e.getMessage()));
                }
                catch (IllegalArgumentException | DateTimeParseException e)
                {
                    json(exchange, 400, Map.of("message", "Invalid portfolio request. Check ISO dates, excluded group IDs and selected instruments. Use a recorded range with no future dates; allocation batches support at most 93 days."));
                }
                catch (IOException e)
                {
                    json(exchange, 500, Map.of("message", "The requested reporting currency could not be initialized."));
                }
                catch (RuntimeException e)
                {
                    System.err.println("Dashboard calculation failed: " + e.getClass().getSimpleName()); // NOSONAR
                    json(exchange, 500, Map.of("message", "The portfolio could not be calculated. Check its data in Portfolio Performance."));
                }
            }
            else if (ASSETS.containsKey(path))
            {
                try (var input = WebServer.class.getResourceAsStream("assets/" + ASSETS.get(path)))
                {
                    if (input == null)
                        throw new IOException("Missing bundled asset");
                    String type = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css"
                                    : path.endsWith(".svg") ? "image/svg+xml" : "text/html";
                    send(exchange, 200, type + "; charset=utf-8", input.readAllBytes());
                }
            }
            else
                json(exchange, 404, Map.of("message", "Not found"));
        }
    }

    private Map<String, String> query(String raw)
    {
        var params = new HashMap<String, String>();
        if (raw == null || raw.isEmpty())
            return params;
        for (var pair : raw.split("&", -1))
        {
            var parts = pair.split("=", 2);
            var key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!(key.equals("exclude") || key.equals("frames") || key.equals("from") || key.equals("to") || key.equals("benchmark") || key.equals("instrument") || key.equals("rankings") || key.equals("waterfall") || key.equals("view") || key.equals("currency") || key.equals("id") || key.equals("v")) || parts.length != 2
                            || params.putIfAbsent(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException("Unknown or duplicate parameter");
        }
        if (params.containsKey("frames") && !params.get("frames").equals("true"))
            throw new IllegalArgumentException("Invalid allocation frame flag");
        if (params.containsKey("rankings") && !(params.get("rankings").equals("true") || params.get("rankings").equals("false") || params.get("rankings").equals("day")))
            throw new IllegalArgumentException("Invalid rankings flag");
        if (params.containsKey("instrument") && !params.get("instrument")
                        .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid instrument UUID");
        if (params.containsKey("currency") && !validReportingCurrency(params.get("currency")))
            throw new IllegalArgumentException("Unsupported reporting currency");
        return params;
    }

    private boolean validReportingCurrency(String currency)
    {
        return currency.equals("CHF") || currency.equals("DKK") || currency.equals("THB") || currency.equals("USD");
    }

    private synchronized DashboardService reportingCurrencyService(PortfolioRefresh.Snapshot snapshot, String view,
                    String requestedCurrency) throws IOException
    {
        var base = snapshot.service();
        if (requestedCurrency == null && !view.equals("combined"))
            return base;
        var currency = requestedCurrency == null ? base.reportingCurrency() : requestedCurrency;
        var key = new CurrencyCacheKey(view, snapshot.revision(), base.fxVersion(), currency);
        var cached = currencyCache.get(key);
        if (cached != null)
            return cached;
        var service = base.withCurrency(currency);
        currencyCache.entrySet().removeIf(entry -> entry.getKey().view().equals(view)
                        && (!java.util.Objects.equals(entry.getKey().revision(), snapshot.revision())
                                        || !java.util.Objects.equals(entry.getKey().fxVersion(), base.fxVersion())));
        if (currencyCache.size() >= 12)
            currencyCache.remove(currencyCache.keySet().iterator().next());
        currencyCache.put(key, service);
        return service;
    }

    private boolean validView(String view)
    {
        return view.equals("ole") || (yingPortfolios != null && (view.equals("ying") || view.equals("combined")));
    }

    private Map<String, java.util.List<String>> quoteKeysForView(DashboardService service)
    {
        var aliases = new HashMap<String, java.util.List<String>>();
        snapshot("combined").service().quoteKeys().values().forEach(keys -> keys.forEach(uuid -> aliases.put(uuid, keys)));
        var selected = new HashMap<String, java.util.List<String>>();
        service.quoteKeys().keySet().forEach(uuid -> selected.put(uuid, aliases.getOrDefault(uuid, java.util.List.of(uuid))));
        return Map.copyOf(selected);
    }

    private PortfolioRefresh.Snapshot snapshot(String view)
    {
        if (view.equals("ole") || yingPortfolios == null)
            return portfolios.snapshot();
        if (view.equals("ying"))
            return yingPortfolios.snapshot();
        var ole = portfolios.snapshot();
        var ying = yingPortfolios.snapshot();
        var cached = combinedCache;
        if (cached != null && cached.ole() == ole.service() && cached.ying() == ying.service()
                        && java.util.Objects.equals(cached.oleRevision(), ole.revision())
                        && java.util.Objects.equals(cached.yingRevision(), ying.revision()))
            return new PortfolioRefresh.Snapshot(cached.snapshot().service(), cached.snapshot().revision(), ole.status());
        synchronized (this)
        {
            cached = combinedCache;
            if (cached == null || cached.ole() != ole.service() || cached.ying() != ying.service()
                            || !java.util.Objects.equals(cached.oleRevision(), ole.revision())
                            || !java.util.Objects.equals(cached.yingRevision(), ying.revision()))
            {
                var combined = new PortfolioRefresh.Snapshot(DashboardService.combined(ole.service(), ying.service()),
                                ole.revision() + ":" + ying.revision(), ole.status());
                combinedCache = new CombinedCache(ole.service(), ying.service(), ole.revision(), ying.revision(), combined);
                cached = combinedCache;
            }
            return new PortfolioRefresh.Snapshot(cached.snapshot().service(), cached.snapshot().revision(), ole.status());
        }
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException
    {
        send(exchange, status, "application/json; charset=utf-8", gson.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }
}
