package name.abuchen.portfolio.ui.jobs.priceupdate;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

import name.abuchen.portfolio.ui.editor.ClientInput;

public class PeriodicUpdatePricesJobTest
{
    @Test
    public void stoppedJobDoesNotUpdateOrReschedule()
    {
        var input = mock(ClientInput.class);
        var job = new PeriodicUpdatePricesJob(input, UpdatePricesJob.Target.LATEST, Duration.ofMinutes(30));
        job.stop();
        job.run(new NullProgressMonitor());
        verifyNoInteractions(input);
        assertEquals(Job.NONE, job.getState());
    }
}
