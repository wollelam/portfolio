package name.abuchen.portfolio.ui.views;

import java.time.LocalDate;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.CostMethod;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.snapshot.filter.WithoutTaxesFilter;
import name.abuchen.portfolio.ui.Images;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.selection.SecuritySelection;
import name.abuchen.portfolio.ui.selection.SelectionService;
import name.abuchen.portfolio.ui.util.ClientFilterDropDown;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.DropDown;
import name.abuchen.portfolio.ui.util.LabelOnly;
import name.abuchen.portfolio.ui.util.LogoManager;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.chart.WaterfallChart;
import name.abuchen.portfolio.ui.util.chart.WaterfallChartCSVExporter;
import name.abuchen.portfolio.ui.util.chart.WaterfallDataset;
import name.abuchen.portfolio.ui.views.panes.HistoricalPricesPane;
import name.abuchen.portfolio.ui.views.panes.InformationPanePage;
import name.abuchen.portfolio.ui.views.panes.SecurityEventsPane;
import name.abuchen.portfolio.ui.views.panes.SecurityPriceChartPane;
import name.abuchen.portfolio.ui.views.panes.TradesPane;
import name.abuchen.portfolio.ui.views.panes.TransactionsPane;
import name.abuchen.portfolio.util.Interval;

/**
 * Shows a monetary waterfall for one reporting period.
 *
 * <p>
 * The view deliberately lives next to the performance calculation and chart
 * views instead of adding another tab to {@link PerformanceView}. A waterfall
 * has its own controls (in particular the instrument limit) and can therefore
 * be refreshed independently from the calculation tables.
 * </p>
 */
public class PerformanceWaterfallView extends AbstractHistoricView
{
    private static final int WATERFALL_LOGO_SIZE = 24;

    private static final String KEY_MODE = "PerformanceWaterfallView-mode"; //$NON-NLS-1$
    private static final String KEY_RANGE_MODE = "PerformanceWaterfallView-range-mode"; //$NON-NLS-1$
    private static final String KEY_CAPITAL_GAIN_METHOD = "PerformanceWaterfallView-capital-gain-method"; //$NON-NLS-1$
    private static final String KEY_PRE_TAX = "PerformanceWaterfallView-pre-tax"; //$NON-NLS-1$
    private static final String KEY_TOP_N = "PerformanceWaterfallView-top-n"; //$NON-NLS-1$
    private static final String KEY_SHOW_VALUE_LABELS = "PerformanceWaterfallView-show-value-labels"; //$NON-NLS-1$

    private enum Mode
    {
        CALCULATION(Messages.LabelPerformanceWaterfallCalculation),
        INSTRUMENTS(Messages.LabelPerformanceWaterfallInstruments);

        private final String label;

