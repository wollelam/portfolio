package name.abuchen.portfolio.ui.jobs.priceupdate;

import java.time.Duration;
import java.util.EnumSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.editor.ClientInput;

public class PeriodicUpdatePricesJob extends Job
{
    private final ClientInput clientInput;
    private final UpdatePricesJob.Target target;
    private final Duration invertval;
    private volatile boolean stopped;

    public PeriodicUpdatePricesJob(ClientInput clientInput, UpdatePricesJob.Target target, Duration interval)
    {
        super(Messages.JobLabelUpdateQuotes);
        this.clientInput = clientInput;
        this.target = target;
        this.invertval = interval;
    }

    public Duration getInterval()
    {
        return invertval;
    }

    public synchronized void stop()
    {
        stopped = true;
        cancel();
    }

    private synchronized void scheduleNextUpdate()
    {
        if (!stopped)
            schedule(invertval.toMillis());
    }

    @Override
    protected IStatus run(IProgressMonitor monitor)
    {
        if (stopped)
            return Status.CANCEL_STATUS;

        var client = this.clientInput.getClient();

        // check on every run because the user can change the preferences
        if (client != null && this.clientInput.getEclipsePreferences()
                        .getBoolean(UIConstants.Preferences.UPDATE_QUOTES_PERIODICALLY, true))
        {
            var config = PriceUpdateConfig.fromCode(this.clientInput.getPreferenceStore()
                            .getString(UIConstants.Preferences.UPDATE_QUOTES_STRATEGY));

            var converter = new CurrencyConverterImpl(this.clientInput.getExchangeRateProviderFacory(),
                            client.getBaseCurrency());
            var job = new UpdatePricesJob(client, config.getPredicate(converter, client), EnumSet.of(target),
                            PriceUpdateMode.fromCode(PortfolioPlugin.getDefault().getPreferenceStore()
                                            .getString(UIConstants.Preferences.UPDATE_QUOTES_MODE)));
            job.suppressAuthenticationDialog(true);

            // add job listener to reschedule job only *after* the
            // completion of the price updates
            job.addJobChangeListener(new JobChangeAdapter()
            {
                @Override
                public void done(IJobChangeEvent event)
                {
                    scheduleNextUpdate();
                }
            });
            job.schedule();
        }
        else
        {
            scheduleNextUpdate();
        }

        return Status.OK_STATUS;
    }
}
