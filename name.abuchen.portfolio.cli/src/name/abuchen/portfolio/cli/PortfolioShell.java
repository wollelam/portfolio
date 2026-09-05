package name.abuchen.portfolio.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import name.abuchen.portfolio.checks.Checker;
import name.abuchen.portfolio.checks.Issue;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.AssetPosition;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.CategoryType;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.ReportingPeriod;
import name.abuchen.portfolio.util.Interval;

/**
 * Interactive shell for a Portfolio Performance client file.
 * <p>
 * This is intentionally a narrow prototype: quote updates remain in memory
 * until STORE explicitly persists them with the production client-file writer.
 * It exercises the production loader, valuation, and consistency-check
 * implementations without starting the SWT workbench.
 */
public class PortfolioShell
{
    private static final String ANSI_RESET = "\033[0m"; //$NON-NLS-1$
    private static final String ANSI_BOLD_CYAN = "\033[1;36m"; //$NON-NLS-1$
    private static final String ANSI_DIM_CYAN = "\033[2;36m"; //$NON-NLS-1$
    private static final String ANSI_GREEN = "\033[32m"; //$NON-NLS-1$
    private static final String ANSI_RED = "\033[31m"; //$NON-NLS-1$
    private static final Pattern COLOUR_VALUE = Pattern.compile(
                    "\\b[A-Z]{3} -?\\d[\\d.,'’]*|(?<![\\p{Alnum}_])[-+]\\d[\\d.,'’]*%?"); //$NON-NLS-1$
    private static final Pattern NEGATIVE_PERIOD = Pattern.compile("^-([1-9][0-9]*)([DWMY])$"); //$NON-NLS-1$

    private static final List<String> COMMANDS = List.of("OPEN", "RELOAD", "QUPD", "STORE", "VAL", "HOLD", "PERF", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                    "TPERF", "SEC", "FX", "ALLOC", "INCOME", "TXN", "DATA", "CHK", "HELP", "EXIT", "QUIT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
                    "SUMMARY"); //$NON-NLS-1$

    private volatile boolean running = true;
    private Client client;
    private Path clientFile;
    private Terminal terminal;
    private boolean modified;

    public PortfolioShell()
    {
        // default constructor for the Equinox application
    }

    PortfolioShell(Terminal terminal)
    {
        this.terminal = terminal;
    }

    public int run() throws IOException
    {
        return run(null);
    }

    /** Starts the interactive shell, optionally opening a client file first. */
    int run(String initialFile) throws IOException
    {
        boolean ownsTerminal = terminal == null;
        if (ownsTerminal)
            terminal = TerminalBuilder.builder().system(true).build();

        try
        {
            var reader = LineReaderBuilder.builder().terminal(terminal).parser(new DefaultParser())
                            .completer(new StringsCompleter(COMMANDS)).build();

            printWelcome();
            if (initialFile != null)
            {
                try
                {
                    open(reader, List.of("OPEN", initialFile)); //$NON-NLS-1$
                }
                catch (IllegalArgumentException | IOException e)
                {
                    println("Error: " + e.getMessage()); //$NON-NLS-1$
                }
            }
            while (running)
                readAndExecute(reader);
        }
        finally
        {
            if (ownsTerminal)
            {
                terminal.close();
                terminal = null;
            }
        }

        return 0;
    }

    public void stop()
    {
        running = false;
    }

