package name.abuchen.portfolio.web;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.FrameworkUtil;

import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.impl.ECBExchangeRateProvider;

/** Independent headless application: it never starts an SWT workbench. */
@SuppressWarnings("nls")
public final class WebApplication implements IApplication
{
    private final CountDownLatch stopped = new CountDownLatch(1);
    private WebServer server;

    @Override
    public Object start(IApplicationContext context) throws Exception
    {
        var args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        Path file = null;
        Path secondFile = null;
        boolean demo = false;
        int port = 8088;
        String bind = "127.0.0.1";
        int pollSeconds = 30;
        Path snapshots = Path.of(System.getProperty("user.home"), ".local", "state", "portfolio-web", "snapshots");
        Path priceDirectory = null;
        Path liveQuotesFile = null;
        for (int ii = 0; ii < args.length; ii++)
        {
            switch (args[ii])
            {
                case "--demo" -> demo = true;
                case "--file" -> file = Path.of(args[++ii]);
                case "--second-file" -> secondFile = Path.of(args[++ii]);
                case "--port" -> port = Integer.parseInt(args[++ii]);
                case "--bind" -> bind = args[++ii];
                case "--poll-seconds" -> pollSeconds = Integer.parseInt(args[++ii]);
                case "--snapshot-dir" -> snapshots = Path.of(args[++ii]);
                case "--price-updates-dir" -> priceDirectory = Path.of(args[++ii]);
                case "--live-quotes-file" -> liveQuotesFile = Path.of(args[++ii]);
                default -> throw new IllegalArgumentException("Expected --demo or --file PATH; optional --port, --bind, --poll-seconds, --snapshot-dir.");
            }
        }
        if (demo == (file != null) || (demo && secondFile != null) || port < 1 || port > 65535 || pollSeconds < 1 || pollSeconds > 3600
                        || !(bind.equals("127.0.0.1") || bind.equals("0.0.0.0")))
            throw new IllegalArgumentException("Choose --demo or --file PATH; bind must be 127.0.0.1 or 0.0.0.0, poll interval 1–3600 seconds.");

        var warnings = new ArrayList<String>();
        for (var provider : ExchangeRateProviderFactory.getProviders())
        {
            try
            {
                provider.load(new NullProgressMonitor());
            }
            catch (Exception e)
            {
                warnings.add("Could not load cached exchange rates from " + provider.getName());
            }
        }
        var clock = Clock.systemDefaultZone();
        PortfolioSource source = demo ? () -> DemoPortfolio.create(LocalDate.now(clock))
                        : PortfolioSource.file(file, () -> {
                            var value = System.getenv("PP_WEB_PASSWORD");
                            return value == null ? null : value.toCharArray();
                        }, snapshots);
        var liveQuotes = new LiveQuotes(liveQuotesFile, clock);
        MarketUpdates updates = new MarketUpdates(new QuoteJobs(null, clock), null, clock, liveQuotes);
        if (!demo && priceDirectory != null)
        {
            PrivateFiles.directory(priceDirectory);
            var bundle = FrameworkUtil.getBundle(ECBExchangeRateProvider.class);
            var seed = Platform.getStateLocation(bundle).toFile().toPath().resolve("ecb_exchange_rates.pb");
            var fx = new FxRates(priceDirectory.resolve("fx"), seed, clock,
                            provider -> provider.update(new NullProgressMonitor()));
            updates = new MarketUpdates(new QuoteJobs(priceDirectory, clock, file.toAbsolutePath().normalize().toString()), fx, clock, liveQuotes);
        }
        var secondarySource = secondFile == null ? null : PortfolioSource.file(secondFile, () -> {
            var value = System.getenv("PP_WEB_SECOND_PASSWORD");
            return value == null ? null : value.toCharArray();
        }, snapshots);
        try (var market = updates; var portfolios = new PortfolioRefresh(source, clock, warnings, demo ? 0 : pollSeconds,
                        Duration.ofSeconds(5), market.fx());
                        var secondaryPortfolios = secondarySource == null ? null : new PortfolioRefresh(secondarySource, clock, warnings,
                                        pollSeconds, Duration.ofSeconds(5), market.fx()))
        {
            server = new WebServer(new InetSocketAddress(bind, port), portfolios, secondaryPortfolios, market);
            var shutdown = new Thread(this::stop, "portfolio-web-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try
            {
                server.start();
                portfolios.start();
                if (secondaryPortfolios != null)
                    secondaryPortfolios.start();
                market.start();
                System.out.println("Portfolio Web: http://localhost:" + server.port()); // NOSONAR
                System.out.println(demo ? "Synthetic demo; automatic file refresh is off."
                                : "Read-only portfolio; automatic file refresh is enabled."); // NOSONAR
                stopped.await();
            }
            finally
            {
                server.close();
                try
                {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                }
                catch (IllegalStateException e)
                {
                    // JVM shutdown is already running the hook.
                }
            }
        }
        return EXIT_OK;
    }

    @Override
    public void stop()
    {
        stopped.countDown();
    }
}
