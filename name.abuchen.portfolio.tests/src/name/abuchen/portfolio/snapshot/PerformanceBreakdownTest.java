package name.abuchen.portfolio.snapshot;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.CategoryType;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown.Entry;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown.EntryKind;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown.EntryType;

@SuppressWarnings("nls")
public class PerformanceBreakdownTest
{
    @Test
    public void testCalculationReconcilesSnapshotAndAppliesSignsExplicitly()
    {
        Client client = new Client();

        new AccountBuilder() //
                        .deposit_("2010-01-01", 1_000_00) //
                        .deposit_("2011-01-01", 50_00) //
                        .fees_refund("2011-02-01", 10_00) //
                        .addTo(client);

        ClientPerformanceSnapshot snapshot = new ClientPerformanceSnapshot(client, new TestCurrencyConverter(),
                        LocalDate.parse("2010-12-31"), LocalDate.parse("2011-12-31"));

        PerformanceBreakdown breakdown = PerformanceBreakdown.createCalculation(snapshot);

        assertThat(breakdown.isReconciled(), is(true));
        assertThat(breakdown.getEntries().size(), is(4));
        assertThat(entry(breakdown, EntryType.INITIAL_VALUE).getKind(), is(EntryKind.START));
        assertThat(entry(breakdown, EntryType.FINAL_VALUE).getKind(), is(EntryKind.TOTAL));
        assertThat(entry(breakdown, EntryType.FEES).getAmount(), is(Money.of(CurrencyUnit.EUR, 10_00)));
        assertThat(entry(breakdown, EntryType.TRANSFERS).getAmount(), is(Money.of(CurrencyUnit.EUR, 50_00)));
        assertThat(breakdown.getEntries().stream()
                        .noneMatch(entry -> entry.getKind() == EntryKind.CHANGE && entry.getAmount().isZero()), is(true));
        assertThat(entry(breakdown, EntryType.FEES).getSourceCategory(),
                        is(snapshot.getCategoryByType(ClientPerformanceSnapshot.CategoryType.FEES)));
        assertThat(breakdown.getEntries().stream().noneMatch(entry -> entry.getType() == EntryType.OTHER), is(true));
    }

    @Test
    public void testCalculationAddsAnOtherEntryForAForeignExchangeRoundingResidual()
    {
        ClientPerformanceSnapshot snapshot = new SyntheticSnapshot(Money.of(CurrencyUnit.EUR, 100_00),
                        Money.of(CurrencyUnit.EUR, 1_00), Money.of(CurrencyUnit.EUR, 100_99));

        PerformanceBreakdown breakdown = PerformanceBreakdown.createCalculation(snapshot);

        assertThat(breakdown.isReconciled(), is(true));
        assertThat(entry(breakdown, EntryType.OTHER).getKind(), is(EntryKind.CHANGE));
        assertThat(entry(breakdown, EntryType.OTHER).getAmount(), is(Money.of(CurrencyUnit.EUR, -1)));
        assertThat(breakdown.getEntries().indexOf(entry(breakdown, EntryType.OTHER)),
                        is(breakdown.getEntries().indexOf(entry(breakdown, EntryType.FINAL_VALUE)) - 1));
    }

    @Test
    public void testContributionsAggregateCapitalGainsAndFeesBySecurity()
    {
        Client client = new Client();

        Security security = new SecurityBuilder() //
                        .addPrice("2010-01-01", Values.Quote.factorize(100)) //
                        .addPrice("2011-06-01", Values.Quote.factorize(110)) //
                        .addTo(client);

        var account = new AccountBuilder() //
                        .deposit_("2010-01-01", 1_00) //
                        .withdraw("2011-01-15", 99_00) //
                        .addTo(client);

        new PortfolioBuilder(account) //
                        .buy(security, "2010-01-01", Values.Share.factorize(10), 1_00) //
                        .sell(security, "2011-01-15", Values.Share.factorize(1), 99_00, 1) //
                        .addTo(client);

        ClientPerformanceSnapshot snapshot = new ClientPerformanceSnapshot(client, new TestCurrencyConverter(),
                        LocalDate.parse("2010-12-31"), LocalDate.parse("2011-12-31"));

        PerformanceBreakdown breakdown = PerformanceBreakdown.createContributions(snapshot);

        assertThat(breakdown.isReconciled(), is(true));
        assertThat(entry(breakdown, EntryType.INITIAL_VALUE).getAmount(), is(Money.of(CurrencyUnit.EUR, 0)));
        assertThat(entry(breakdown, EntryType.TOTAL_PERFORMANCE).getAmount(), is(snapshot.getAbsoluteDelta()));

        Entry contribution = breakdown.getEntries().stream() //
                        .filter(entry -> entry.getType() == EntryType.SECURITY) //
                        .findFirst().orElseThrow();
        assertThat(contribution.getSecurity(), is(security));
        assertThat(contribution.getAmount(), is(snapshot.getAbsoluteDelta()));
    }

