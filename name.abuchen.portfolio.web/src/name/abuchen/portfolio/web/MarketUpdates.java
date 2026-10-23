package name.abuchen.portfolio.web;

import java.io.IOException;
import java.time.Clock;

@SuppressWarnings("nls")
final class MarketUpdates implements AutoCloseable
{
    record Status(String observedAt, QuoteJobs.Status quotes, FxRates.Status fx)
    {
    }

    private final QuoteJobs quotes;
    private final FxRates fx;
    private final Clock clock;

    MarketUpdates(QuoteJobs quotes, FxRates fx, Clock clock)
    {
        this.quotes = quotes;
        this.fx = fx;
        this.clock = clock;
    }

    static MarketUpdates disabled()
    {
        return new MarketUpdates(new QuoteJobs(null, Clock.systemUTC()), null, Clock.systemUTC());
    }

    FxRates fx()
    {
        return fx;
    }

    Status status()
    {
        return new Status(clock.instant().toString(), quotes.status(), fx == null ? null : fx.status());
    }

    int request() throws IOException
    {
        var result = quotes.request();
        if (result.httpStatus() == 202 && fx != null)
            fx.request(true);
        return result.httpStatus();
    }

    void start()
    {
        if (fx != null)
            fx.start();
    }

    @Override
    public void close()
    {
        if (fx != null)
            fx.close();
    }
}
