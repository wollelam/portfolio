package name.abuchen.portfolio.ui.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;

import name.abuchen.portfolio.snapshot.ReportingPeriod;
import name.abuchen.portfolio.util.Interval;

/** Calendar navigation for the waterfall's reporting period. */
public final class ReportingPeriodNavigation
{
    public enum Unit
    {
        DAY, WEEK, MONTH, QUARTER, YEAR
    }

    private Unit unit = Unit.MONTH;
    private ReportingPeriod selected;
    private Interval base;
    private int offset;

    public Unit getUnit()
    {
        return unit;
    }

    public void synchronize(ReportingPeriod period, LocalDate today)
    {
        if (period.equals(selected))
            return;

        selected = period;
        base = period.toInterval(today);
        offset = 0;
        if (period instanceof ReportingPeriod.CurrentWeek || period instanceof ReportingPeriod.PreviousWeek)
            unit = Unit.WEEK;
        else if (period instanceof ReportingPeriod.CurrentQuarter || period instanceof ReportingPeriod.PreviousQuarter)
            unit = Unit.QUARTER;
        else if (period instanceof ReportingPeriod.YearX || period instanceof ReportingPeriod.YearToDate
                        || period instanceof ReportingPeriod.PreviousYear)
            unit = Unit.YEAR;
        else if (period instanceof ReportingPeriod.LastXDays || period instanceof ReportingPeriod.PreviousDay
                        || period instanceof ReportingPeriod.PreviousTradingDay
                        || period instanceof ReportingPeriod.LastXTradingDays)
            unit = Unit.DAY;
        else if (period instanceof ReportingPeriod.CurrentMonth || period instanceof ReportingPeriod.PreviousMonth)
            unit = Unit.MONTH;
        else if (base.getDays() == 1)
            unit = Unit.DAY;
        else if (base.getDays() == 7)
            unit = Unit.WEEK;
        else
        {
            LocalDate first = base.getStart().plusDays(1);
            LocalDate afterLast = base.getEnd().plusDays(1);
            if (first.getDayOfYear() == 1 && first.plusYears(1).equals(afterLast))
                unit = Unit.YEAR;
            else if (first.getDayOfMonth() == 1 && (first.getMonthValue() - 1) % 3 == 0
                            && first.plusMonths(3).equals(afterLast))
                unit = Unit.QUARTER;
            else if (first.getDayOfMonth() == 1 && first.plusMonths(1).equals(afterLast))
                unit = Unit.MONTH;
        }
    }

    public ReportingPeriod select(Unit unit, LocalDate date)
    {
        this.unit = unit;
        LocalDate start = switch (unit)
        {
            case DAY -> date;
            case WEEK -> date.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
            case MONTH -> date.withDayOfMonth(1);
            case QUARTER -> date.withDayOfMonth(1).withMonth((date.getMonthValue() - 1) / 3 * 3 + 1);
            case YEAR -> date.withDayOfYear(1);
        };
        base = Interval.of(start.minusDays(1), add(start, 1).minusDays(1));
        offset = 0;
        selected = new ReportingPeriod.FromXtoY(base);
        return selected;
    }

    public ReportingPeriod move(int direction)
    {
        offset += direction;
        // Always shift from the original interval, so short months and leap
        // years cannot cause date drift when navigating back and forth.
        LocalDate start = add(base.getStart().plusDays(1), offset).minusDays(1);
        LocalDate end = base.getEnd().getDayOfMonth() == base.getEnd().lengthOfMonth()
                        && (unit == Unit.MONTH || unit == Unit.QUARTER || unit == Unit.YEAR)
                                        ? add(base.getEnd().plusDays(1), offset).minusDays(1)
                                        : add(base.getEnd(), offset);
        selected = new ReportingPeriod.FromXtoY(start, end);
        return selected;
    }

    private LocalDate add(LocalDate date, int amount)
    {
        return switch (unit)
        {
            case DAY -> date.plusDays(amount);
            case WEEK -> date.plusWeeks(amount);
            case MONTH -> date.plusMonths(amount);
            case QUARTER -> date.plusMonths(amount * 3L);
            case YEAR -> date.plus(amount, ChronoUnit.YEARS);
        };
    }
}
