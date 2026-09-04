package name.abuchen.portfolio.ui.wizards.security;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

@SuppressWarnings("nls")
public class EditSecurityModelTest
{
    @Test
    public void readsAndAppliesWithholdingTaxRate()
    {
        var security = new Security("Security", "EUR");
        security.setWithholdingTaxRate(new BigDecimal("0.15"));

        var model = new EditSecurityModel(new Client(), security);
        assertThat(model.getWithholdingTaxRate(), is(new BigDecimal("0.15")));

        model.setWithholdingTaxRate(new BigDecimal("0.26375"));
        assertThat(security.getWithholdingTaxRate(), is(new BigDecimal("0.15")));

        model.applyChanges();
        assertThat(security.getWithholdingTaxRate(), is(new BigDecimal("0.26375")));
    }
}
