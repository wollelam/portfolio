package name.abuchen.portfolio.ui.util.chart;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.text.MessageFormat;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.action.IAction;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.ui.Messages;

@SuppressWarnings("nls")
public class ChartCurrencyActionTest
{
    @Test
    public void testPortfolioCurrencyIsInitiallyCheckedAndRunSelectsInstrumentCurrency()
    {
        Client client = new Client();
        client.setBaseCurrency("CHF");
        AtomicReference<String> selection = new AtomicReference<>();

        ChartCurrencyAction action = new ChartCurrencyAction(client, ChartCurrencySelection.PORTFOLIO,
                        selection::set);

        assertThat(action.getStyle(), is(IAction.AS_CHECK_BOX));
        assertThat(action.isChecked(), is(true));
        assertThat(action.getSelection(), is(ChartCurrencySelection.PORTFOLIO));

        action.run();

        assertThat(selection.get(), is(ChartCurrencySelection.SECURITY));
        assertThat(action.getSelection(), is(ChartCurrencySelection.SECURITY));
        assertThat(action.isChecked(), is(false));
    }

    @Test
    public void testInstrumentCurrencyIsInitiallyUncheckedAndRunSelectsPortfolioCurrency()
    {
        Client client = new Client();
        client.setBaseCurrency("CHF");
        AtomicReference<String> selection = new AtomicReference<>();

        ChartCurrencyAction action = new ChartCurrencyAction(client, ChartCurrencySelection.SECURITY,
                        selection::set);

        assertThat(action.isChecked(), is(false));

        action.run();

        assertThat(selection.get(), is(ChartCurrencySelection.PORTFOLIO));
        assertThat(action.isChecked(), is(true));
    }

    @Test
    public void testRefreshLabelUsesCurrentPortfolioCurrency()
    {
        Client client = new Client();
        client.setBaseCurrency("CHF");
        ChartCurrencyAction action = new ChartCurrencyAction(client, ChartCurrencySelection.PORTFOLIO, ignored -> {
        });

        client.setBaseCurrency("EUR");
        action.refreshLabel();

        assertThat(action.getText(), is(MessageFormat.format(Messages.LabelUsePortfolioCurrency, "EUR")));
    }
}
