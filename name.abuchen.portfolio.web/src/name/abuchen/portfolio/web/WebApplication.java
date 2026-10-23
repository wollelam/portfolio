package name.abuchen.portfolio.web;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import name.abuchen.portfolio.money.ExchangeRateProviderFactory;

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
        boolean demo = false;
        int port = 8088;
        String bind = "127.0.0.1";
        for (int ii = 0; ii < args.length; ii++)
        {
            switch (args[ii])
            {
                case "--demo" -> demo = true;
                case "--file" -> file = Path.of(args[++ii]);
                case "--port" -> port = Integer.parseInt(args[++ii]);
                case "--bind" -> bind = args[++ii];
                default -> throw new IllegalArgumentException("Expected --demo or --file PATH, with optional --port and --bind.");
            }
        }
        if (demo == (file != null) || port < 1 || port > 65535
                        || !(bind.equals("127.0.0.1") || bind.equals("0.0.0.0")))
            throw new IllegalArgumentException("Choose exactly one of --demo or --file PATH; bind must be 127.0.0.1 or 0.0.0.0.");

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
        var passwordValue = System.getenv("PP_WEB_PASSWORD");
        char[] password = passwordValue == null ? null : passwordValue.toCharArray();
        PortfolioSource.LoadedPortfolio loaded;
        var clock = Clock.systemDefaultZone();
        try
        {
            loaded = demo ? DemoPortfolio.create(LocalDate.now(clock)) : PortfolioSource.file(file, password).load();
        }
        finally
        {
            if (password != null)
                Arrays.fill(password, '\0');
        }
        server = new WebServer(new InetSocketAddress(bind, port), new DashboardService(loaded, clock, warnings));
        var shutdown = new Thread(this::stop, "portfolio-web-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try
        {
            server.start();
            System.out.println("Portfolio Web: http://localhost:" + server.port()); // NOSONAR
            System.out.println("Loaded " + loaded.label() + "; read-only snapshot. Restart to load a newer file."); // NOSONAR
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
        return EXIT_OK;
    }

    @Override
    public void stop()
    {
        stopped.countDown();
    }
}
