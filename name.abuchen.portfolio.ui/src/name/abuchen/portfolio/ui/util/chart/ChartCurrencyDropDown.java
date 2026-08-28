package name.abuchen.portfolio.ui.util.chart;

import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.DropDown;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.util.Pair;

public class ChartCurrencyDropDown extends DropDown
{
    private final Client client;
    private final Consumer<String> selectionListener;
    private String selection;

    public ChartCurrencyDropDown(Client client, String initialSelection, Consumer<String> selectionListener)
    {
        super(getLabel(client, initialSelection));
        this.client = Objects.requireNonNull(client);
        this.selection = Objects.requireNonNull(initialSelection);
        this.selectionListener = Objects.requireNonNull(selectionListener);
        setMenuListener(this::menuAboutToShow);
    }

    private void menuAboutToShow(IMenuManager manager)
    {
        Action portfolioCurrency = new SimpleAction(
                        MessageFormat.format(Messages.LabelUsePortfolioCurrency, client.getBaseCurrency()),
                        a -> select(ChartCurrencySelection.PORTFOLIO));
        portfolioCurrency.setChecked(ChartCurrencySelection.PORTFOLIO.equals(selection));
        manager.add(portfolioCurrency);

        Action securityCurrency = new SimpleAction(Messages.LabelUseSecurityCurrency,
                        a -> select(ChartCurrencySelection.SECURITY));
        securityCurrency.setChecked(ChartCurrencySelection.SECURITY.equals(selection));
        manager.add(securityCurrency);
        manager.add(new Separator());

        Function<CurrencyUnit, Action> asAction = unit -> {
            Action action = new SimpleAction(unit.getLabel(), a -> select(unit.getCurrencyCode()));
            action.setChecked(Objects.equals(selection, unit.getCurrencyCode()));
            return action;
        };

        client.getUsedCurrencies().forEach(unit -> manager.add(asAction.apply(unit)));
        manager.add(new Separator());

        List<Pair<String, List<CurrencyUnit>>> available = CurrencyUnit.getAvailableCurrencyUnitsGrouped();
        for (Pair<String, List<CurrencyUnit>> pair : available)
        {
            MenuManager submenu = new MenuManager(pair.getLeft());
            manager.add(submenu);
            pair.getRight().forEach(unit -> submenu.add(asAction.apply(unit)));
        }
    }

    private void select(String newSelection)
    {
        selection = newSelection;
        setLabel(getLabel(client, selection));
        selectionListener.accept(selection);
    }

    public void refreshLabel()
    {
        setLabel(getLabel(client, selection));
    }

    private static String getLabel(Client client, String selection)
    {
        if (ChartCurrencySelection.SECURITY.equals(selection))
            return Messages.LabelUseSecurityCurrency;
        else if (ChartCurrencySelection.PORTFOLIO.equals(selection))
            return client.getBaseCurrency();
        else
            return selection;
    }
}