    private void readAndExecute(LineReader reader)
    {
        try
        {
            execute(reader, reader.readLine(prompt()));
        }
        catch (UserInterruptException e)
        {
            // Ctrl-C cancels the current input without ending the shell.
        }
        catch (EndOfFileException e)
        {
            running = false;
        }
        catch (IllegalArgumentException | IllegalStateException | IOException e)
        {
            println("Error: " + e.getMessage()); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            println("Unexpected error: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    void execute(LineReader reader, String line) throws IOException
    {
        List<String> words = parse(line);
        if (words.isEmpty())
            return;

        String command = words.get(0).toUpperCase(Locale.ROOT);
        switch (command)
        {
            case "OPEN": //$NON-NLS-1$
                open(reader, words);
                break;
            case "RELOAD": //$NON-NLS-1$
                reload(reader, words);
                break;
            case "QUPD": //$NON-NLS-1$
                updateQuotes(words);
                break;
            case "STORE": //$NON-NLS-1$
                store(words);
                break;
            case "VAL": //$NON-NLS-1$
                value(words);
                break;
            case "HOLD": //$NON-NLS-1$
                holdings(words);
                break;
            case "PERF": //$NON-NLS-1$
                performance(words);
                break;
            case "SUMMARY": //$NON-NLS-1$
                var summaryOptions = performanceOptions(words, "SUMMARY"); //$NON-NLS-1$
                SummaryReport.render(requireClient(), Interval.of(summaryOptions.from(), summaryOptions.to()))
                                .forEach(this::println);
                break;
            case "TPERF": //$NON-NLS-1$
                topPerformers(words);
                break;
            case "SEC": //$NON-NLS-1$
                security(words);
                break;
            case "FX": //$NON-NLS-1$
                foreignExchange(words);
                break;
            case "ALLOC": //$NON-NLS-1$
                allocation(words);
                break;
            case "INCOME": //$NON-NLS-1$
                income(words);
                break;
            case "TXN": //$NON-NLS-1$
                transactions(words);
                break;
            case "DATA": //$NON-NLS-1$
                var dataOptions = performanceOptions(words, "DATA"); //$NON-NLS-1$
                DataQualityReport.render(requireClient(), Interval.of(dataOptions.from(), dataOptions.to()))
                                .forEach(this::println);
                break;
            case "CHK": //$NON-NLS-1$
                check(words);
                break;
            case "HELP": //$NON-NLS-1$
            case "?": //$NON-NLS-1$
                help();
                break;
            case "EXIT": //$NON-NLS-1$
            case "QUIT": //$NON-NLS-1$
                if (modified)
                    println("Discarding unsaved in-memory quote updates."); //$NON-NLS-1$
                running = false;
                break;
            default:
                throw new IllegalArgumentException("Unknown command '" + words.get(0) + "'. Type HELP."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private List<String> parse(String line)
    {
        Parser parser = new DefaultParser();
        ParsedLine parsed = parser.parse(line, line.length());
        return parsed.words();
    }

    private void open(LineReader reader, List<String> words) throws IOException
    {
        requireArgumentCount(words, 2, "OPEN <file>"); //$NON-NLS-1$

        Path file = Path.of(words.get(1)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file))
            throw new IllegalArgumentException("Not a readable file: " + file); //$NON-NLS-1$

        char[] password = null;
        try
        {
            if (ClientFactory.isEncrypted(file.toFile()))
                password = reader.readLine("Password: ", '*').toCharArray(); //$NON-NLS-1$

            Client loaded = ClientFactory.load(file.toFile(), password, new NullProgressMonitor());
            if (modified)
                println("Discarded unsaved in-memory quote updates."); //$NON-NLS-1$
            client = loaded;
            clientFile = file;
            modified = false;
            println("Opened " + file + " (" + client.getBaseCurrency() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        finally
        {
            if (password != null)
                Arrays.fill(password, '\0');
        }
    }

    private void reload(LineReader reader, List<String> words) throws IOException
    {
        requireArgumentCount(words, 1, "RELOAD"); //$NON-NLS-1$
        requireClient();
        open(reader, List.of("OPEN", clientFile.toString())); //$NON-NLS-1$
    }

    private void updateQuotes(List<String> words)
    {
        requireArgumentCount(words, 1, "QUPD"); //$NON-NLS-1$
        Client loaded = requireClient();
        println("Updating latest quotes in memory..."); //$NON-NLS-1$

        LatestQuoteUpdater.Result result = new LatestQuoteUpdater().update(loaded);
        if (result.getUpdatedCount() > 0)
            modified = true;

        println(String.format("Quotes: %d updated, %d unchanged, %d skipped, %d failed", result.getUpdatedCount(), //$NON-NLS-1$
                        result.getUnchangedCount(), result.getSkippedCount(), result.getFailedCount()));
        result.getEntries().stream().filter(entry -> entry.status() == LatestQuoteUpdater.Status.FAILED)
                        .forEach(entry -> println("- " + entry.security().getName() + ": " + entry.message())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void store(List<String> words) throws IOException
    {
        requireArgumentCount(words, 1, "STORE"); //$NON-NLS-1$
        Client loaded = requireClient();

        // Mirror the GUI's default Save behavior: preserve a sibling backup
        // before delegating all serialization to the production file writer.
        Path backup = backupFile(clientFile);
        Files.copy(clientFile, backup, StandardCopyOption.REPLACE_EXISTING);

        // This is the same production save API used by ClientInput in the GUI.
        // It retains the format, compression, and encryption flags from OPEN.
        ClientFactory.save(loaded, clientFile.toFile());
        modified = false;
        println("Stored " + clientFile + " (backup: " + backup + ")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Path backupFile(Path file)
    {
        String filename = file.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        String backupName = extension > 0 ? filename.substring(0, extension) + ".backup" + filename.substring(extension) //$NON-NLS-1$
                        : filename + ".backup"; //$NON-NLS-1$
        return file.resolveSibling(backupName);
    }

    private void value(List<String> words)
    {
        Client loaded = requireClient();
        LocalDate date = dateArgument(words, "VAL [YYYY-MM-DD]"); //$NON-NLS-1$
        ClientSnapshot snapshot = snapshot(loaded, date);

        println("Value at " + date + ": " + Values.Money.format(snapshot.getMonetaryAssets())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void holdings(List<String> words)
    {
        Client loaded = requireClient();
        LocalDate date = dateArgument(words, "HOLD [YYYY-MM-DD]"); //$NON-NLS-1$
        ClientSnapshot snapshot = snapshot(loaded, date);

        println(String.format("%-36s %14s %18s %8s", "Holding", "Shares", "Value", "Weight")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        snapshot.getAssetPositions().sorted(Comparator.comparing(AssetPosition::getValuation).reversed())
                        .forEach(position -> printHolding(position, snapshot.getCurrencyCode()));
    }

    private void performance(List<String> words)
    {
        Client loaded = requireClient();
        PerformanceOptions options = performanceOptions(words, "PERF"); //$NON-NLS-1$
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded), loaded.getBaseCurrency());
        var interval = Interval.of(options.from(), options.to());
        var performanceSnapshot = new ClientPerformanceSnapshot(loaded, converter, interval);

        println("Performance breakdown " + options.from() + " to " + options.to()); //$NON-NLS-1$ //$NON-NLS-2$
        printPerformanceBreakdown(performanceSnapshot);
    }

    private void topPerformers(List<String> words)
    {
        Client loaded = requireClient();
        PerformanceOptions options = performanceOptions(words, "TPERF"); //$NON-NLS-1$
        var warnings = new ArrayList<Exception>();
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded), loaded.getBaseCurrency());
        var interval = Interval.of(options.from(), options.to());
        var portfolioIndex = PerformanceIndex.forClient(loaded, converter, interval, warnings);
        var performers = PerformerRanking.rank(loaded, converter, interval, portfolioIndex, -1);

        println("Top performers " + options.from() + " to " + options.to()); //$NON-NLS-1$ //$NON-NLS-2$
        if (performers.isEmpty())
        {
            println("No security holdings found for this period."); //$NON-NLS-1$
            return;
        }

        int count = Math.min(options.limit(), performers.size());
        println("Best performers (TTWROR):"); //$NON-NLS-1$
        printPerformerHeader("TTWROR"); //$NON-NLS-1$
        for (int index = 0; index < count; index++)
            printPerformer(performers.get(index), loaded.getBaseCurrency(), false);

        println("Worst performers (TTWROR):"); //$NON-NLS-1$
        printPerformerHeader("TTWROR"); //$NON-NLS-1$
        for (int index = performers.size() - 1; index >= performers.size() - count; index--)
            printPerformer(performers.get(index), loaded.getBaseCurrency(), false);

        var currencyPerformers = PerformerRanking.sortByCurrencyPerformance(performers);
        println("Best performers (currency performance):"); //$NON-NLS-1$
        printPerformerHeader("Abs. return"); //$NON-NLS-1$
        for (int index = 0; index < count; index++)
            printPerformer(currencyPerformers.get(index), loaded.getBaseCurrency(), true);

        println("Worst performers (currency performance):"); //$NON-NLS-1$
        printPerformerHeader("Abs. return"); //$NON-NLS-1$
        for (int index = currencyPerformers.size() - 1; index >= currencyPerformers.size() - count; index--)
            printPerformer(currencyPerformers.get(index), loaded.getBaseCurrency(), true);

        if (!warnings.isEmpty())
            println(warnings.size() + " performance calculation warning(s)."); //$NON-NLS-1$
    }

    private void security(List<String> words)
    {
        requireArgumentCountAtLeast(words, 2, "SEC <ticker|name> [period]"); //$NON-NLS-1$
        PerformanceOptions options = performanceOptionsWithOffset(words, 2, "SEC"); //$NON-NLS-1$
        SecurityReport.render(requireClient(), words.get(1), Interval.of(options.from(), options.to()))
                        .forEach(this::println);
    }

    private void foreignExchange(List<String> words)
    {
        Client loaded = requireClient();
        PerformanceOptions options = performanceOptions(words, "FX"); //$NON-NLS-1$
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded), loaded.getBaseCurrency());
        println("FX rates to " + loaded.getBaseCurrency() + " at " + options.to()); //$NON-NLS-1$ //$NON-NLS-2$
        var currencies = new java.util.TreeSet<String>();
        loaded.getSecurities().stream().map(Security::getCurrencyCode).filter(java.util.Objects::nonNull)
                        .forEach(currencies::add);
        loaded.getAccounts().forEach(account -> currencies.add(account.getCurrencyCode()));
        for (String currency : currencies)
        {
            var rate = converter.getRateIfAvailable(options.to(), currency);
            println(currency + ": " + rate.map(r -> r.getValue() + " (rate date " + r.getTime() + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            .orElse("unavailable")); //$NON-NLS-1$
        }
    }

    private void allocation(List<String> words)
    {
        Client loaded = requireClient();
        PerformanceOptions options = performanceOptions(words, "ALLOC"); //$NON-NLS-1$
        var snapshot = snapshot(loaded, options.to());
        var totals = new java.util.TreeMap<String, Long>();
        snapshot.getAssetPositions().forEach(p -> totals.merge(p.getSecurity() == null ? "Cash" : "Securities", //$NON-NLS-1$ //$NON-NLS-2$
                        p.getValuation().getAmount(), Long::sum));
        println("Allocation at " + options.to() + ":"); //$NON-NLS-1$ //$NON-NLS-2$
        totals.forEach((label, amount) -> println(String.format("%-16s %18s", label, //$NON-NLS-1$
                        Values.Money.format(Money.of(loaded.getBaseCurrency(), amount)))));
    }

    private void income(List<String> words)
    {
        Client loaded = requireClient();
        PerformanceOptions options = performanceOptions(words, "INCOME"); //$NON-NLS-1$
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded), loaded.getBaseCurrency());
        var performance = new ClientPerformanceSnapshot(loaded, converter, Interval.of(options.from(), options.to()));
        println("Income " + options.from() + " to " + options.to() + ":"); //$NON-NLS-1$ //$NON-NLS-2$
        printBreakdownValue("Earnings", performance.getValue(CategoryType.EARNINGS), true); //$NON-NLS-1$
        printBreakdownValue("Fees", performance.getValue(CategoryType.FEES).multiply(-1), true); //$NON-NLS-1$
        printBreakdownValue("Taxes", performance.getValue(CategoryType.TAXES).multiply(-1), true); //$NON-NLS-1$
    }

    private void transactions(List<String> words)
    {
        var periodWords = new ArrayList<String>();
        periodWords.add(words.getFirst());
        String securityFilter = null;
        String ownerFilter = null;
        String typeFilter = null;
        for (int i = 1; i < words.size(); i++)
        {
            switch (words.get(i).toLowerCase(Locale.ROOT))
            {
                case "--security" -> securityFilter = optionValue(words, ++i, "--security"); //$NON-NLS-1$ //$NON-NLS-2$
                case "--account" -> ownerFilter = optionValue(words, ++i, "--account"); //$NON-NLS-1$ //$NON-NLS-2$
                case "--type" -> typeFilter = optionValue(words, ++i, "--type"); //$NON-NLS-1$ //$NON-NLS-2$
                default -> periodWords.add(words.get(i));
            }
        }
        PerformanceOptions options = performanceOptions(periodWords, "TXN"); //$NON-NLS-1$
        TransactionReport.render(requireClient(), Interval.of(options.from(), options.to()), securityFilter,
                        ownerFilter, typeFilter).forEach(this::println);
    }

    private PerformanceOptions performanceOptions(List<String> words, String command)
    {
        LocalDate to = LocalDate.now();
        String period = "1D"; //$NON-NLS-1$
        LocalDate explicitFrom = null;
        boolean endDateSpecified = false;
        int limit = 5;
        boolean periodSeen = false;

        for (int index = 1; index < words.size(); index++)
        {
            String word = words.get(index);
            if ("--limit".equalsIgnoreCase(word)) //$NON-NLS-1$
            {
                limit = positiveInteger(optionValue(words, ++index, "--limit"), "--limit"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if ("--to".equalsIgnoreCase(word)) //$NON-NLS-1$
            {
                to = isoDate(optionValue(words, ++index, "--to")); //$NON-NLS-1$
                endDateSpecified = true;
            }
            else if ("--from".equalsIgnoreCase(word)) //$NON-NLS-1$
            {
                explicitFrom = isoDate(optionValue(words, ++index, "--from")); //$NON-NLS-1$
            }
            else if (!periodSeen)
            {
                period = word.toUpperCase(Locale.ROOT);
                periodSeen = true;
            }
            else
            {
                throw new IllegalArgumentException(
                                "Usage: " + command + " [nD|-nD|nW|-nW|nM|-nM|nY|-nY|MTD|YTD] [--from DATE] [--to DATE] [--limit N]"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        if (!periodSeen && explicitFrom == null && !endDateSpecified)
        {
            Interval interval = new ReportingPeriod.PreviousTradingDay().toInterval(to);
            return new PerformanceOptions(interval.getStart(), interval.getEnd(), limit);
        }

        Interval offsetPeriod = negativePeriod(period, to);
        if (offsetPeriod != null)
        {
            LocalDate from = explicitFrom != null ? explicitFrom : offsetPeriod.getStart();
            if (!from.isBefore(offsetPeriod.getEnd()))
                throw new IllegalArgumentException("Performance start date must be before the end date."); //$NON-NLS-1$
            return new PerformanceOptions(from, offsetPeriod.getEnd(), limit);
        }

        LocalDate from = explicitFrom != null ? explicitFrom : switch (period)
        {
            case "1D" -> to.minusDays(1); //$NON-NLS-1$
            case "MTD" -> to.withDayOfMonth(1).minusDays(1); //$NON-NLS-1$
            case "YTD" -> to.withDayOfYear(1).minusDays(1); //$NON-NLS-1$
            default -> periodBefore(period, to);
        };
        if (!from.isBefore(to))
            throw new IllegalArgumentException("Performance start date must be before the end date."); //$NON-NLS-1$
        return new PerformanceOptions(from, to, limit);
    }

    private Interval negativePeriod(String period, LocalDate relativeTo)
    {
        Matcher matcher = NEGATIVE_PERIOD.matcher(period);
        if (!matcher.matches())
            return null;

        int offset;
        try
        {
            offset = Integer.parseInt(matcher.group(1));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid period: " + period); //$NON-NLS-1$
        }

        LocalDate includedStart;
        LocalDate end;
        switch (matcher.group(2))
        {
            case "D": //$NON-NLS-1$
                end = relativeTo.minusDays(offset);
                includedStart = end;
                break;
            case "W": //$NON-NLS-1$
                includedStart = relativeTo.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                .minusWeeks(offset);
                end = includedStart.plusDays(6);
                break;
            case "M": //$NON-NLS-1$
                includedStart = relativeTo.withDayOfMonth(1).minusMonths(offset);
                end = includedStart.plusMonths(1).minusDays(1);
                break;
            case "Y": //$NON-NLS-1$
                includedStart = relativeTo.withDayOfYear(1).minusYears(offset);
                end = includedStart.plusYears(1).minusDays(1);
                break;
            default:
                throw new IllegalArgumentException("Invalid period: " + period); //$NON-NLS-1$
        }
        return Interval.of(includedStart.minusDays(1), end);
    }

    private PerformanceOptions performanceOptionsWithOffset(List<String> words, int offset, String command)
    {
        List<String> arguments = new ArrayList<>();
        arguments.add(command);
        arguments.addAll(words.subList(offset, words.size()));
        return performanceOptions(arguments, command);
    }

    private LocalDate periodBefore(String period, LocalDate to)
    {
        if (period.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) //$NON-NLS-1$
            return isoDate(period);
        if (period.length() < 2)
            return isoDate(period);

        try
        {
            int amount = Integer.parseInt(period.substring(0, period.length() - 1));
            if (amount <= 0)
                throw new NumberFormatException();

            return switch (period.charAt(period.length() - 1))
            {
                case 'D' -> to.minusDays(amount);
                case 'W' -> to.minusWeeks(amount);
                case 'M' -> to.minusMonths(amount);
                case 'Y' -> to.minusYears(amount);
                default -> isoDate(period);
            };
        }
        catch (NumberFormatException e)
        {
            // handled by the validation error below
        }

        throw new IllegalArgumentException("Period must be a positive number followed by D, W, or Y, for example 2Y."); //$NON-NLS-1$
    }

    private String optionValue(List<String> words, int index, String option)
    {
        if (index >= words.size())
            throw new IllegalArgumentException("Missing value for " + option); //$NON-NLS-1$
        return words.get(index);
    }

    private int positiveInteger(String value, String option)
    {
        try
        {
            int parsed = Integer.parseInt(value);
            if (parsed > 0)
                return parsed;
        }
        catch (NumberFormatException e)
        {
            // handled by the validation error below
        }
        throw new IllegalArgumentException(option + " must be a positive integer."); //$NON-NLS-1$
    }

    private LocalDate isoDate(String value)
    {
        try
        {
            return LocalDate.parse(value);
        }
        catch (DateTimeParseException e)
        {
            throw new IllegalArgumentException("Expected ISO date YYYY-MM-DD: " + value); //$NON-NLS-1$
        }
    }

    private void printPerformer(PerformerRanking.Performer performer, String currency, boolean absoluteReturn)
    {
        println(String.format("  %-36s %10s %10s %18s %18s", abbreviate(performer.name(), 36), //$NON-NLS-1$
                        formattedPercent(absoluteReturn ? performer.currencyPerformancePercent()
                                        : performer.performance()),
                        formattedPercent(performer.irr()),
                        signedMoney(Money.of(currency, performer.currencyPerformance())),
                        Values.Money.format(Money.of(currency, performer.value()))));
    }

    private void printPerformerHeader(String returnLabel)
    {
        println(String.format("  %-36s %10s %10s %18s %18s", "Instrument", returnLabel, "IRR p.a.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "Contribution", "Current value")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void printPerformanceBreakdown(ClientPerformanceSnapshot snapshot)
    {
        println("Performance breakdown:"); //$NON-NLS-1$
        printBreakdownValue("Opening value", snapshot.getValue(CategoryType.INITIAL_VALUE), false); //$NON-NLS-1$
        printBreakdownValue("Unrealized capital gains", snapshot.getValue(CategoryType.CAPITAL_GAINS), true); //$NON-NLS-1$
        printBreakdownValue("Realized capital gains", snapshot.getValue(CategoryType.REALIZED_CAPITAL_GAINS), true); //$NON-NLS-1$
        printBreakdownValue("Earnings", snapshot.getValue(CategoryType.EARNINGS), true); //$NON-NLS-1$
        printBreakdownValue("Fees", snapshot.getValue(CategoryType.FEES).multiply(-1), true); //$NON-NLS-1$
        printBreakdownValue("Taxes", snapshot.getValue(CategoryType.TAXES).multiply(-1), true); //$NON-NLS-1$
        printBreakdownValue("Currency gains", snapshot.getValue(CategoryType.CURRENCY_GAINS), true); //$NON-NLS-1$
        printBreakdownValue("Net transfers", snapshot.getValue(CategoryType.TRANSFERS), true); //$NON-NLS-1$
        printBreakdownValue("Ending value", snapshot.getValue(CategoryType.FINAL_VALUE), false); //$NON-NLS-1$
        printBreakdownValue("Total performance", snapshot.getAbsoluteDelta(), true); //$NON-NLS-1$
    }

    private void printBreakdownValue(String label, Money value, boolean signed)
    {
        println(String.format("  %-28s %18s", label, signed ? signedMoney(value) : Values.Money.format(value))); //$NON-NLS-1$
    }

    private String formattedPercent(double value)
    {
        return Double.isFinite(value) ? String.format("%+.2f%%", value * 100) : "n/a"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String signedMoney(Money value)
    {
        String formatted = Values.Money.format(value);
        return value.isPositive() ? "+" + formatted : formatted; //$NON-NLS-1$
    }

    private record PerformanceOptions(LocalDate from, LocalDate to, int limit) {}


    private void printHolding(AssetPosition position, String currency)
    {
        String description = abbreviate(position.getDescription(), 36);
        String shares = Values.Share.format(position.getPosition().getShares());
        String value = Values.Money.format(position.getValuation(), currency);
        double weight = position.getShare();
        String share = Double.isFinite(weight) ? String.format("%.2f%%", weight * 100) : "n/a"; //$NON-NLS-1$ //$NON-NLS-2$
        println(String.format("%-36s %14s %18s %8s", description, shares, value, share)); //$NON-NLS-1$
    }

    private void check(List<String> words)
    {
        requireArgumentCount(words, 1, "CHK"); //$NON-NLS-1$
        List<Issue> issues = Checker.runAll(requireClient());
        if (issues.isEmpty())
        {
            println("No consistency issues found."); //$NON-NLS-1$
            return;
        }

        println(issues.size() + " consistency issue(s):"); //$NON-NLS-1$
        for (Issue issue : issues)
            println("- " + issue.getLabel()); //$NON-NLS-1$
    }

    private ClientSnapshot snapshot(Client loaded, LocalDate date)
    {
        var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded), loaded.getBaseCurrency());
        return ClientSnapshot.create(loaded, converter, date);
    }

    private LocalDate dateArgument(List<String> words, String usage)
    {
        if (words.size() == 1)
            return LocalDate.now();
        requireArgumentCount(words, 2, usage);

        try
        {
            return LocalDate.parse(words.get(1));
        }
        catch (DateTimeParseException e)
        {
            throw new IllegalArgumentException("Expected ISO date YYYY-MM-DD: " + words.get(1)); //$NON-NLS-1$
        }
    }

    private Client requireClient()
    {
        if (client == null)
            throw new IllegalStateException("No file is open. Use OPEN <file> first."); //$NON-NLS-1$
        return client;
    }

    private void requireArgumentCount(List<String> words, int expected, String usage)
    {
        if (words.size() != expected)
            throw new IllegalArgumentException("Usage: " + usage); //$NON-NLS-1$
    }

    private void requireArgumentCountAtLeast(List<String> words, int minimum, String usage)
    {
        if (words.size() < minimum)
            throw new IllegalArgumentException("Usage: " + usage); //$NON-NLS-1$
    }

    private String prompt()
    {
        String prompt;
        if (clientFile == null)
            prompt = "portfolio> "; //$NON-NLS-1$
        else
            prompt = clientFile.getFileName() + (modified ? " [modified]> " : "> "); //$NON-NLS-1$ //$NON-NLS-2$
        return supportsColour() ? ANSI_BOLD_CYAN + prompt + ANSI_RESET : prompt;
    }

    private void printWelcome()
    {
        println("Portfolio Performance CLI prototype — type HELP for commands."); //$NON-NLS-1$
    }

    private void help()
    {
        println("OPEN <file>          Load a .portfolio, .xml, or .zip client file"); //$NON-NLS-1$
        println("RELOAD               Discard in-memory updates and reload the file"); //$NON-NLS-1$
        println("QUPD                 Fetch latest quotes into memory (does not save)"); //$NON-NLS-1$
        println("STORE                Save in-memory updates using the production file writer"); //$NON-NLS-1$
        println("VAL [YYYY-MM-DD]     Show total value in the base currency"); //$NON-NLS-1$
        println("HOLD [YYYY-MM-DD]    List holdings, cash, values, and weights"); //$NON-NLS-1$
        println("PERF [period] [--from DATE] [--to DATE]"); //$NON-NLS-1$
        println("TPERF [period] [--from DATE] [--to DATE] [--limit N]"); //$NON-NLS-1$
        println("SUMMARY [period]     Overview, cash, returns and quote/FX checks"); //$NON-NLS-1$
        println("SEC <ticker|name> [period]  Show a security at the period end date"); //$NON-NLS-1$
        println("FX [period]          Show base-currency FX rates at the period end date"); //$NON-NLS-1$
        println("ALLOC [period]       Show cash versus securities allocation at the period end date"); //$NON-NLS-1$
        println("INCOME [period]      Show earnings, fees, and taxes for the period"); //$NON-NLS-1$
        println("TXN [period] [--security NAME] [--account NAME] [--type BUY|SELL|DIVIDENDS|...]"); //$NON-NLS-1$
        println("DATA [period]        Check quote freshness, FX data, and calculation warnings"); //$NON-NLS-1$
        println("Periods: nD, nW, nM, nY, MTD, YTD; -nD/-nW/-nM/-nY select past calendar periods"); //$NON-NLS-1$
        println("CHK                  Run the registered consistency checks"); //$NON-NLS-1$
        println("HELP                 Show this help"); //$NON-NLS-1$
        println("EXIT                 Exit without changing the loaded file"); //$NON-NLS-1$
    }

    private String abbreviate(String value, int width)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        return value.length() <= width ? value : value.substring(0, width - 1) + "…"; //$NON-NLS-1$
    }

    private void println(String message)
    {
        terminal.writer().println(styleOutput(message));
        terminal.flush();
    }

    private String styleOutput(String message)
    {
        if (!supportsColour())
            return message;

        String prefix = ANSI_DIM_CYAN + "│ " + ANSI_RESET; //$NON-NLS-1$
        if (message.startsWith("Error:") || message.startsWith("Unexpected error:") //$NON-NLS-1$ //$NON-NLS-2$
                        || message.startsWith("Warning:")) //$NON-NLS-1$
            return prefix + ANSI_RED + message + ANSI_RESET;
        if (isHeading(message))
            return prefix + ANSI_BOLD_CYAN + message + ANSI_RESET;

        return prefix + colourValues(message);
    }

    static String colourValues(String message)
    {
        Matcher matcher = COLOUR_VALUE.matcher(message);
        StringBuilder styled = new StringBuilder();
        while (matcher.find())
        {
            String value = matcher.group();
            String colour = value.indexOf('-') >= 0 ? ANSI_RED : ANSI_GREEN;
            matcher.appendReplacement(styled, Matcher.quoteReplacement(colour + value + ANSI_RESET));
        }
        matcher.appendTail(styled);
        return styled.toString();
    }

    private boolean isHeading(String message)
    {
        return message.endsWith(":") || message.startsWith("PORTFOLIO SUMMARY") //$NON-NLS-1$ //$NON-NLS-2$
                        || message.startsWith("DATA QUALITY") //$NON-NLS-1$
                        || message.startsWith("Performance breakdown ") || message.startsWith("Top performers ") //$NON-NLS-1$ //$NON-NLS-2$
                        || message.startsWith("Transaction ledger ") || message.startsWith("Security: "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean supportsColour()
    {
        return terminal != null && !"dumb".equalsIgnoreCase(terminal.getType()) && System.getenv("NO_COLOR") == null; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
