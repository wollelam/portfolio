package name.abuchen.portfolio.cli;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class SummaryReportTest
{
    @Test
    public void portfolioImpactReconcilesCurrencyContributionToPortfolioReturn()
    {
        assertThat(SummaryReport.portfolioImpact(20_000L, 200_000L, 0.20d), is("+2.00 pp"));
        assertThat(SummaryReport.portfolioImpact(-20_000L, 200_000L, 0.20d), is("-2.00 pp"));
        assertThat(SummaryReport.portfolioImpact(20_000L, 0L, 0.20d), is("n/a"));
    }

    @Test
    public void depositIncreasesValueAndCashButNotPerformance()
    {
        var originalLocale = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("en-CH"));

            var client = new Client();
            client.setBaseCurrency("EUR");
            var account = new Account("Cash");
            account.setCurrencyCode("EUR");
            client.addAccount(account);
            account.addTransaction(new AccountTransaction(LocalDateTime.of(2024, 1, 2, 12, 0), "EUR", 100_000L,
                            null, AccountTransaction.Type.DEPOSIT));
            String output = String.join("\n", SummaryReport.render(client,
                            Interval.of(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))));
            assertThat(output, containsString("Total value       EUR 1,000.00"));
            assertThat(output, containsString("Cash              EUR 1,000.00"));
            assertThat(output, containsString("Net deposits      EUR 1,000.00"));
            assertThat(output, containsString("Performance       EUR 0.00"));
            assertThat(output, containsString("Return (IRR, annualized)"));
            assertThat(output, containsString("Top contributors:"));
            assertThat(output, containsString("Top detractors:"));
            assertThat(output, containsString("IRR p.a."));
            assertThat(output, not(containsString("Data checks")));
        }
        finally
        {
            Locale.setDefault(originalLocale);
        }
    }
}
