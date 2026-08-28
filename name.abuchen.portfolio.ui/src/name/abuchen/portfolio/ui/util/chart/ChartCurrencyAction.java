package name.abuchen.portfolio.ui.util.chart;

import java.text.MessageFormat;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.ui.Messages;

/** A check-box action selecting portfolio (checked) or instrument (unchecked) currency. */
public class ChartCurrencyAction extends Action
{
    private final Client client;
    private final Consumer<String> selectionListener;
    private String selection;

    public ChartCurrencyAction(Client client, String initialSelection, Consumer<String> selectionListener)
    {
        super(getLabel(client), IAction.AS_CHECK_BOX);
        this.client = Objects.requireNonNull(client);
        this.selection = Objects.requireNonNull(initialSelection);
        this.selectionListener = Objects.requireNonNull(selectionListener);
        setChecked(ChartCurrencySelection.PORTFOLIO.equals(selection));
    }

    @Override
    public void run()
    {
        selection = ChartCurrencySelection.PORTFOLIO.equals(selection) ? ChartCurrencySelection.SECURITY
                        : ChartCurrencySelection.PORTFOLIO;
        setChecked(ChartCurrencySelection.PORTFOLIO.equals(selection));
        selectionListener.accept(selection);
    }

    private static String getLabel(Client client)
    {
        return MessageFormat.format(Messages.LabelUsePortfolioCurrency, client.getBaseCurrency());
    }

    public void refreshLabel()
    {
        setText(getLabel(client));
    }

    public void setSelection(String selection)
    {
        this.selection = Objects.requireNonNull(selection);
        setChecked(ChartCurrencySelection.PORTFOLIO.equals(selection));
    }

    public String getSelection()
    {
        return selection;
    }
}