        Mode(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    private enum RangeMode
    {
        ABSOLUTE(Messages.LabelPerformanceWaterfallAbsolute),
        RELATIVE(Messages.LabelPerformanceWaterfallRelative);

        private final String label;

        RangeMode(String label)
        {
            this.label = label;
        }
    }

    @Inject
    private ExchangeRateProviderFactory exchangeRateProviderFactory;

    @Inject
    private SelectionService selectionService;

    private WaterfallChart chart;
    private ClientFilterDropDown clientFilter;

    private Mode mode = Mode.CALCULATION;
    private RangeMode rangeMode = RangeMode.ABSOLUTE;
    private boolean useFifo = true;
    private boolean preTax;
    private int topN = 10;
    private boolean showValueLabels;

    @PostConstruct
    public void setup()
    {
        String modeKey = getPreferenceStore().getString(KEY_MODE);
        if (modeKey != null && !modeKey.isEmpty())
        {
            try
            {
                mode = Mode.valueOf(modeKey);
            }
            catch (IllegalArgumentException ignore)
            {
                // retain the default when upgrading from an unknown version
            }
        }

        String methodKey = getPreferenceStore().getString(KEY_CAPITAL_GAIN_METHOD);
        if (methodKey != null && !methodKey.isEmpty())
            useFifo = CostMethod.FIFO.name().equals(methodKey);

        String rangeModeKey = getPreferenceStore().getString(KEY_RANGE_MODE);
        if (rangeModeKey != null && !rangeModeKey.isEmpty())
        {
            try
            {
                rangeMode = RangeMode.valueOf(rangeModeKey);
            }
            catch (IllegalArgumentException ignore)
            {
                // retain the default when upgrading from an unknown version
            }
        }

        preTax = getPreferenceStore().getBoolean(KEY_PRE_TAX);
        int configuredTopN = getPreferenceStore().getInt(KEY_TOP_N);
        if (configuredTopN > 0)
            topN = configuredTopN;
        showValueLabels = getPreferenceStore().getBoolean(KEY_SHOW_VALUE_LABELS);
    }

    @Override
    protected String getDefaultTitle()
    {
        return Messages.LabelPerformanceWaterfall;
    }

    @Override
    protected void addButtons(ToolBarManager toolBar)
    {
        super.addButtons(toolBar);

        clientFilter = new ClientFilterDropDown(getClient(), getPreferenceStore(), getClass().getSimpleName(),
                        filter -> reportingPeriodUpdated());
        toolBar.add(clientFilter);

        toolBar.add(new ExportDropDown());
        toolBar.add(new DropDown(Messages.MenuConfigureView, Images.CONFIG, SWT.NONE, this::configureMenuAboutToShow));
    }

    @Override
    protected Composite createBody(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setBackground(Colors.theme().defaultBackground());

        chart = new WaterfallChart(composite);
        chart.setShowValueLabels(showValueLabels);
        chart.getTitle().setVisible(false);
        chart.getTitle().setText(getTitle());
        chart.addSelectionListener(bar -> {
            if (bar.getSource() instanceof PerformanceBreakdown.Entry entry && entry.getSecurity() != null)
            {
                setInformationPaneInput(entry.getSecurity());
                selectionService.setSelection(new SecuritySelection(getClient(), entry.getSecurity()));
            }
        });
        GridLayoutFactory.fillDefaults().margins(0, 0).spacing(0, 0).applyTo(composite);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(chart);

        reportingPeriodUpdated();
        return composite;
    }

    @Override
    protected void addPanePages(List<InformationPanePage> pages)
    {
        super.addPanePages(pages);
        pages.add(make(SecurityPriceChartPane.class));
        pages.add(make(HistoricalPricesPane.class));
        pages.add(make(TransactionsPane.class));
        pages.add(make(TradesPane.class));
        pages.add(make(SecurityEventsPane.class));
    }

    @Override
    public void reportingPeriodUpdated()
    {
        if (chart == null || chart.isDisposed())
            return;

        Interval interval = getReportingPeriod().toInterval(LocalDate.now());
        Client filteredClient = clientFilter.getSelectedFilter().filter(getClient());
        if (preTax)
            filteredClient = new WithoutTaxesFilter().filter(filteredClient);

        CurrencyConverter converter = new CurrencyConverterImpl(exchangeRateProviderFactory,
                        getClient().getBaseCurrency());
        ClientPerformanceSnapshot snapshot = new ClientPerformanceSnapshot(filteredClient, converter, interval, useFifo);

        PerformanceBreakdown breakdown = mode == Mode.CALCULATION
                        ? PerformanceBreakdown.createCalculation(snapshot)
                        : PerformanceBreakdown.createContributions(snapshot);

        if (!breakdown.isReconciled())
        {
            chart.setDataset(null);
            chart.setBarColorProvider(null);
            chart.setBarImageProvider(null);
            chart.getTitle().setText(Messages.MsgPerformanceWaterfallNotReconciled);
            chart.getTitle().setVisible(true);
        }
        else
        {
            chart.getTitle().setVisible(false);
            chart.setIncludeZeroInRange(mode != Mode.CALCULATION || rangeMode == RangeMode.ABSOLUTE);
            chart.setDataset(mode == Mode.INSTRUMENTS ? new WaterfallDataset(breakdown, topN)
                            : new WaterfallDataset(breakdown));
            chart.setBarColorProvider(null);
            chart.setBarImageProvider(mode == Mode.INSTRUMENTS ? this::getInstrumentLogo : null);
        }

        chart.adjustRange();
        chart.redraw();
        updateTitle(getDefaultTitle());
    }

    @Override
    public void notifyModelUpdated()
    {
        reportingPeriodUpdated();
    }

    @Override
    public void setFocus()
    {
        if (chart != null && !chart.isDisposed())
        {
            chart.adjustRange();
            chart.setFocus();
        }
    }

    private void configureMenuAboutToShow(IMenuManager manager)
    {
        manager.add(new LabelOnly(Messages.LabelPerformanceWaterfallMode));

        for (Mode candidate : Mode.values())
        {
            Action action = new SimpleAction(candidate.label, a -> {
                mode = candidate;
                getPreferenceStore().setValue(KEY_MODE, mode.name());
                reportingPeriodUpdated();
            });
            action.setChecked(mode == candidate);
            manager.add(action);
        }

        manager.add(new Separator());

        if (mode == Mode.CALCULATION)
        {
            manager.add(new LabelOnly(Messages.LabelPerformanceWaterfallRange));
            for (RangeMode candidate : RangeMode.values())
            {
                Action action = new SimpleAction(candidate.label, a -> {
                    rangeMode = candidate;
                    getPreferenceStore().setValue(KEY_RANGE_MODE, rangeMode.name());
                    reportingPeriodUpdated();
                });
                action.setChecked(rangeMode == candidate);
                manager.add(action);
            }
            manager.add(new Separator());
        }

        SimpleAction preTaxAction = new SimpleAction(Messages.LabelPreTax, a -> {
            preTax = !preTax;
            getPreferenceStore().setValue(KEY_PRE_TAX, preTax);
            reportingPeriodUpdated();
        });
        preTaxAction.setChecked(preTax);
        manager.add(preTaxAction);

        manager.add(new Separator());

        SimpleAction showValuesAction = new SimpleAction(Messages.LabelPerformanceWaterfallShowValues, a -> {
            showValueLabels = !showValueLabels;
            getPreferenceStore().setValue(KEY_SHOW_VALUE_LABELS, showValueLabels);
            chart.setShowValueLabels(showValueLabels);
        });
        showValuesAction.setChecked(showValueLabels);
        manager.add(showValuesAction);

        manager.add(new Separator());
        manager.add(new LabelOnly(Messages.LabelCapitalGainsMethod));

        SimpleAction fifoAction = new SimpleAction(CostMethod.FIFO.getLabel(), a -> {
            useFifo = true;
            getPreferenceStore().setValue(KEY_CAPITAL_GAIN_METHOD, CostMethod.FIFO.name());
            reportingPeriodUpdated();
        });
        fifoAction.setChecked(useFifo);
        manager.add(fifoAction);

        SimpleAction movingAverageAction = new SimpleAction(CostMethod.MOVING_AVERAGE.getLabel(), a -> {
            useFifo = false;
            getPreferenceStore().setValue(KEY_CAPITAL_GAIN_METHOD, CostMethod.MOVING_AVERAGE.name());
            reportingPeriodUpdated();
        });
        movingAverageAction.setChecked(!useFifo);
        manager.add(movingAverageAction);

        if (mode == Mode.INSTRUMENTS)
        {
            manager.add(new Separator());
            manager.add(new LabelOnly(Messages.LabelPerformanceWaterfallTopN));
            for (int candidate : new int[] { 5, 10, 20, 50 })
            {
                SimpleAction action = new SimpleAction(String.valueOf(candidate), a -> {
                    topN = candidate;
                    getPreferenceStore().setValue(KEY_TOP_N, topN);
                    reportingPeriodUpdated();
                });
                action.setChecked(topN == candidate);
                manager.add(action);
            }
        }
    }

    private Image getInstrumentLogo(WaterfallDataset.Bar bar)
    {
        if (bar.getSource() instanceof PerformanceBreakdown.Entry entry && entry.getSecurity() != null
                        && LogoManager.instance().hasCustomLogo(entry.getSecurity(), getClient().getSettings()))
            return LogoManager.instance().getCustomLogoImage(entry.getSecurity(), getClient().getSettings(),
                            WATERFALL_LOGO_SIZE, WATERFALL_LOGO_SIZE);
        return null;
    }

    private final class ExportDropDown extends DropDown implements IMenuListener
    {
        private ExportDropDown()
        {
            super(Messages.MenuExportData, Images.EXPORT, SWT.NONE);
            setMenuListener(this);
        }

        @Override
        public void menuAboutToShow(IMenuManager manager)
        {
            manager.add(new Action(Messages.MenuExportChartData)
            {
                @Override
                public void run()
                {
                    new WaterfallChartCSVExporter(chart).export(getTitle() + ".csv"); //$NON-NLS-1$
                }
            });
            manager.add(new Separator());
            chart.exportMenuAboutToShow(manager, getTitle());
        }
    }
}