    @Test
    public void testContributionsKeepUnassignedRefundsAsPositiveOtherPerformance()
    {
        Client client = new Client();

        new AccountBuilder() //
                        .fees_refund("2011-01-01", 100_00) //
                        .addTo(client);

        ClientPerformanceSnapshot snapshot = new ClientPerformanceSnapshot(client, new TestCurrencyConverter(),
                        LocalDate.parse("2010-12-31"), LocalDate.parse("2011-12-31"));

        PerformanceBreakdown breakdown = PerformanceBreakdown.createContributions(snapshot);

        assertThat(breakdown.isReconciled(), is(true));
        assertThat(entry(breakdown, EntryType.OTHER).getAmount(), is(Money.of(CurrencyUnit.EUR, 100_00)));
        assertThat(entry(breakdown, EntryType.TOTAL_PERFORMANCE).getAmount(), is(Money.of(CurrencyUnit.EUR, 100_00)));
    }

    @Test
    public void testLimitContributionsGroupsExcludedSecuritiesWithoutChangingTheTotal()
    {
        Client client = new Client();

        Security winner = new SecurityBuilder() //
                        .addPrice("2010-01-01", Values.Quote.factorize(100)) //
                        .addPrice("2011-06-01", Values.Quote.factorize(120)) //
                        .addTo(client);
        Security loser = new SecurityBuilder() //
                        .addPrice("2010-01-01", Values.Quote.factorize(100)) //
                        .addPrice("2011-06-01", Values.Quote.factorize(90)) //
                        .addTo(client);

        Portfolio portfolio = new Portfolio();
        portfolio.setReferenceAccount(new AccountBuilder().addTo(client));
        portfolio.addTransaction(new PortfolioTransaction(LocalDateTime.parse("2010-01-01T00:00"), CurrencyUnit.EUR,
                        1_00, winner, Values.Share.factorize(10), PortfolioTransaction.Type.BUY, 0, 0));
        portfolio.addTransaction(new PortfolioTransaction(LocalDateTime.parse("2010-01-01T00:00"), CurrencyUnit.EUR,
                        1_00, loser, Values.Share.factorize(10), PortfolioTransaction.Type.BUY, 0, 0));
        client.addPortfolio(portfolio);

        ClientPerformanceSnapshot snapshot = new ClientPerformanceSnapshot(client, new TestCurrencyConverter(),
                        LocalDate.parse("2010-12-31"), LocalDate.parse("2011-12-31"));

        PerformanceBreakdown limited = PerformanceBreakdown.createContributions(snapshot).limitContributions(1);

        assertThat(limited.isReconciled(), is(true));
        assertThat(limited.getEntries().stream().filter(entry -> entry.getType() == EntryType.SECURITY).count(), is(1L));
        assertThat(entry(limited, EntryType.OTHER).getAmount(), is(Money.of(CurrencyUnit.EUR, -100_00)));
        assertThat(entry(limited, EntryType.TOTAL_PERFORMANCE).getAmount(), is(Money.of(CurrencyUnit.EUR, 100_00)));
    }

    private Entry entry(PerformanceBreakdown breakdown, EntryType type)
    {
        return breakdown.getEntries().stream().filter(entry -> entry.getType() == type).findFirst().orElseThrow();
    }

    /**
     * Represents a snapshot whose independently rounded foreign-exchange
     * categories differ by one cent from the portfolio's final value.
     */
    private static final class SyntheticSnapshot extends ClientPerformanceSnapshot
    {
        private final Map<CategoryType, Category> categories = new EnumMap<>(CategoryType.class);

        private SyntheticSnapshot(Money initialValue, Money capitalGains, Money finalValue)
        {
            super(new Client(), new TestCurrencyConverter(), LocalDate.parse("2015-01-05"), LocalDate.parse("2015-01-16"));

            for (CategoryType type : CategoryType.values())
                categories.put(type, new Category(type.name(), "", Money.of(initialValue.getCurrencyCode(), 0)));

            categories.put(CategoryType.INITIAL_VALUE, new Category("Initial", "", initialValue));
            categories.put(CategoryType.CAPITAL_GAINS, new Category("Capital gains", "+", capitalGains));
            categories.put(CategoryType.FINAL_VALUE, new Category("Final", "=", finalValue));
        }

        @Override
        public Category getCategoryByType(CategoryType type)
        {
            return categories.get(type);
        }
    }
}
