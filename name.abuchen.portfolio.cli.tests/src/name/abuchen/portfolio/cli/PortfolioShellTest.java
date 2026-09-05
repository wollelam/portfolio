package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.snapshot.ReportingPeriod;

/**
 * Command-level tests for the interactive prototype. As a fragment, this test
 * bundle exercises the package-visible dispatcher used by the interactive
 * loop without exposing test-only API from the CLI bundle.
 */
public class PortfolioShellTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void openValueAndHoldingsUseCoreClientSnapshot() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("VAL 2015-01-16"); //$NON-NLS-1$
            harness.execute("HOLD 2015-01-16"); //$NON-NLS-1$

            String output = harness.output();
            assertThat(output, containsString("Opened ")); //$NON-NLS-1$
            assertThat(output, containsString("Value at 2015-01-16: EUR")); //$NON-NLS-1$
            assertThat(output, containsString("Apple")); //$NON-NLS-1$
            assertThat(output, containsString("BASF")); //$NON-NLS-1$
            assertThat(output, containsString("Account EUR")); //$NON-NLS-1$
            assertThat(output, containsString("Account USD")); //$NON-NLS-1$
        }
    }

    @Test
    public void encryptedOpenPromptsForPasswordAndLoadsFixture() throws Exception
    {
        Path file = copyFixture("fileversions/client52.binary+pwd.portfolio"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness("123456\n")) //$NON-NLS-1$
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Opened ")); //$NON-NLS-1$
        }
    }

    @Test
    public void checkReportsCleanCurrencyFixture() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("CHK"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("No consistency issues found.")); //$NON-NLS-1$
        }
    }

    @Test
    public void commandsRequireAnOpenClient() throws Exception
    {
        try (ShellHarness harness = new ShellHarness())
        {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                            () -> harness.execute("VAL 2015-01-16")); //$NON-NLS-1$
            assertThat(error.getMessage(), is("No file is open. Use OPEN <file> first.")); //$NON-NLS-1$
        }
    }

    @Test
    public void performanceShowsDashboardStyleBreakdown() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF --from 2014-01-01 --to 2015-01-16 --limit 2"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Performance breakdown")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Performance breakdown:")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Unrealized capital gains")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Realized capital gains")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Earnings")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Currency gains")); //$NON-NLS-1$
        }
    }

    @Test
    public void commandsWithoutAPeriodUseThePreviousTradingDay() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var interval = new ReportingPeriod.PreviousTradingDay().toInterval(LocalDate.now());
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF"); //$NON-NLS-1$

            assertThat(harness.output(), containsString(interval.getStart() + " to " + interval.getEnd())); //$NON-NLS-1$
        }
    }

    @Test
    public void topPerformersListsBestAndWorstUsingCoreTtwror() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("TPERF --from 2014-01-01 --to 2015-01-16 --limit 2"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Top performers")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Best performers:")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Worst performers:")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Best performers (currency performance):")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Worst performers (currency performance):")); //$NON-NLS-1$
        }
    }

    @Test
    public void negativePeriodsSelectCompletedCalendarPeriods() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF -1d --to 2015-01-16 --limit 1"); //$NON-NLS-1$
            harness.execute("PERF -2w --to 2015-01-16 --limit 1"); //$NON-NLS-1$
            harness.execute("PERF -1m --to 2015-01-16 --limit 1"); //$NON-NLS-1$
            harness.execute("PERF -1y --to 2015-01-16 --limit 1"); //$NON-NLS-1$
            harness.execute("PERF -2y --to 2015-01-16 --limit 1"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("2015-01-14 to 2015-01-15")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2014-12-28 to 2015-01-04")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2014-11-30 to 2014-12-31")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2013-12-31 to 2014-12-31")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2012-12-31 to 2013-12-31")); //$NON-NLS-1$
        }
    }

    @Test
    public void performanceSupportsMultiYearPeriods() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF 2Y --to 2015-01-16 --limit 1"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("2013-01-16 to 2015-01-16")); //$NON-NLS-1$
        }
    }

    @Test
    public void performanceSupportsMultiDayAndWeekPeriods() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF 2d --to 2015-01-16 --limit 1"); //$NON-NLS-1$
            harness.execute("PERF 7D --to 2015-01-16 --limit 1"); //$NON-NLS-1$
            harness.execute("PERF 1W --to 2015-01-16 --limit 1"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("2015-01-14 to 2015-01-16")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2015-01-09 to 2015-01-16")); //$NON-NLS-1$
        }
    }

    @Test
    public void performanceYtdStartsOnTheDayBeforeTheCalendarYear() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF ytd --to 2015-01-16 --limit 1"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("2014-12-31 to 2015-01-16")); //$NON-NLS-1$
        }
    }

    @Test
    public void performanceMtdStartsOnTheDayBeforeTheCalendarMonth() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF mtd --to 2015-01-16 --limit 1"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("2014-12-31 to 2015-01-16")); //$NON-NLS-1$
        }
    }

    @Test
    public void interactiveLoopReadsJLineCommandsUntilExit() throws Exception
    {
        try (ShellHarness harness = new ShellHarness("HELP\nEXIT\n")) //$NON-NLS-1$
        {
            assertThat(harness.run(), is(0));
            assertThat(harness.output(), containsString("Portfolio Performance CLI prototype")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("VAL [YYYY-MM-DD]")); //$NON-NLS-1$
        }
    }

    @Test
    public void interactiveLoopOpensItsInitialFileBeforeReadingCommands() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness("EXIT\n")) //$NON-NLS-1$
        {
            assertThat(harness.run(file.toString()), is(0));
            assertThat(harness.output(), containsString("Opened " + file)); //$NON-NLS-1$
        }
    }

    @Test
    public void storeUsesTheProductionWriterAndTheResultCanBeReloaded() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("STORE"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Stored " + file)); //$NON-NLS-1$
            assertThat(Files.isRegularFile(file.resolveSibling("currency_sample.backup.xml")), is(true)); //$NON-NLS-1$
        }

        assertThat(ClientFactory.load(file.toFile(), null, new NullProgressMonitor()).getBaseCurrency(), is("EUR")); //$NON-NLS-1$
    }

    @Test
    public void storePreservesAnEncryptedPortfolio() throws Exception
    {
        Path file = copyFixture("fileversions/client52.binary+pwd.portfolio"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness("123456\n")) //$NON-NLS-1$
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("STORE"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Stored " + file)); //$NON-NLS-1$
        }

        char[] password = "123456".toCharArray(); //$NON-NLS-1$
        try
        {
            assertThat(ClientFactory.isEncrypted(file.toFile()), is(true));
            assertThat(ClientFactory.load(file.toFile(), password, new NullProgressMonitor()).getSecurities().isEmpty(),
                            is(false));
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    public void overviewAndSecurityReportsUseTheRequestedPeriod() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("SUMMARY 1M --to 2015-01-16"); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2014-12-16 to 2015-01-16")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Total value       EUR 4,354.38")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Top contributors:")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Top detractors:")); //$NON-NLS-1$
            harness.execute("DATA 1M --to 2015-01-16"); //$NON-NLS-1$
            assertThat(harness.output(), containsString("DATA QUALITY  2014-12-16 to 2015-01-16")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Quotes older than 7 calendar days")); //$NON-NLS-1$
            harness.execute("SEC Apple YTD --to 2015-01-16"); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Security: Apple")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("TTWROR")); //$NON-NLS-1$
        }
    }

    @Test
    public void transactionFiltersSharePeriodParsing() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("TXN 2Y --to 2015-01-16 --security nonexistent --type BUY"); //$NON-NLS-1$
            assertThat(harness.output(), containsString("2013-01-16 to 2015-01-16")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("No transactions found.")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Count: 0")); //$NON-NLS-1$
        }
    }

    private Path copyFixture(String path) throws IOException
    {
        Path target = temporaryFolder.newFile(Path.of(path).getFileName().toString()).toPath();
        try (InputStream input = PortfolioShellTest.class.getResourceAsStream("/" + path)) //$NON-NLS-1$
        {
            if (input == null)
                throw new IllegalArgumentException("Fixture not found: " + path); //$NON-NLS-1$
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private final class ShellHarness implements AutoCloseable
    {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Terminal terminal;
        private final LineReader reader;
        private final PortfolioShell shell;

        private ShellHarness()
        {
            this(""); //$NON-NLS-1$
        }

        private ShellHarness(String input)
        {
            try
            {
                terminal = new DumbTerminal(
                                new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                output);
                reader = LineReaderBuilder.builder().terminal(terminal).build();
                shell = new PortfolioShell(terminal);
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        }

        private void execute(String command) throws Exception
        {
            shell.execute(reader, command);
        }

        private int run() throws IOException
        {
            return shell.run();
        }

        private int run(String initialFile) throws IOException
        {
            return shell.run(initialFile);
        }

        private String output()
        {
            terminal.flush();
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws Exception
        {
            terminal.close();
        }
    }
}
