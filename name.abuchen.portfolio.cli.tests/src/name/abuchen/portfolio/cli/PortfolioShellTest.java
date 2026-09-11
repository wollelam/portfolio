package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
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
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.ExchangeRateProvider;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.online.QuoteFeedData;
import name.abuchen.portfolio.online.QuoteFeedException;
import name.abuchen.portfolio.util.Interval;

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
    public void quoteUpdateReportsPortfolioValueChange() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var quoteUpdater = new LatestQuoteUpdater(feedId -> new FixedQuoteFeed());
        try (ShellHarness harness = new ShellHarness("", quoteUpdater)) //$NON-NLS-1$
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("QUPD"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Quotes: ")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Portfolio value: ")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("(change ")); //$NON-NLS-1$
        }
    }

    @Test
    public void quoteUpdateRefreshesExchangeRateCache() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var quoteUpdater = new LatestQuoteUpdater(feedId -> new FixedQuoteFeed());
        var exchangeRateProvider = new RecordingExchangeRateProvider();
        try (ShellHarness harness = new ShellHarness("", quoteUpdater, //$NON-NLS-1$
                        new ExchangeRateCache(List.of(exchangeRateProvider))))
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("QUPD"); //$NON-NLS-1$

            assertThat(exchangeRateProvider.operations, is(List.of("load", "update", "save"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    @Test
    public void foreignExchangeShowsDailyChange() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var client = ClientFactory.load(file.toFile(), null, new NullProgressMonitor());
        var exchangeRate = new Security("USD/EUR", "USD"); //$NON-NLS-1$ //$NON-NLS-2$
        exchangeRate.setTargetCurrencyCode("EUR"); //$NON-NLS-1$
        exchangeRate.addPrice(new SecurityPrice(LocalDate.of(2015, 1, 15), Values.Quote.factorize(1.0d)));
        exchangeRate.addPrice(new SecurityPrice(LocalDate.of(2015, 1, 16), Values.Quote.factorize(1.1d)));
        client.addSecurity(exchangeRate);
        ClientFactory.save(client, file.toFile());

        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("FX --to 2015-01-16"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("+10.00%")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("rate date 2015-01-16")); //$NON-NLS-1$
        }
    }

    @Test
    public void commandAbbreviationsDispatchToTheirCommands() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var quoteUpdater = new LatestQuoteUpdater(feedId -> new FixedQuoteFeed());
        try (ShellHarness harness = new ShellHarness("", quoteUpdater)) //$NON-NLS-1$
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("qu"); //$NON-NLS-1$
            harness.execute("su 1M --to 2015-01-16"); //$NON-NLS-1$

            assertThat(harness.output(), containsString("Quotes: ")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("PORTFOLIO SUMMARY")); //$NON-NLS-1$
        }
    }

    @Test
    public void quoteUpdateSummarizesErrorsAndErrorsCommandShowsDetails() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var quoteUpdater = new LatestQuoteUpdater(feedId -> new FailingQuoteFeed());
        try (ShellHarness harness = new ShellHarness("", quoteUpdater)) //$NON-NLS-1$
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("QUPD"); //$NON-NLS-1$

            String summary = harness.output();
            assertThat(summary, containsString("Warning: Errors encountered on ")); //$NON-NLS-1$
            assertThat(summary, containsString("Use ERRORS to view details.")); //$NON-NLS-1$
            assertThat(summary, not(containsString("unavailable"))); //$NON-NLS-1$

            harness.execute("ERRORS"); //$NON-NLS-1$
            assertThat(harness.output(), containsString("QUOTE UPDATE ERRORS (")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("unavailable")); //$NON-NLS-1$
        }
    }

    @Test
    public void holdingsExcludeRetiredAccounts() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var client = ClientFactory.load(file.toFile(), null, new NullProgressMonitor());
        client.getAccounts().stream().filter(account -> "Account EUR".equals(account.getName())).findFirst()
                        .orElseThrow().setRetired(true);
        ClientFactory.save(client, file.toFile());

        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("HOLD 2015-01-16"); //$NON-NLS-1$

            assertThat(harness.output(), not(containsString("Account EUR"))); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Account USD")); //$NON-NLS-1$
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
    public void colourizerDoesNotMistakeAnInstrumentSuffixForACurrency()
    {
        String styled = PortfolioShell.colourValues(
                        "  Fundsmith Equity T INC               -0.45%       CHF -2,668.29   -0.08 pp   >1000.00%"); //$NON-NLS-1$

        assertThat(styled, containsString("INC               \033[31m-0.45%\033[0m")); //$NON-NLS-1$
        assertThat(styled, containsString("\033[31mCHF -2,668.29\033[0m")); //$NON-NLS-1$
        assertThat(styled, containsString("\033[32m>1000.00%\033[0m")); //$NON-NLS-1$
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
    public void commandsWithoutAPeriodUseTheMostRecentTradingDay() throws Exception
    {
        Path file = copyFixture("scenarios/currency_sample.xml"); //$NON-NLS-1$
        var interval = PortfolioShell.mostRecentTradingDayInterval(LocalDate.now());
        try (ShellHarness harness = new ShellHarness())
        {
            harness.execute("OPEN " + file); //$NON-NLS-1$
            harness.execute("PERF"); //$NON-NLS-1$
            harness.execute("TPERF"); //$NON-NLS-1$

            assertThat(harness.output(), containsString(interval.getStart() + " to " + interval.getEnd())); //$NON-NLS-1$
        }
    }

    @Test
    public void defaultPerformanceIntervalUsesCurrentOrPreviousTradingDay()
    {
        assertInterval(PortfolioShell.mostRecentTradingDayInterval(LocalDate.of(2026, 9, 4)), "2026-09-03",
                        "2026-09-04"); //$NON-NLS-1$ //$NON-NLS-2$
        assertInterval(PortfolioShell.mostRecentTradingDayInterval(LocalDate.of(2026, 9, 5)), "2026-09-03",
                        "2026-09-04"); //$NON-NLS-1$ //$NON-NLS-2$
        assertInterval(PortfolioShell.mostRecentTradingDayInterval(LocalDate.of(2026, 9, 6)), "2026-09-03",
                        "2026-09-04"); //$NON-NLS-1$ //$NON-NLS-2$
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
            assertThat(harness.output(), containsString("Best performers (TTWROR):")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Worst performers (TTWROR):")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Best performers (currency performance):")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Worst performers (currency performance):")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("IRR p.a.")); //$NON-NLS-1$
            assertThat(harness.output(), containsString("Quote")); //$NON-NLS-1$
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
    public void quitIsNoLongerACommand() throws Exception
    {
        try (ShellHarness harness = new ShellHarness())
        {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                            () -> harness.execute("QUIT")); //$NON-NLS-1$
            assertThat(error.getMessage(), is("Unknown command 'QUIT'. Type HELP.")); //$NON-NLS-1$
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
            assertThat(harness.output(), containsString("IRR p.a.")); //$NON-NLS-1$
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

    private void assertInterval(Interval interval, String start, String end)
    {
        assertThat(interval.getStart(), is(LocalDate.parse(start)));
        assertThat(interval.getEnd(), is(LocalDate.parse(end)));
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
            this(input, new LatestQuoteUpdater());
        }

        private ShellHarness(String input, LatestQuoteUpdater quoteUpdater)
        {
            this(input, quoteUpdater, ExchangeRateCache.disabled());
        }

        private ShellHarness(String input, LatestQuoteUpdater quoteUpdater, ExchangeRateCache exchangeRateCache)
        {
            try
            {
                terminal = new DumbTerminal(
                                new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                output);
                reader = LineReaderBuilder.builder().terminal(terminal).build();
                shell = new PortfolioShell(terminal, quoteUpdater, exchangeRateCache);
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

    private static final class RecordingExchangeRateProvider implements ExchangeRateProvider
    {
        private final List<String> operations = new java.util.ArrayList<>();

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

    private static final class FixedQuoteFeed implements QuoteFeed
    {
        @Override
        public String getId()
        {
            return "TEST"; //$NON-NLS-1$
        }

        @Override
        public String getName()
        {
            return getId();
        }

        @Override
        public Optional<LatestSecurityPrice> getLatestQuote(Security security)
        {
            return Optional.of(new LatestSecurityPrice(LocalDate.now(), 200_000L));
        }

        @Override
        public QuoteFeedData getHistoricalQuotes(Security security, boolean collectRawResponse)
        {
            QuoteFeedData data = new QuoteFeedData();
            data.addPrice(new LatestSecurityPrice(LocalDate.now(), 200_000L));
            return data;
        }
    }

    private static final class FailingQuoteFeed implements QuoteFeed
    {
        @Override
        public String getId()
        {
            return "FAIL"; //$NON-NLS-1$
        }

        @Override
        public String getName()
        {
            return getId();
        }

        @Override
        public Optional<LatestSecurityPrice> getLatestQuote(Security security) throws QuoteFeedException
        {
            throw new UnavailableQuoteFeedException();
        }

        @Override
        public QuoteFeedData getHistoricalQuotes(Security security, boolean collectRawResponse)
                        throws QuoteFeedException
        {
            throw new UnavailableQuoteFeedException();
        }
    }

    private static final class UnavailableQuoteFeedException extends QuoteFeedException
    {
        private static final long serialVersionUID = 1L;

        private UnavailableQuoteFeedException()
        {
            super("unavailable"); //$NON-NLS-1$
        }
    }
}
