package name.abuchen.portfolio.ui.dialogs.transactions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;

public class AccountTransactionModelTest
{
    @Test
    public void calculatesTaxRateFromTaxesAndGrossDividend()
    {
        var model = new AccountTransactionModel(new Client(), AccountTransaction.Type.DIVIDENDS);
        model.setGrossAmount(10000);
        model.setTaxes(1500);

        assertThat(model.getTaxRate(), comparesEqualTo(new BigDecimal("0.15")));
        assertThat(model.getTotal(), is(8500L));
    }

    @Test
    public void calculatesTaxesAndTotalFromTaxRate()
    {
        var model = new AccountTransactionModel(new Client(), AccountTransaction.Type.DIVIDENDS);
        model.setGrossAmount(10000);
        model.setTaxRate(new BigDecimal("0.35"));

        assertThat(model.getTaxes(), is(3500L));
        assertThat(model.getTotal(), is(6500L));
    }

    @Test
    public void roundsCalculatedTaxesToMoneyPrecision()
    {
        var model = new AccountTransactionModel(new Client(), AccountTransaction.Type.DIVIDENDS);
        model.setGrossAmount(101);
        model.setTaxRate(new BigDecimal("0.15"));

        assertThat(model.getTaxes(), is(15L));
        assertThat(model.getTotal(), is(86L));
    }

    @Test
    public void zeroGrossDividendHasZeroTaxRate()
    {
        var model = new AccountTransactionModel(new Client(), AccountTransaction.Type.DIVIDENDS);
        model.setTaxes(100);

        assertThat(model.getTaxRate(), comparesEqualTo(BigDecimal.ZERO));
        model.setTaxRate(new BigDecimal("0.35"));
        assertThat(model.getTaxes(), is(0L));
    }
}
