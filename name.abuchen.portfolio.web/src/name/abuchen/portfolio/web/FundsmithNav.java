package name.abuchen.portfolio.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.Gson;

/** Fetches Fundsmith's public NAV without changing stored portfolio quotes. */
@SuppressWarnings("nls")
final class FundsmithNav implements AutoCloseable
{
    static final String ISIN = "GB00B4M93C53";
    static final String SOURCE = "https://www.fundsmith.co.uk/factsheet/";

    record Price(String date, String isin, String currency, String price, String source, String fetchedAt)
    {
    }

    record FetchStatus(int attempts, int forbidden, String lastAttemptAt, String lastSuccessAt, String error)
    {
    }

    private static final Pattern HEADING = Pattern.compile("<h2\\b[^>]*>(.*?)</h2>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TABLE = Pattern.compile("<table\\b[^>]*>(.*?)</table>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh]\\b[^>]*>(.*?)</t[dh]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("Price Data (\\d{1,2})(?:st|nd|rd|th) ([A-Za-z]+) (\\d{4})");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);

    private final Path file;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "portfolio-fundsmith-nav");
        thread.setDaemon(true);
        return thread;
    });
    private Price accepted;
    private volatile FetchStatus fetchStatus = new FetchStatus(0, 0, null, null, null);

    FundsmithNav(Path file, Clock clock)
    {
        this.file = file;
        this.clock = clock;
    }

    synchronized Price price()
    {
        if (file == null)
            return null;
        try
        {
            if (Files.size(file) > 4096)
                return accepted;
            var candidate = new Gson().fromJson(Files.readString(file), Price.class);
            if (valid(candidate) && (accepted == null || candidate.date().compareTo(accepted.date()) >= 0))
                accepted = candidate;
        }
        catch (IOException | RuntimeException ignored)
        {
            // A failed fetch or incomplete replacement never discards the last validated NAV.
        }
        return accepted;
    }

    FetchStatus fetchStatus()
    {
        return fetchStatus;
    }

    void start()
    {
        if (file != null)
            executor.scheduleWithFixedDelay(this::refreshIfDue, 0, 30, TimeUnit.MINUTES);
    }

    private void refreshIfDue()
    {
        var local = clock.instant().atZone(ZoneId.of("Europe/Zurich"));
        if (local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY
                        || local.getHour() < 12 || local.getHour() >= 20)
            return;
        var previous = fetchStatus;
        var attempt = clock.instant().toString();
        try
        {
            var request = HttpRequest.newBuilder(URI.create(SOURCE)).timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "PortfolioWeb-FundsmithNAV/1.0").header("Accept", "text/html").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200)
            {
                fetchStatus = new FetchStatus(previous.attempts() + 1,
                                previous.forbidden() + (response.statusCode() == 403 ? 1 : 0), attempt,
                                previous.lastSuccessAt(), "Fundsmith returned HTTP " + response.statusCode());
                return;
            }
            if (response.body().length > 1_000_000)
                throw new IOException("Oversized Fundsmith response");
            var candidate = parse(new String(response.body(), StandardCharsets.UTF_8), clock.instant());
            if (!valid(candidate))
                throw new IOException("Invalid Fundsmith NAV");
            var current = price();
            if (current != null && candidate.date().compareTo(current.date()) < 0)
                throw new IOException("Fundsmith returned an older NAV");
            PrivateFiles.write(file, new Gson().toJson(candidate).getBytes(StandardCharsets.UTF_8));
            accepted = candidate;
            fetchStatus = new FetchStatus(previous.attempts() + 1, previous.forbidden(), attempt, attempt, null);
        }
        catch (IOException | InterruptedException | RuntimeException failure)
        {
            if (failure instanceof InterruptedException)
                Thread.currentThread().interrupt();
            fetchStatus = new FetchStatus(previous.attempts() + 1, previous.forbidden(), attempt,
                            previous.lastSuccessAt(), "Fundsmith NAV fetch failed: " + failure.getClass().getSimpleName());
        }
    }

    static Price parse(String html, Instant fetchedAt) throws IOException
    {
        var headings = HEADING.matcher(html);
        while (headings.find())
        {
            var heading = text(headings.group(1));
            if (!heading.startsWith("Price Data "))
                continue;
            var dateMatch = DATE.matcher(heading);
            if (!dateMatch.matches())
                throw new IOException("Invalid Fundsmith price date");
            LocalDate date;
            try
            {
                date = LocalDate.parse(dateMatch.group(1) + " " + dateMatch.group(2) + " " + dateMatch.group(3), DATE_FORMAT);
            }
            catch (RuntimeException invalid)
            {
                throw new IOException("Invalid Fundsmith price date", invalid);
            }
            var table = TABLE.matcher(html);
            if (!table.find(headings.end()))
                throw new IOException("Fundsmith price table missing");
            var rows = ROW.matcher(table.group(1));
            if (!rows.find() || !cells(rows.group(1)).equals(List.of("Share Class", "OCF*", "Minimum", "Price p")))
                throw new IOException("Unexpected Fundsmith price table");
            String found = null;
            while (rows.find())
            {
                var cells = cells(rows.group(1));
                if (!cells.isEmpty() && cells.getFirst().equals("T Class Inc"))
                {
                    if (found != null || cells.size() != 4)
                        throw new IOException("Ambiguous Fundsmith T Class Inc price");
                    found = cells.get(3);
                }
            }
            if (found == null)
                throw new IOException("Fundsmith T Class Inc price missing");
            try
            {
                var value = new BigDecimal(found);
                if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100000)) >= 0 || value.scale() > 4)
                    throw new NumberFormatException();
            }
            catch (NumberFormatException invalid)
            {
                throw new IOException("Invalid Fundsmith T Class Inc price", invalid);
            }
            return new Price(date.toString(), ISIN, "GBX", found, SOURCE, fetchedAt.toString());
        }
        throw new IOException("Fundsmith price section missing");
    }

    private static List<String> cells(String row)
    {
        var result = new ArrayList<String>();
        var matcher = CELL.matcher(row);
        while (matcher.find())
            result.add(text(matcher.group(1)));
        return result;
    }

    private static String text(String html)
    {
        return TAG.matcher(html).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private boolean valid(Price price)
    {
        if (price == null || !ISIN.equals(price.isin()) || !SOURCE.equals(price.source()) || !"GBX".equals(price.currency()))
            return false;
        try
        {
            var date = LocalDate.parse(price.date());
            var fetched = Instant.parse(price.fetchedAt());
            var value = new BigDecimal(price.price());
            return !date.isAfter(LocalDate.now(clock)) && !fetched.isAfter(clock.instant().plusSeconds(60))
                            && !fetched.isBefore(date.atStartOfDay(clock.getZone()).toInstant())
                            && value.signum() > 0 && value.compareTo(BigDecimal.valueOf(100000)) < 0 && value.scale() <= 4;
        }
        catch (RuntimeException invalid)
        {
            return false;
        }
    }

    @Override
    public void close()
    {
        executor.shutdownNow();
    }
}
