package name.abuchen.portfolio.ui.views.dashboard.charts;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Dashboard;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.ui.views.dashboard.DashboardData;
import name.abuchen.portfolio.ui.views.dashboard.WidgetFactory;

@SuppressWarnings("nls")
public class PerformanceWaterfallWidgetTest
{
    @Test
    public void testWidgetFactoryRegistersBothWaterfallsWithoutInitializingImages()
    {
        var data = new DashboardData(new Client());

        var performanceModel = WidgetFactory.PERFORMANCE_CONTRIBUTION_WATERFALL.constructWidget();
        assertThat(performanceModel.getType(), is(WidgetFactory.PERFORMANCE_CONTRIBUTION_WATERFALL.name()));
        assertThat(WidgetFactory.PERFORMANCE_CONTRIBUTION_WATERFALL.constructDelegate(performanceModel, data),
                        instanceOf(PerformanceContributionWaterfallWidget.class));

        var instrumentModel = WidgetFactory.INSTRUMENT_CONTRIBUTION_WATERFALL.constructWidget();
        assertThat(instrumentModel.getType(), is(WidgetFactory.INSTRUMENT_CONTRIBUTION_WATERFALL.name()));
        assertThat(WidgetFactory.INSTRUMENT_CONTRIBUTION_WATERFALL.constructDelegate(instrumentModel, data),
                        instanceOf(InstrumentContributionWaterfallWidget.class));
    }

    @Test
    public void testPerformanceContributionDefaultsToAbsoluteRange()
    {
        var widget = new PerformanceContributionWaterfallWidget(new Widget(), new DashboardData(new Client()));

        assertThat(widget.get(AbstractPerformanceWaterfallWidget.RangeConfig.class).getValue(),
                        is(AbstractPerformanceWaterfallWidget.Range.ABSOLUTE));
        assertThat(widget.includeZeroInRange(), is(true));
        assertThat(widget.get(AbstractPerformanceWaterfallWidget.PreTaxConfig.class).getValue(), is(false));
        assertThat(widget.get(AbstractPerformanceWaterfallWidget.ShowValuesConfig.class).getValue(), is(false));
    }

    @Test
    public void testPerformanceContributionReadsPersistedConfiguration()
    {
        var model = new Widget();
        model.getConfiguration().put(Dashboard.Config.WATERFALL_RANGE.name(),
                        AbstractPerformanceWaterfallWidget.Range.RELATIVE.name());
        model.getConfiguration().put(Dashboard.Config.FLAG_PRE_TAX.name(), Boolean.TRUE.toString());
        model.getConfiguration().put(Dashboard.Config.FLAG_SHOW_VALUES.name(), Boolean.TRUE.toString());

        var widget = new PerformanceContributionWaterfallWidget(model, new DashboardData(new Client()));

        assertThat(widget.get(AbstractPerformanceWaterfallWidget.RangeConfig.class).getValue(),
                        is(AbstractPerformanceWaterfallWidget.Range.RELATIVE));
        assertThat(widget.includeZeroInRange(), is(false));
        assertThat(widget.get(AbstractPerformanceWaterfallWidget.PreTaxConfig.class).getValue(), is(true));
        assertThat(widget.get(AbstractPerformanceWaterfallWidget.ShowValuesConfig.class).getValue(), is(true));
    }

    @Test
    public void testInvalidRangeFallsBackToAbsolute()
    {
        var model = new Widget();
        model.getConfiguration().put(Dashboard.Config.WATERFALL_RANGE.name(), "UNKNOWN");

        var widget = new PerformanceContributionWaterfallWidget(model, new DashboardData(new Client()));

        assertThat(widget.get(AbstractPerformanceWaterfallWidget.RangeConfig.class).getValue(),
                        is(AbstractPerformanceWaterfallWidget.Range.ABSOLUTE));
        assertThat(widget.includeZeroInRange(), is(true));
    }

    @Test
    public void testInstrumentContributionDefaultsToTenInstruments()
    {
        var widget = new InstrumentContributionWaterfallWidget(new Widget(), new DashboardData(new Client()));

        assertThat(widget.get(InstrumentContributionWaterfallWidget.InstrumentCountConfig.class).getCount(), is(10));
        assertThat(widget.optionallyGet(AbstractPerformanceWaterfallWidget.RangeConfig.class).isEmpty(), is(true));
        assertThat(widget.includeZeroInRange(), is(true));
    }

    @Test
    public void testInstrumentContributionReadsSupportedCounts()
    {
        for (int count : new int[] { 5, 10, 20, 50 })
        {
            var model = new Widget();
            model.getConfiguration().put(Dashboard.Config.WATERFALL_TOP_N.name(), String.valueOf(count));

            var widget = new InstrumentContributionWaterfallWidget(model, new DashboardData(new Client()));

            assertThat(widget.get(InstrumentContributionWaterfallWidget.InstrumentCountConfig.class).getCount(),
                            is(count));
        }
    }

    @Test
    public void testInvalidInstrumentCountsFallBackToTen()
    {
        for (String count : new String[] { "", "6", "many" })
        {
            var model = new Widget();
            model.getConfiguration().put(Dashboard.Config.WATERFALL_TOP_N.name(), count);

            var widget = new InstrumentContributionWaterfallWidget(model, new DashboardData(new Client()));

            assertThat(widget.get(InstrumentContributionWaterfallWidget.InstrumentCountConfig.class).getCount(),
                            is(10));
        }
    }
}
