package name.abuchen.portfolio.web;

import java.io.IOException;
import java.time.Clock;

@SuppressWarnings("nls")
final class MarketUpdates implements AutoCloseable
{
    record Status(String observedAt, QuoteJobs.Status quotes, FxRates.Status fx, LiveQuotes.Status liveQuotes,
                    FundsmithNav.Price fundsmith, FundsmithNav.FetchStatus fundsmithFetch)
    {
    }

    private final QuoteJobs quotes;
    private final FxRates fx;
    private final Clock clock;
    private final LiveQuotes liveQuotes;
    private final FundsmithNav fundsmith;

    MarketUpdates(QuoteJobs quotes, FxRates fx, Clock clock)
    {
        this(quotes, fx, clock, new LiveQuotes(null, clock));
    }

    MarketUpdates(QuoteJobs quotes, FxRates fx, Clock clock, LiveQuotes liveQuotes)
    {
        this(quotes, fx, clock, liveQuotes, null);
    }

    MarketUpdates(QuoteJobs quotes, FxRates fx, Clock clock, LiveQuotes liveQuotes, FundsmithNav fundsmith)
    {
        this.quotes = quotes;
        this.fx = fx;
        this.clock = clock;
        this.liveQuotes = liveQuotes;
        this.fundsmith = fundsmith;
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
        return new Status(clock.instant().toString(), quotes.status(), fx == null ? null : fx.status(), liveQuotes.status(),
                        fundsmith == null ? null : fundsmith.price(), fundsmith == null ? null : fundsmith.fetchStatus());
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
        if (fundsmith != null)
            fundsmith.start();
    }

    @Override
    public void close()
    {
        if (fx != null)
            fx.close();
        if (fundsmith != null)
            fundsmith.close();
    }
}
