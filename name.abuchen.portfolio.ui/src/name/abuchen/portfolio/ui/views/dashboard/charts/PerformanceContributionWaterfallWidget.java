package name.abuchen.portfolio.ui.views.dashboard.charts;

import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.ui.util.chart.WaterfallDataset;
import name.abuchen.portfolio.ui.views.dashboard.DashboardData;

public class PerformanceContributionWaterfallWidget extends AbstractPerformanceWaterfallWidget
{
    public PerformanceContributionWaterfallWidget(Widget widget, DashboardData dashboardData)
    {
        super(widget, dashboardData, true);
    }

    @Override
    protected PerformanceBreakdown createBreakdown(ClientPerformanceSnapshot snapshot)
    {
        return PerformanceBreakdown.createCalculation(snapshot);
    }

    @Override
    protected WaterfallDataset createDataset(PerformanceBreakdown breakdown)
    {
        return new WaterfallDataset(breakdown);
    }

    @Override
    protected boolean includeZeroInRange()
    {
        return get(RangeConfig.class).getValue() == Range.ABSOLUTE;
    }
}
