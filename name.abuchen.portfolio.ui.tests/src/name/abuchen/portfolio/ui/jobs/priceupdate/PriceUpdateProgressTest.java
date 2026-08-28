package name.abuchen.portfolio.ui.jobs.priceupdate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;

@SuppressWarnings("nls")
public class PriceUpdateProgressTest
{
    @Test
    public void finishedCurrentJobNotifiesAndIsRemoved()
    {
        var client = new Client();
        var job = new UpdatePricesJob(client, Collections.emptySet());
        var progress = PriceUpdateProgress.getInstance();
        var notifications = new AtomicInteger();
        PriceUpdateProgress.Listener listener = snapshot -> notifications.incrementAndGet();

        progress.register(client, listener);
        try
        {
            progress.setLatestJob(client, job);
            var snapshot = new PriceUpdateSnapshot(System.currentTimeMillis(), Collections.emptyMap());

            progress.notifyFinished(job, snapshot);

            assertThat(notifications.get(), is(1));
            assertThat(progress.isCurrent(job), is(false));

            progress.notifyProgress(job, snapshot);
            assertThat(notifications.get(), is(1));
        }
        finally
        {
            progress.unregister(client, listener);
        }
    }

    @Test
    public void staleFinishedJobDoesNotRemoveNewerJobOrNotifyListeners()
    {
        var client = new Client();
        var oldJob = new UpdatePricesJob(client, Collections.emptySet());
        var newJob = new UpdatePricesJob(client, Collections.emptySet());
        var progress = PriceUpdateProgress.getInstance();
        var notifications = new AtomicInteger();
        PriceUpdateProgress.Listener listener = snapshot -> notifications.incrementAndGet();

        progress.register(client, listener);
        try
        {
            progress.setLatestJob(client, oldJob);
            progress.setLatestJob(client, newJob);
            var snapshot = new PriceUpdateSnapshot(System.currentTimeMillis(), Collections.emptyMap());

            progress.notifyFinished(oldJob, snapshot);

            assertThat(notifications.get(), is(0));
            assertThat(progress.isCurrent(newJob), is(true));

            progress.notifyFinished(newJob, snapshot);

            assertThat(notifications.get(), is(1));
            assertThat(progress.isCurrent(newJob), is(false));
        }
        finally
        {
            progress.unregister(client, listener);
        }
    }

    @Test
    public void finishedJobIsRemovedWhenListenerThrows()
    {
        var client = new Client();
        var job = new UpdatePricesJob(client, Collections.emptySet());
        var progress = PriceUpdateProgress.getInstance();
        PriceUpdateProgress.Listener listener = snapshot -> {
            throw new IllegalStateException("listener failed");
        };

        progress.register(client, listener);
        try
        {
            progress.setLatestJob(client, job);
            var snapshot = new PriceUpdateSnapshot(System.currentTimeMillis(), Collections.emptyMap());

            try
            {
                progress.notifyFinished(job, snapshot);
            }
            catch (IllegalStateException expected)
            {
                // expected
            }

            assertThat(progress.isCurrent(job), is(false));
        }
        finally
        {
            progress.unregister(client, listener);
        }
    }
}
