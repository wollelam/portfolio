package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRateProvider;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;

public class ExchangeRateCacheTest
{
    @Test
    public void loadsOnceThenRefreshesAndSavesProviders() throws Exception
    {
        var provider = new RecordingProvider();
        var cache = new ExchangeRateCache(List.of(provider));

        cache.load();
        cache.refresh();

        assertThat(provider.operations, is(List.of("load", "update", "save"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static final class RecordingProvider implements ExchangeRateProvider
    {
        private final List<String> operations = new ArrayList<>();

        @Override
        public String getName()
        {
            return "recording"; //$NON-NLS-1$
        }

        @Override
        public void load(IProgressMonitor monitor)
        {
            operations.add("load"); //$NON-NLS-1$
        }

        @Override
        public void update(IProgressMonitor monitor)
        {
            operations.add("update"); //$NON-NLS-1$
        }

        @Override
        public void save(IProgressMonitor monitor)
        {
            operations.add("save"); //$NON-NLS-1$
        }

        @Override
        public List<ExchangeRateTimeSeries> getAvailableTimeSeries(Client client)
        {
            return List.of();
        }
    }
}
