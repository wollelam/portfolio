package name.abuchen.portfolio.ui.jobs.priceupdate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.online.QuoteFeedData;
import name.abuchen.portfolio.online.QuoteFeedException;

@SuppressWarnings("nls")
public class UpdatePricesJobCancellationTest
{
    @Test
    public void cancelingTaskGroupSkipsApplyAfterFetch() throws Exception
    {
        var client = new Client();
        var security = new Security("Test", "EUR");
        security.setTickerSymbol("TEST");
        security.setFeed("CANCEL-TEST");
        client.addSecurity(security);

        var feed = new BlockingQuoteFeed();
        var request = new PriceUpdateRequest(client, List.of(security), true, false);
        var task = new Task.LatestTask("CANCEL-TEST", feed, request.getStatus(security).getLatestStatus(), security);
        var job = new RunTaskGroupJob("CANCEL-TEST", List.of(task), request);

        try
        {
            job.schedule();
            assertTrue(feed.started.await(5, TimeUnit.SECONDS));

            job.cancel();
            feed.release.countDown();
            job.join(5_000, new NullProgressMonitor());
            assertThat(job.getState(), is(Job.NONE));
            assertThat(security.getLatest(), nullValue());
            assertTrue(feed.finished.await(5, TimeUnit.SECONDS));
        }
        finally
        {
            feed.release.countDown();
            job.cancel();
            feed.finished.await(5, TimeUnit.SECONDS);
            job.join(5_000, new NullProgressMonitor());
        }
    }

    private static final class BlockingQuoteFeed implements QuoteFeed
    {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public String getId()
        {
            return "CANCEL-TEST";
        }

        @Override
        public String getName()
        {
            return "Cancellation test feed";
        }

        @Override
        public Optional<LatestSecurityPrice> getLatestQuote(Security security) throws QuoteFeedException
        {
            started.countDown();
            try
            {
                release.await();
                return Optional.of(new LatestSecurityPrice(LocalDate.now(), 10000));
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        }

        @Override
        public QuoteFeedData getHistoricalQuotes(Security security, boolean collectRawResponse)
        {
            return new QuoteFeedData();
        }
    }
}
