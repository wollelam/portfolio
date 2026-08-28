package name.abuchen.portfolio.util;

import static name.abuchen.portfolio.util.ExchangeRateGapClassifier.Status.AVAILABLE_EXACT;
import static name.abuchen.portfolio.util.ExchangeRateGapClassifier.Status.EXPECTED_NON_TRADING_CARRY_FORWARD;
import static name.abuchen.portfolio.util.ExchangeRateGapClassifier.Status.UNAVAILABLE_BEFORE_COVERAGE;
import static name.abuchen.portfolio.util.ExchangeRateGapClassifier.Status.UNEXPECTED_BUSINESS_DAY_GAP;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;

@SuppressWarnings("nls")
public class ExchangeRateGapClassifierTest
{
    private static final TradeCalendar NYSE = TradeCalendarManager.getInstance("nyse");

    @Test
    public void classifiesExactRateAsAvailable()
    {
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-03"), rate("2024-07-03"), NYSE),
                        is(AVAILABLE_EXACT));
    }

    @Test
    public void allowsCarryForwardAcrossWeekends()
    {
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-07"), rate("2024-07-05"), NYSE),
                        is(EXPECTED_NON_TRADING_CARRY_FORWARD));
    }

    @Test
    public void allowsCarryForwardAcrossRecognizedHolidays()
    {
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-04"), rate("2024-07-03"), NYSE),
                        is(EXPECTED_NON_TRADING_CARRY_FORWARD));
    }

    @Test
    public void rejectsCarryForwardAcrossTradingDays()
    {
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-08"), rate("2024-07-05"), NYSE),
                        is(UNEXPECTED_BUSINESS_DAY_GAP));
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-08"),
                        LocalDate.parse("2024-07-05"), NYSE), is(UNEXPECTED_BUSINESS_DAY_GAP));
    }

    @Test
    public void reportsNoHistoricalRateAsUnavailable()
    {
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-03"), Optional.empty(), NYSE),
                        is(UNAVAILABLE_BEFORE_COVERAGE));
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-03"), rate("2024-07-04"), NYSE),
                        is(UNAVAILABLE_BEFORE_COVERAGE));
    }

    @Test
    public void resolvesConfiguredSecurityCalendar()
    {
        Security security = new Security();
        security.setCalendar("nyse");

        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-04"), rate("2024-07-03"), security),
                        is(EXPECTED_NON_TRADING_CARRY_FORWARD));
    }

    @Test
    public void looksUpHistoricalRateFromSeries()
    {
        var series = new ExchangeRateTimeSeriesImpl(null, "USD", "CHF");
        series.addRate(new ExchangeRate(LocalDate.parse("2024-07-03"), BigDecimal.ONE));

        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-04"), series, NYSE),
                        is(EXPECTED_NON_TRADING_CARRY_FORWARD));
        assertThat(ExchangeRateGapClassifier.classify(LocalDate.parse("2024-07-02"), series, NYSE),
                        is(UNAVAILABLE_BEFORE_COVERAGE));
    }

    private Optional<ExchangeRate> rate(String date)
    {
        return Optional.of(new ExchangeRate(LocalDate.parse(date), BigDecimal.ONE));
    }
}
