package name.abuchen.portfolio.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityProperty;
import name.abuchen.portfolio.online.Factory;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.online.QuoteFeed.HistoricalUpdatePolicy;
import name.abuchen.portfolio.online.QuoteFeedData;
import name.abuchen.portfolio.online.QuoteFeedException;
import name.abuchen.portfolio.online.RateLimitExceededException;

/**
 * Updates the historical and latest prices of a client's active securities
 * without writing a client file. The caller owns the supplied {@link Client};
 * consequently all successful updates remain available for valuation until the
 * client is discarded or saved by a future command.
 * <p>
 * The updater deliberately performs requests sequentially. Apart from making
 * progress deterministic for a terminal, this avoids bypassing a provider's
 * rate limits. A provider-reported rate limit is retried with its requested
 * delay and configured retry count, matching the UI price-update job. Quote
 * feeds are resolved through the core {@link Factory} by default, while the
 * alternate constructor makes the service testable and usable with a
 * caller-provided feed registry.
 */
public final class LatestQuoteUpdater
{
    /** Receives progress after each security has reached a terminal result. */
    @FunctionalInterface
    public interface ProgressListener
    {
        void updated(int completed, int total, ResultEntry entry);
    }

    public enum Status
    {
        UPDATED, UNCHANGED, SKIPPED, FAILED
    }

    /** One security's outcome. {@code message} explains skipped and failed entries. */
    public record ResultEntry(Security security, String feedId, Status status, String message)
    {
        public ResultEntry
        {
            Objects.requireNonNull(security, "security"); //$NON-NLS-1$
            Objects.requireNonNull(status, "status"); //$NON-NLS-1$
        }
    }

    /** Immutable aggregate suitable for rendering a concise QUPD summary. */
    public static final class Result
    {
        private final List<ResultEntry> entries;

        private Result(List<ResultEntry> entries)
        {
            this.entries = List.copyOf(entries);
        }

        public List<ResultEntry> getEntries()
        {
            return entries;
        }

        public long getUpdatedCount()
        {
            return count(Status.UPDATED);
        }

        public long getUnchangedCount()
        {
            return count(Status.UNCHANGED);
        }

        public long getSkippedCount()
        {
            return count(Status.SKIPPED);
        }

        public long getFailedCount()
        {
            return count(Status.FAILED);
        }

        private long count(Status status)
        {
            return entries.stream().filter(entry -> entry.status() == status).count();
        }
    }

    private static final ProgressListener NO_PROGRESS = (completed, total, entry) -> {
        // intentionally empty
    };

    private final Function<String, QuoteFeed> feedResolver;

    public LatestQuoteUpdater()
    {
        this(Factory::getQuoteFeedProvider);
    }

    public LatestQuoteUpdater(Function<String, QuoteFeed> feedResolver)
    {
        this.feedResolver = Objects.requireNonNull(feedResolver, "feedResolver"); //$NON-NLS-1$
    }

    /** Updates all non-retired securities configured with an automatic quote feed. */
    public Result update(Client client)
    {
        return update(client, NO_PROGRESS);
    }

    public Result update(Client client, ProgressListener progress)
    {
        Objects.requireNonNull(client, "client"); //$NON-NLS-1$
        return update(client.getActiveSecurities(), progress);
    }

    /**
     * Updates the supplied securities. This overload permits a shell command
     * to support a future explicit selection without duplicating feed logic.
     */
    public Result update(List<Security> securities, ProgressListener progress)
    {
        Objects.requireNonNull(securities, "securities"); //$NON-NLS-1$
        Objects.requireNonNull(progress, "progress"); //$NON-NLS-1$

        List<Security> target = Collections.unmodifiableList(new ArrayList<>(securities));
        List<ResultEntry> entries = new ArrayList<>(target.size());
        for (int index = 0; index < target.size(); index++)
        {
            ResultEntry entry = update(target.get(index));
            entries.add(entry);
            progress.updated(index + 1, target.size(), entry);
        }
        return new Result(entries);
    }

