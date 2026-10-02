package name.abuchen.portfolio.ui.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.LocalDate;
import java.util.Locale;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.ReportingPeriod;
import name.abuchen.portfolio.ui.util.ReportingPeriodNavigation.Unit;
import name.abuchen.portfolio.util.Interval;

public class ReportingPeriodNavigationTest
{
    private static final LocalDate TODAY = LocalDate.of(2024, 3, 15);

    @Test
    public void testMonthNavigationKeepsCalendarBoundariesAndReturnsWithoutDrift()
    {
        var navigation = new ReportingPeriodNavigation();
        assertInterval(navigation.select(Unit.MONTH, TODAY), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31));
        assertInterval(navigation.move(-1), LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29));
        assertInterval(navigation.move(-1), LocalDate.of(2023, 12, 31), LocalDate.of(2024, 1, 31));
        assertInterval(navigation.move(1), LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29));
        assertInterval(navigation.move(1), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31));
    }

    @Test
    public void testDayQuarterAndYearNavigation()
    {
        var navigation = new ReportingPeriodNavigation();
        navigation.select(Unit.DAY, LocalDate.of(2024, 3, 1));
        assertInterval(navigation.move(-1), LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29));
        navigation.select(Unit.QUARTER, TODAY);
        assertInterval(navigation.move(-1), LocalDate.of(2023, 9, 30), LocalDate.of(2023, 12, 31));
        assertInterval(navigation.move(1), LocalDate.of(2023, 12, 31), LocalDate.of(2024, 3, 31));
        navigation.select(Unit.YEAR, TODAY);
        assertInterval(navigation.move(1), LocalDate.of(2024, 12, 31), LocalDate.of(2025, 12, 31));
    }

    @Test
    public void testWeekUsesLocaleAndMovesSevenDays()
    {
        Locale original = Locale.getDefault();
        try
        {
            var navigation = new ReportingPeriodNavigation();
            Locale.setDefault(Locale.GERMANY);
            assertInterval(navigation.select(Unit.WEEK, TODAY), LocalDate.of(2024, 3, 10), LocalDate.of(2024, 3, 17));
            assertInterval(navigation.move(-1), LocalDate.of(2024, 3, 3), LocalDate.of(2024, 3, 10));
            Locale.setDefault(Locale.US);
            assertInterval(navigation.select(Unit.WEEK, TODAY), LocalDate.of(2024, 3, 9), LocalDate.of(2024, 3, 16));
        }
        finally
        {
            Locale.setDefault(original);
        }
    }

    @Test
    public void testManualPeriodSelectionResetsNavigation()
    {
        var navigation = new ReportingPeriodNavigation();
        navigation.synchronize(new ReportingPeriod.CurrentWeek(), TODAY);
        assertThat(navigation.getUnit(), is(Unit.WEEK));
        ReportingPeriod moved = navigation.move(-1);
        navigation.synchronize(moved, TODAY);
        assertInterval(navigation.move(1), new ReportingPeriod.CurrentWeek().toInterval(TODAY).getStart(),
                        new ReportingPeriod.CurrentWeek().toInterval(TODAY).getEnd());
        navigation.synchronize(new ReportingPeriod.YearX(2020), TODAY);
        assertThat(navigation.getUnit(), is(Unit.YEAR));
        assertInterval(navigation.move(-1), LocalDate.of(2018, 12, 31), LocalDate.of(2019, 12, 31));
    }

    @Test
    public void testSavedCalendarPeriodRetainsNavigationUnit()
    {
        for (Unit unit : Unit.values())
        {
            var navigation = new ReportingPeriodNavigation();
            ReportingPeriod period = navigation.select(unit, TODAY);
            var reopened = new ReportingPeriodNavigation();
            reopened.synchronize(period, TODAY);
            assertThat(reopened.getUnit(), is(unit));
        }
    }

    @Test
    public void testPartialPeriodPreservesEndAndRoundTrip()
    {
        var navigation = new ReportingPeriodNavigation();
        navigation.select(Unit.MONTH, TODAY);
        navigation.synchronize(new ReportingPeriod.FromXtoY(LocalDate.of(2024, 2, 29), TODAY), TODAY);
        assertInterval(navigation.move(-1), LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 15));
        assertInterval(navigation.move(1), LocalDate.of(2024, 2, 29), TODAY);
    }

    private static void assertInterval(ReportingPeriod period, LocalDate start, LocalDate end)
    {
        Interval interval = period.toInterval(TODAY);
        assertThat(interval.getStart(), is(start));
        assertThat(interval.getEnd(), is(end));
    }
}
