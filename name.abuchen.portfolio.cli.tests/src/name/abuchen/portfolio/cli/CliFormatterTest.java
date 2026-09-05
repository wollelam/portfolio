package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

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
            assertThat(CliFormatter.format("%+.2f", 1.5d), is("+1.50"));
        }
        finally
        {
            Locale.setDefault(originalLocale);
        }
    }
}
