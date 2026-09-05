package name.abuchen.portfolio.cli;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/**
 * Equinox entry point for the interactive command-line prototype.
 */
public class CliApplication implements IApplication
{
    private PortfolioShell shell;

    @Override
    public Object start(IApplicationContext context) throws Exception
    {
        shell = new PortfolioShell();
        String[] arguments = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (arguments.length > 1)
            throw new IllegalArgumentException("Usage: portfolio-cli [file.portfolio]"); //$NON-NLS-1$
        return shell.run(arguments.length == 1 ? arguments[0] : null);
    }

    @Override
    public void stop()
    {
        if (shell != null)
            shell.stop();
    }
}