    private ResultEntry update(Security security)
    {
        Objects.requireNonNull(security, "security"); //$NON-NLS-1$

        String historicalFeedId = security.getFeed();
        String latestFeedId = security.getLatestFeed();
        if (latestFeedId == null)
            latestFeedId = historicalFeedId;
        String resultFeedId = latestFeedId != null ? latestFeedId : historicalFeedId;

        try
        {
            QuoteFeed historicalFeed = resolve(historicalFeedId);
            QuoteFeed latestFeed = Objects.equals(historicalFeedId, latestFeedId) ? historicalFeed
                            : resolve(latestFeedId);

            boolean historicalAvailable = isAutomatic(historicalFeed);
            boolean latestAvailable = isAutomatic(latestFeed);
            if (!historicalAvailable && !latestAvailable)
            {
                String reason = historicalFeed == null && latestFeed == null
                                ? "No quote feed is configured." //$NON-NLS-1$
                                : "The quote feed is manual."; //$NON-NLS-1$
                return new ResultEntry(security, resultFeedId, Status.SKIPPED, reason);
            }

            boolean updated = false;
            List<String> errors = new ArrayList<>();

            // Keep this separate from the latest update. The desktop client
            // updates historical prices with the security's normal ticker and
            // uses the optional alternate ticker only for the latest quote.
            if (historicalAvailable)
            {
                try
                {
                    QuoteFeedData data = historicalQuotes(historicalFeed, security);
                    updated |= applyHistoricalQuotes(historicalFeed, security, data);
                    data.getErrors().stream().map(this::message).forEach(errors::add);
                }
                catch (QuoteFeedException | RuntimeException e)
                {
                    errors.add(message(e));
                }
            }

            if (latestAvailable)
            {
                try
                {
                    Optional<LatestSecurityPrice> latest = latestQuote(latestFeed, fetchSecurity(security));
                    if (latest.isPresent())
                        updated |= security.setLatest(latest.get());
                }
                catch (QuoteFeedException | RuntimeException e)
                {
                    errors.add(message(e));
                }
            }

            if (!errors.isEmpty() && !updated)
                return new ResultEntry(security, resultFeedId, Status.FAILED, String.join("; ", errors)); //$NON-NLS-1$

            String message = errors.isEmpty() ? null : String.join("; ", errors); //$NON-NLS-1$
            return new ResultEntry(security, resultFeedId, updated ? Status.UPDATED : Status.UNCHANGED, message);
        }
        catch (RuntimeException e)
        {
            return new ResultEntry(security, resultFeedId, Status.FAILED, message(e));
        }
    }

    private QuoteFeed resolve(String feedId)
    {
        return feedId == null ? null : feedResolver.apply(feedId);
    }

    private boolean isAutomatic(QuoteFeed feed)
    {
        return feed != null && !QuoteFeed.MANUAL.equals(feed.getId());
    }

    private QuoteFeedData historicalQuotes(QuoteFeed feed, Security security) throws QuoteFeedException
    {
        return withRateLimitRetry(feed, () -> feed.getHistoricalQuotes(security, false));
    }

    private Optional<LatestSecurityPrice> latestQuote(QuoteFeed feed, Security security) throws QuoteFeedException
    {
        return withRateLimitRetry(feed, () -> feed.getLatestQuote(security));
    }

    private <T> T withRateLimitRetry(QuoteFeed feed, QuoteRequest<T> request) throws QuoteFeedException
    {
        int retriesRemaining = feed.getMaxRateLimitAttempts();
        while (true)
        {
            try
            {
                return request.get();
            }
            catch (RateLimitExceededException e)
            {
                if (retriesRemaining-- <= 0 || !e.getRetryAfter().isPositive())
                    throw e;

                try
                {
                    Thread.sleep(e.getRetryAfter().toMillis());
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Quote refresh was interrupted.", interrupted); //$NON-NLS-1$
                }
            }
        }
    }

    @FunctionalInterface
    private interface QuoteRequest<T>
    {
        T get() throws QuoteFeedException;
    }

    private boolean applyHistoricalQuotes(QuoteFeed feed, Security security, QuoteFeedData data)
    {
        HistoricalUpdatePolicy updatePolicy = feed.getHistoricalUpdatePolicy(security);

        if (updatePolicy == HistoricalUpdatePolicy.REPLACE)
            return replaceHistoricalQuotes(security, data, null);

        if (updatePolicy == HistoricalUpdatePolicy.REPLACE_IF_SOURCE_CHANGED)
            return applyReplaceIfSourceChanged(feed, security, data);

        return security.addAllPrices(data.getPrices());
    }

    private boolean applyReplaceIfSourceChanged(QuoteFeed feed, Security security, QuoteFeedData data)
    {
        var currentIdentity = feed.getHistoricalDataIdentity(security);
        if (currentIdentity.isEmpty())
            return security.addAllPrices(data.getPrices());

        String storedIdentity = security
                        .getPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.HISTORICAL_DATA_IDENTITY)
                        .orElse(null);

        if (currentIdentity.get().equals(storedIdentity))
            return security.addAllPrices(data.getPrices());

        return replaceHistoricalQuotes(security, data, currentIdentity.get());
    }

    private boolean replaceHistoricalQuotes(Security security, QuoteFeedData data, String identity)
    {
        if (!data.getErrors().isEmpty() || data.getPrices().isEmpty())
            return false;

        boolean hadExistingPrices = !security.getPrices().isEmpty();
        security.removeAllPrices();

        boolean updated = security.addAllPrices(data.getPrices()) || hadExistingPrices;
        if (security.setPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.HISTORICAL_DATA_IDENTITY, identity))
            updated = true;

        return updated;
    }

    private Security fetchSecurity(Security security)
    {
        var latestTicker = security.getPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.TICKER_SYMBOL_LATEST);
        if (latestTicker.isEmpty())
            return security;

        Security copy = security.deepCopy();
        copy.setTickerSymbol(latestTicker.get());
        return copy;
    }

    private String message(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
