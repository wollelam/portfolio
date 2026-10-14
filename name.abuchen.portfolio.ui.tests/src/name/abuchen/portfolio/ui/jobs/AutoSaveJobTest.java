package name.abuchen.portfolio.ui.jobs;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.ui.editor.ClientInput;

public class AutoSaveJobTest
{
    @Test
    public void stoppedJobDoesNotSaveOrReschedule()
    {
        var input = mock(ClientInput.class);
        when(input.getClient()).thenReturn(new Client());
        var job = new AutoSaveJob(input, 1000);
        job.stop();
        job.run(new NullProgressMonitor());
        verify(input, never()).autoSave();
        assertEquals(Job.NONE, job.getState());
    }
}
