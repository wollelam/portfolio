package name.abuchen.portfolio.util;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;

/**
 * Classifies whether an exchange rate can be carried forward to a requested
 * date. A rate may be carried forward across days on which the relevant market
 * is closed, but not across a trading day for which no new rate was supplied.
 */
public final class ExchangeRateGapClassifier
{
    public enum Status
    {
        /** A rate was supplied for the requested date. */
        AVAILABLE_EXACT,

        /**
         * The most recent rate precedes the requested date, but every
         * intervening date is a non-trading day.
         */
        EXPECTED_NON_TRADING_CARRY_FORWARD,

        /**
         * The most recent rate predates at least one intervening trading day.
         */
        UNEXPECTED_BUSINESS_DAY_GAP,

        /** No rate on or before the requested date was supplied. */
        UNAVAILABLE_BEFORE_COVERAGE
    }

    private ExchangeRateGapClassifier()
    {
    }

    /**
     * Classifies a rate which was resolved for {@code requestedDate}.
     *
     * @param requestedDate
     *            the date for which a rate is needed
     * @param resolvedRate
     *            the rate resolved on or before {@code requestedDate}; an
     *            empty value means that no historical rate is available
     * @param tradeCalendar
     *            the calendar that determines expected non-trading days
     */
    public static Status classify(LocalDate requestedDate, Optional<ExchangeRate> resolvedRate,
                    TradeCalendar tradeCalendar)
    {
        Objects.requireNonNull(requestedDate);
        Objects.requireNonNull(resolvedRate);
        Objects.requireNonNull(tradeCalendar);

        if (resolvedRate.isEmpty() || resolvedRate.get().getTime().isAfter(requestedDate))
            return Status.UNAVAILABLE_BEFORE_COVERAGE;

        return classify(requestedDate, resolvedRate.get().getTime(), tradeCalendar);
    }

    public static Status classify(LocalDate requestedDate, LocalDate rateDate, TradeCalendar tradeCalendar)
    {
        Objects.requireNonNull(requestedDate);
        Objects.requireNonNull(rateDate);
        Objects.requireNonNull(tradeCalendar);

        if (rateDate.isAfter(requestedDate))
            return Status.UNAVAILABLE_BEFORE_COVERAGE;

        if (rateDate.equals(requestedDate))
            return Status.AVAILABLE_EXACT;

        for (LocalDate date = rateDate.plusDays(1); !date.isAfter(requestedDate); date = date.plusDays(1))
        {
            if (!tradeCalendar.isHoliday(date))
                return Status.UNEXPECTED_BUSINESS_DAY_GAP;
        }

        return Status.EXPECTED_NON_TRADING_CARRY_FORWARD;
    }

    /**
     * Classifies a rate using the calendar configured on {@code security}, or
     * the application's default calendar when the security inherits it.
     */
    public static Status classify(LocalDate requestedDate, Optional<ExchangeRate> resolvedRate, Security security)
    {
        Objects.requireNonNull(security);
        return classify(requestedDate, resolvedRate, TradeCalendarManager.getInstance(security));
    }

    public static Status classify(LocalDate requestedDate, LocalDate rateDate, Security security)
    {
        Objects.requireNonNull(security);
        return classify(requestedDate, rateDate, TradeCalendarManager.getInstance(security));
    }

    /**
     * Looks up the historical rate and classifies its availability.
     */
    public static Status classify(LocalDate requestedDate, ExchangeRateTimeSeries series, TradeCalendar tradeCalendar)
    {
        Objects.requireNonNull(series);
        return classify(requestedDate, series.lookupRateIfAvailable(requestedDate), tradeCalendar);
    }

    /**
     * Looks up the historical rate and classifies it using the calendar
     * configured on {@code security}.
     */
    public static Status classify(LocalDate requestedDate, ExchangeRateTimeSeries series, Security security)
    {
        Objects.requireNonNull(security);
        return classify(requestedDate, series, TradeCalendarManager.getInstance(security));
    }
}
