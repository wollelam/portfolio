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
import name.abuchen.portfolio.online.QuoteFeedException;
import name.abuchen.portfolio.online.RateLimitExceededException;

/**
 * Updates the latest prices of a client's active securities without writing a
 * client file. The caller owns the supplied {@link Client}; consequently all
 * successful updates remain available for valuation until the client is
 * discarded or saved by a future command.
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

    /** Updates all non-retired securities configured with an automatic latest-price feed. */
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

        String feedId = security.getLatestFeed();
        if (feedId == null)
            feedId = security.getFeed();

        try
        {
            QuoteFeed feed = feedId == null ? null : feedResolver.apply(feedId);
            if (feed == null)
                return new ResultEntry(security, feedId, Status.SKIPPED, "No quote feed is configured."); //$NON-NLS-1$
            if (QuoteFeed.MANUAL.equals(feed.getId()))
                return new ResultEntry(security, feedId, Status.SKIPPED, "The quote feed is manual."); //$NON-NLS-1$

            Optional<LatestSecurityPrice> latest = latestQuote(feed, security);
            if (latest.isEmpty())
                return new ResultEntry(security, feedId, Status.UNCHANGED, "The feed returned no latest quote."); //$NON-NLS-1$

            Status status = security.setLatest(latest.get()) ? Status.UPDATED : Status.UNCHANGED;
            return new ResultEntry(security, feedId, status, null);
        }
        catch (QuoteFeedException | RuntimeException e)
        {
            return new ResultEntry(security, feedId, Status.FAILED, message(e));
        }
    }

    private Optional<LatestSecurityPrice> latestQuote(QuoteFeed feed, Security security) throws QuoteFeedException
    {
        int retriesRemaining = feed.getMaxRateLimitAttempts();
        while (true)
        {
            try
            {
                return feed.getLatestQuote(fetchSecurity(security));
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
