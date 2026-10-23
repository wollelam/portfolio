package name.abuchen.portfolio.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
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
    private static final Map<String, String> ASSETS = Map.of("/", "index.html", "/app.js", "app.js", "/app.css", "app.css",
                    "/favicon.svg", "favicon.svg");
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final DashboardService service;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    WebServer(InetSocketAddress address, DashboardService service) throws IOException
    {
        this.service = service;
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
            if (!"GET".equals(exchange.getRequestMethod()))
            {
                headers.set("Allow", "GET");
                json(exchange, 405, Map.of("message", "This application is read-only."));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            if ("/api/dashboard".equals(path))
            {
                try
                {
                    var params = query(exchange.getRequestURI().getRawQuery());
                    var to = params.containsKey("to") ? LocalDate.parse(params.get("to")) : service.defaultClosingDate();
                    var from = params.containsKey("from") ? LocalDate.parse(params.get("from")) : to.minusYears(1);
                    json(exchange, 200, service.dashboard(from, to));
                }
                catch (IllegalArgumentException | DateTimeParseException e)
                {
                    json(exchange, 400, Map.of("message", "Invalid date range. Use ISO dates, from before to, no future dates, and at most 10 years."));
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
            if (!(key.equals("from") || key.equals("to")) || parts.length != 2
                            || params.putIfAbsent(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException("Unknown or duplicate parameter");
        }
        return params;
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
