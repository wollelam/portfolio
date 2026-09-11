package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.Test;

import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class CliFormatterTest
{
    @Test
    public void formatsNumbersWithRootLocale()
    {
        var originalLocale = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("en-CH"));

            assertThat(CliFormatter.money(Money.of("EUR", 435_438L)), is("EUR 4,354.38"));
            assertThat(CliFormatter.percent(0.1234d), is("12.34%"));
            assertThat(CliFormatter.signedPercent(0.1234d), is("+12.34%"));
            assertThat(CliFormatter.signedPercent(-0.1234d), is("-12.34%"));
            assertThat(CliFormatter.irr(10.0d), is("+1000.00%"));
            assertThat(CliFormatter.irr(10.0001d), is(">1000.00%"));
            assertThat(CliFormatter.irr(Double.POSITIVE_INFINITY), is(">1000.00%"));
            assertThat(CliFormatter.irr(Double.NaN), is("n/a"));
            assertThat(CliFormatter.format("%+.2f", 1.5d), is("+1.50"));
        }
        finally
        {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    public void coloursComparableValuesWithThreeStrengths()
    {
        var low = CliLine.builder().appendValue("+1.00%", 10, 0.1d, CliLine.Metric.RETURN).build(); //$NON-NLS-1$
        var medium = CliLine.builder().appendValue("+1.50%", 10, 0.15d, CliLine.Metric.RETURN).build(); //$NON-NLS-1$
        var high = CliLine.builder().appendValue("+3.00%", 10, 0.3d, CliLine.Metric.RETURN).build(); //$NON-NLS-1$
        var negative = CliLine.builder().appendValue("-3.00%", 10, -0.3d, CliLine.Metric.RETURN).build(); //$NON-NLS-1$
        var lines = List.of(low, medium, high, negative);
        var scales = ValueColourScale.forLines(lines);

        assertThat(ValueColourScale.apply(low, scales), containsString(ValueColourScale.DIM_GREEN));
        assertThat(ValueColourScale.apply(medium, scales), containsString(ValueColourScale.GREEN));
        assertThat(ValueColourScale.apply(high, scales), containsString(ValueColourScale.BRIGHT_GREEN));
        assertThat(ValueColourScale.apply(negative, scales), containsString(ValueColourScale.BRIGHT_RED));
    }

    @Test
    public void doesNotColourZeroOrMissingValues()
    {
        var zero = CliLine.builder().appendValue("+0.00%", 10, 0d, CliLine.Metric.RETURN).build(); //$NON-NLS-1$
        var missing = CliLine.builder().appendValue("n/a", 10, Double.NaN, CliLine.Metric.RETURN).build(); //$NON-NLS-1$
        var scales = ValueColourScale.forLines(List.of(zero, missing));

        assertThat(ValueColourScale.apply(zero, scales), is("    +0.00%")); //$NON-NLS-1$
        assertThat(ValueColourScale.apply(missing, scales), is("       n/a")); //$NON-NLS-1$
    }

    @Test
    public void coloursForeignExchangeChanges()
    {
        var increase = CliLine.builder().appendValue("+1.00%", 10, 0.01d, CliLine.Metric.FX_CHANGE).build(); //$NON-NLS-1$
        var decrease = CliLine.builder().appendValue("-1.00%", 10, -0.01d, CliLine.Metric.FX_CHANGE).build(); //$NON-NLS-1$
        var scales = ValueColourScale.forLines(List.of(increase, decrease));

        assertThat(ValueColourScale.apply(increase, scales), containsString(ValueColourScale.BRIGHT_GREEN));
        assertThat(ValueColourScale.apply(decrease, scales), containsString(ValueColourScale.BRIGHT_RED));
    }
}
