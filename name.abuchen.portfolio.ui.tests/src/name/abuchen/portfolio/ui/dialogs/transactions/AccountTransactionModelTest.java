package name.abuchen.portfolio.ui.dialogs.transactions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;

public class AccountTransactionModelTest
{
    @Test
    public void appliesSecurityWithholdingTaxRateInAccountCurrency()
    {
        var model = createModel(CurrencyUnit.EUR, CurrencyUnit.EUR, new BigDecimal("0.35")); //$NON-NLS-1$
        model.setGrossAmount(10000);
        model.setFxTaxes(500);

        model.applyWithholdingTaxRate();

        assertThat(model.getTaxes(), is(3500L));
        assertThat(model.getFxTaxes(), is(0L));
        assertThat(model.getTotal(), is(6500L));
    }

    @Test
    public void appliesSecurityWithholdingTaxRateInForeignCurrencyWithoutDoubleCounting()
    {
        var model = createModel(CurrencyUnit.EUR, CurrencyUnit.USD, new BigDecimal("0.15")); //$NON-NLS-1$
        model.setExchangeRate(new BigDecimal("0.80")); //$NON-NLS-1$
        model.setFxGrossAmount(10000);
        model.setTaxes(1000);
        model.setFxTaxes(2000);

        model.applyWithholdingTaxRate();

        assertThat(model.getTaxes(), is(0L));
        assertThat(model.getFxTaxes(), is(1500L));
        assertThat(model.getTotal(), is(6800L));
    }

    @Test
    public void roundsCalculatedTaxesToMoneyPrecision()
    {
        var model = createModel(CurrencyUnit.EUR, CurrencyUnit.EUR, new BigDecimal("0.15")); //$NON-NLS-1$
        model.setGrossAmount(101);

        model.applyWithholdingTaxRate();

        assertThat(model.getTaxes(), is(15L));
        assertThat(model.getTotal(), is(86L));
    }

    @Test
    public void doesNothingWithoutConfiguredRate()
    {
        var model = createModel(CurrencyUnit.EUR, CurrencyUnit.EUR, null);
        model.setGrossAmount(10000);
        model.setTaxes(1234);

        assertThat(model.getWithholdingTaxRate(), is(nullValue()));
        model.applyWithholdingTaxRate();

        assertThat(model.getTaxes(), is(1234L));
        assertThat(model.getTotal(), is(8766L));
    }

    private AccountTransactionModel createModel(String accountCurrency, String securityCurrency, BigDecimal rate)
    {
        var client = new Client();
        var model = new AccountTransactionModel(client, AccountTransaction.Type.DIVIDENDS);
        model.setExchangeRateProviderFactory(new ExchangeRateProviderFactory(client));

        var account = new Account();
        account.setCurrencyCode(accountCurrency);
        model.setAccount(account);

        var security = new Security("Security", securityCurrency); //$NON-NLS-1$
        security.setWithholdingTaxRate(rate);
        model.setSecurity(security);

        return model;
    }
}
