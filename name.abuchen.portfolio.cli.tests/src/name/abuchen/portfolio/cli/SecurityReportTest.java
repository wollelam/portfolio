package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.util.Interval;

public class SecurityReportTest
{
    @Test
    public void reportsCandidatesForAnAmbiguousPartialMatch()
    {
        Client client = new Client();
        client.addSecurity(security("Apple Inc.")); //$NON-NLS-1$
        client.addSecurity(security("Apple Fund")); //$NON-NLS-1$

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> SecurityReport.render(client, "apple", //$NON-NLS-1$
                                        Interval.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))));

        assertThat(error.getMessage(), containsString("Ambiguous security 'apple'")); //$NON-NLS-1$
        assertThat(error.getMessage(), containsString("Apple Fund")); //$NON-NLS-1$
        assertThat(error.getMessage(), containsString("Apple Inc.")); //$NON-NLS-1$
    }

    @Test
    public void quoteAsOfPeriodEndNeverUsesAFuturePrice()
    {
        Client client = new Client();
        Security security = security("Example"); //$NON-NLS-1$
        security.addPrice(new SecurityPrice(LocalDate.of(2026, 1, 10), 100_000L));
        security.addPrice(new SecurityPrice(LocalDate.of(2026, 2, 10), 200_000L));
        client.addSecurity(security);

        var lines = SecurityReport.render(client, "Example", //$NON-NLS-1$
                        Interval.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertThat(lines.stream().filter(line -> line.startsWith("Latest quote")).findFirst().orElseThrow(), //$NON-NLS-1$
                        containsString("price date 2026-01-10")); //$NON-NLS-1$
    }

    private Security security(String name)
    {
        return new Security(name, "EUR"); //$NON-NLS-1$
    }
}
