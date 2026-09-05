package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityProperty;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.online.QuoteFeedData;
import name.abuchen.portfolio.online.QuoteFeedException;
import name.abuchen.portfolio.online.RateLimitExceededException;

public class LatestQuoteUpdaterTest
{
    @Test
    public void updatesLatestPriceInMemoryAndHonorsLatestTickerOverride()
    {
        Security security = security("ACME", "AUTO"); //$NON-NLS-1$ //$NON-NLS-2$
        security.setPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.TICKER_SYMBOL_LATEST, "LATEST"); //$NON-NLS-1$
        Client client = new Client();
        client.addSecurity(security);

        LatestQuoteFeed feed = new LatestQuoteFeed();
        LatestQuoteUpdater updater = new LatestQuoteUpdater(feedId -> feed);
        LatestQuoteUpdater.Result result = updater.update(client);

        assertThat(result.getUpdatedCount(), is(1L));
        assertThat(security.getLatest().getValue(), is(123_000L));
        assertThat(feed.getTicker(), is("LATEST")); //$NON-NLS-1$
    }

    @Test
    public void classifiesManualMissingAndFailingFeedsWithoutStoppingTheBatch()
    {
        Security manual = security("Manual", QuoteFeed.MANUAL); //$NON-NLS-1$
        Security missing = security("Missing", "MISSING"); //$NON-NLS-1$ //$NON-NLS-2$
        Security failing = security("Failing", "FAIL"); //$NON-NLS-1$ //$NON-NLS-2$

        LatestQuoteUpdater updater = new LatestQuoteUpdater(feedId -> {
            if (QuoteFeed.MANUAL.equals(feedId))
                return new ManualFeed();
            if ("FAIL".equals(feedId)) //$NON-NLS-1$
                return new FailingFeed();
            return null;
        });

        List<LatestQuoteUpdater.ResultEntry> progress = new java.util.ArrayList<>();
        LatestQuoteUpdater.Result result = updater.update(List.of(manual, missing, failing),
                        (completed, total, entry) -> progress.add(entry));

        assertThat(result.getSkippedCount(), is(2L));
        assertThat(result.getFailedCount(), is(1L));
        assertThat(progress.size(), is(3));
    }

    @Test
    public void retriesProviderRateLimitUsingTheSamePolicyAsTheUiJob()
    {
        Security security = security("Limited", "LIMITED"); //$NON-NLS-1$ //$NON-NLS-2$
        RateLimitedFeed feed = new RateLimitedFeed();

        LatestQuoteUpdater.Result result = new LatestQuoteUpdater(feedId -> feed).update(List.of(security),
                        (completed, total, entry) -> {
                            // no progress assertion needed for this retry behavior
                        });

        assertThat(result.getUpdatedCount(), is(1L));
        assertThat(feed.getCalls(), is(2));
    }

    private Security security(String name, String feed)
    {
        Security security = new Security(name, "EUR"); //$NON-NLS-1$
        security.setFeed(feed);
        security.setTickerSymbol("MAIN"); //$NON-NLS-1$
        return security;
    }

    private static class LatestQuoteFeed implements QuoteFeed
    {
        private String ticker;

        @Override
        public String getId()
        {
            return "AUTO"; //$NON-NLS-1$
        }

        @Override
        public String getName()
        {
            return getId();
        }

        @Override
        public Optional<LatestSecurityPrice> getLatestQuote(Security security) throws QuoteFeedException
        {
            ticker = security.getTickerSymbol();
            return Optional.of(new LatestSecurityPrice(LocalDate.of(2026, 1, 2), 123_000L));
        }

        public String getTicker()
        {
            return ticker;
        }

        @Override
        public QuoteFeedData getHistoricalQuotes(Security security, boolean collectRawResponse)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ManualFeed extends LatestQuoteFeed
    {
        @Override
        public String getId()
        {
            return QuoteFeed.MANUAL;
        }
    }

    private static final class FailingFeed extends LatestQuoteFeed
    {
        @Override
        public String getId()
        {
            return "FAIL"; //$NON-NLS-1$
        }

        @Override
        public Optional<LatestSecurityPrice> getLatestQuote(Security security) throws QuoteFeedException
        {
            throw new TestQuoteFeedException();
        }
    }

    private static final class RateLimitedFeed extends LatestQuoteFeed
    {
        private int calls;

        @Override
        public Optional<LatestSecurityPrice> getLatestQuote(Security security) throws QuoteFeedException
        {
            calls++;
            if (calls == 1)
                throw new RateLimitExceededException(Duration.ofMillis(1), "limited"); //$NON-NLS-1$
            return super.getLatestQuote(security);
        }

        @Override
        public int getMaxRateLimitAttempts()
        {
            return 1;
        }

        public int getCalls()
        {
            return calls;
        }
    }

    private static final class TestQuoteFeedException extends QuoteFeedException
    {
        private static final long serialVersionUID = 1L;

        private TestQuoteFeedException()
        {
            super("unavailable"); //$NON-NLS-1$
        }
    }
}
