package name.abuchen.portfolio.ui.jobs.priceupdate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

/* package */ class PriceUpdateRequest
{
    private final long timestamp = System.currentTimeMillis();
    
    private final Client client;
    private final Map<Security, SecurityUpdateStatus> statuses;

    private final boolean includeHistorical;
    private final boolean includeLatest;

    private final List<Security> securities;

    private final AtomicBoolean modified = new AtomicBoolean(false);
    private final AtomicBoolean unannouncedModification = new AtomicBoolean(false);

    public PriceUpdateRequest(Client client, List<Security> securities, boolean includeLatest,
                    boolean includeHistorical)
    {
        this.client = client;
        this.securities = securities;

        this.includeHistorical = includeHistorical;
        this.includeLatest = includeLatest;

        Map<Security, SecurityUpdateStatus> map = new HashMap<>();
        for (Security security : securities)
        {
            map.put(security, new SecurityUpdateStatus(security,
                            new FeedUpdateStatus(includeHistorical ? UpdateStatus.WAITING : UpdateStatus.SKIPPED),
                            new FeedUpdateStatus(includeLatest ? UpdateStatus.WAITING : UpdateStatus.SKIPPED)));
        }
        this.statuses = Collections.unmodifiableMap(map);
    }

    void markModified()
    {
        modified.set(true);
        unannouncedModification.set(true);
    }

    boolean getAndResetUnannouncedModification()
    {
        return unannouncedModification.getAndSet(false);
    }

    boolean isModified()
    {
        return modified.get();
    }

    public Client getClient()
    {
        return client;
    }

    public List<Security> getSecurities()
    {
        return securities;
    }

    public boolean isIncludeHistorical()
    {
        return includeHistorical;
    }

    public boolean isIncludeLatest()
    {
        return includeLatest;
    }

    public SecurityUpdateStatus getStatus(Security security)
    {
        return statuses.get(security);
    }

    public PriceUpdateSnapshot getStatusSnapshot()
    {
        return new PriceUpdateSnapshot(timestamp, statuses);
    }
}
