package name.abuchen.portfolio.ui.views.dashboard.charts;

import java.time.LocalDate;
import java.util.function.Supplier;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.model.Dashboard;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.snapshot.filter.WithoutTaxesFilter;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.chart.WaterfallChart;
import name.abuchen.portfolio.ui.util.chart.WaterfallDataset;
import name.abuchen.portfolio.ui.views.dashboard.ChartHeightConfig;
import name.abuchen.portfolio.ui.views.dashboard.ClientFilterConfig;
import name.abuchen.portfolio.ui.views.dashboard.CostMethodConfig;
import name.abuchen.portfolio.ui.views.dashboard.DashboardData;
import name.abuchen.portfolio.ui.views.dashboard.DashboardResources;
import name.abuchen.portfolio.ui.views.dashboard.EnumBasedConfig;
import name.abuchen.portfolio.ui.views.dashboard.ReportingPeriodConfig;
import name.abuchen.portfolio.ui.views.dashboard.WidgetConfig;
import name.abuchen.portfolio.ui.views.dashboard.WidgetDelegate;
import name.abuchen.portfolio.util.TextUtil;

abstract class AbstractPerformanceWaterfallWidget extends WidgetDelegate<PerformanceBreakdown>
{
    enum Range
    {
        ABSOLUTE(Messages.LabelPerformanceWaterfallAbsolute), RELATIVE(Messages.LabelPerformanceWaterfallRelative);

        private final String label;

        Range(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    static final class RangeConfig extends EnumBasedConfig<Range>
    {
        RangeConfig(WidgetDelegate<?> delegate)
        {
            super(delegate, Messages.LabelPerformanceWaterfallRange, Range.class, Dashboard.Config.WATERFALL_RANGE,
                            Policy.EXACTLY_ONE);
        }
    }

    private abstract static class BooleanConfig implements WidgetConfig
    {
        private final WidgetDelegate<?> delegate;
        private final Dashboard.Config key;
        private final String label;
        private boolean value;

        BooleanConfig(WidgetDelegate<?> delegate, Dashboard.Config key, String label, boolean defaultValue)
        {
            this.delegate = delegate;
            this.key = key;
            this.label = label;
            String code = delegate.getWidget().getConfiguration().get(key.name());
            this.value = code != null ? Boolean.parseBoolean(code) : defaultValue;
        }

        @Override
        public void menuAboutToShow(IMenuManager manager)
        {
            var action = new SimpleAction(label, a -> {
                value = !value;
                delegate.getWidget().getConfiguration().put(key.name(), String.valueOf(value));
                delegate.update();
                delegate.getClient().touch();
            });
            action.setChecked(value);
            manager.add(action);
        }

        @Override
        public String getLabel()
        {
            return label + ": " + (value ? Messages.LabelYes : Messages.LabelNo); //$NON-NLS-1$
        }

        boolean getValue()
        {
            return value;
        }
    }

    static final class PreTaxConfig extends BooleanConfig
    {
        PreTaxConfig(WidgetDelegate<?> delegate)
        {
            super(delegate, Dashboard.Config.FLAG_PRE_TAX, Messages.LabelPreTax, false);
        }
    }

    static final class ShowValuesConfig extends BooleanConfig
    {
        ShowValuesConfig(WidgetDelegate<?> delegate)
        {
            super(delegate, Dashboard.Config.FLAG_SHOW_VALUES, Messages.LabelPerformanceWaterfallShowValues, false);
        }
    }

    private Label title;
    private WaterfallChart chart;

    AbstractPerformanceWaterfallWidget(Widget widget, DashboardData dashboardData, boolean withRange)
    {
        super(widget, dashboardData);
        addConfig(new ReportingPeriodConfig(this));
        addConfig(new ClientFilterConfig(this));
        if (withRange)
            addConfig(new RangeConfig(this));
        addConfig(new PreTaxConfig(this));
        addConfig(new ShowValuesConfig(this));
        addConfig(new CostMethodConfig(this));
        addConfig(new ChartHeightConfig(this));
    }

    @Override
    public Composite createControl(Composite parent, DashboardResources resources)
    {
        var container = new Composite(parent, SWT.NONE);
        container.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(container);
        container.setBackground(parent.getBackground());

        title = new Label(container, SWT.NONE);
        title.setBackground(container.getBackground());
        title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        title.setText(TextUtil.tooltip(getWidget().getLabel()));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(title);

        chart = new WaterfallChart(container);
        chart.setBackground(container.getBackground());
        chart.getTitle().setText(title.getText());
        chart.getTitle().setVisible(false);
        configureChart(chart);
        GridDataFactory.fillDefaults().hint(SWT.DEFAULT, get(ChartHeightConfig.class).getPixel()).grab(true, false)
                        .applyTo(chart);
        getDashboardData().getStylingEngine().style(chart);
        return container;
    }

    protected void configureChart(WaterfallChart chart)
    {
        // subclasses can add mode-specific presentation
    }

    @Override
    public Control getTitleControl()
    {
        return title;
    }

    @Override
    public Supplier<PerformanceBreakdown> getUpdateTask()
    {
        var interval = get(ReportingPeriodConfig.class).getReportingPeriod().toInterval(LocalDate.now());
        var filter = get(ClientFilterConfig.class).getSelectedFilter();
        boolean preTax = get(PreTaxConfig.class).getValue();
        boolean useFifo = get(CostMethodConfig.class).getValue().useFifo();
        return () -> {
            var client = filter.filter(getClient());
            if (preTax)
                client = new WithoutTaxesFilter().filter(client);

            var converter = new CurrencyConverterImpl(getDashboardData().getExchangeRateProviderFactory(),
                            getClient().getBaseCurrency());
            var snapshot = new ClientPerformanceSnapshot(client, converter, interval, useFifo);
            return createBreakdown(snapshot);
        };
    }

    protected abstract PerformanceBreakdown createBreakdown(ClientPerformanceSnapshot snapshot);

    protected abstract WaterfallDataset createDataset(PerformanceBreakdown breakdown);

    @Override
    public void update(PerformanceBreakdown breakdown)
    {
        title.setText(TextUtil.tooltip(getWidget().getLabel()));
        get(ChartHeightConfig.class).updateGridData(chart, title.getParent());
        chart.getTitle().setText(title.getText());
        chart.setShowValueLabels(get(ShowValuesConfig.class).getValue());

        if (breakdown == null || !breakdown.isReconciled())
        {
            chart.setDataset(null);
            chart.getTitle().setText(Messages.MsgPerformanceWaterfallNotReconciled);
            chart.getTitle().setVisible(true);
        }
        else
        {
            chart.getTitle().setVisible(false);
            chart.setDataset(createDataset(breakdown));
        }

        chart.setIncludeZeroInRange(includeZeroInRange());
        chart.adjustRange();
        chart.redraw();
    }

    protected boolean includeZeroInRange()
    {
        return true;
    }
}
