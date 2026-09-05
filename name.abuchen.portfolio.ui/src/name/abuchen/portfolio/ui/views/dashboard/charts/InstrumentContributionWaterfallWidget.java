package name.abuchen.portfolio.ui.views.dashboard.charts;

import java.text.MessageFormat;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.graphics.Image;

import name.abuchen.portfolio.model.Dashboard;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.LogoManager;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.chart.WaterfallChart;
import name.abuchen.portfolio.ui.util.chart.WaterfallDataset;
import name.abuchen.portfolio.ui.views.dashboard.DashboardData;
import name.abuchen.portfolio.ui.views.dashboard.WidgetConfig;
import name.abuchen.portfolio.ui.views.dashboard.WidgetDelegate;

public class InstrumentContributionWaterfallWidget extends AbstractPerformanceWaterfallWidget
{
    private static final int WATERFALL_LOGO_SIZE = 24;

    public static final class InstrumentCountConfig implements WidgetConfig
    {
        private static final int DEFAULT_COUNT = 10;
        private static final int[] COUNTS = { 5, 10, 20, 50 };

        private final WidgetDelegate<?> delegate;
        private int count = DEFAULT_COUNT;

        public InstrumentCountConfig(WidgetDelegate<?> delegate)
        {
            this.delegate = delegate;
            String code = delegate.getWidget().getConfiguration().get(Dashboard.Config.WATERFALL_TOP_N.name());
            if (code != null)
            {
                try
                {
                    int stored = Integer.parseInt(code);
                    for (int candidate : COUNTS)
                        if (candidate == stored)
                            count = stored;
                }
                catch (NumberFormatException ignore)
                {
                    // use default
                }
            }
        }

        public int getCount()
        {
            return count;
        }

        @Override
        public void menuAboutToShow(IMenuManager manager)
        {
            var subMenu = new MenuManager(Messages.LabelPerformanceWaterfallTopN);
            for (int candidate : COUNTS)
            {
                var action = new SimpleAction(String.valueOf(candidate), a -> {
                    count = candidate;
                    delegate.getWidget().getConfiguration().put(Dashboard.Config.WATERFALL_TOP_N.name(),
                                    String.valueOf(candidate));
                    delegate.update();
                    delegate.getClient().touch();
                });
                action.setChecked(count == candidate);
                subMenu.add(action);
            }
            manager.add(subMenu);
        }

        @Override
        public String getLabel()
        {
            return MessageFormat.format(Messages.LabelColonSeparated, Messages.LabelPerformanceWaterfallTopN, count);
        }
    }

    public InstrumentContributionWaterfallWidget(Widget widget, DashboardData dashboardData)
    {
        super(widget, dashboardData, false);
        addConfig(new InstrumentCountConfig(this));
    }

    @Override
    protected PerformanceBreakdown createBreakdown(ClientPerformanceSnapshot snapshot)
    {
        return PerformanceBreakdown.createContributions(snapshot);
    }

    @Override
    protected WaterfallDataset createDataset(PerformanceBreakdown breakdown)
    {
        return new WaterfallDataset(breakdown, get(InstrumentCountConfig.class).getCount());
    }

    @Override
    protected void configureChart(WaterfallChart chart)
    {
        chart.setBarImageProvider(this::getInstrumentLogo);
    }

    private Image getInstrumentLogo(WaterfallDataset.Bar bar)
    {
        if (bar.getSource() instanceof PerformanceBreakdown.Entry entry && entry.getSecurity() != null
                        && LogoManager.instance().hasCustomLogo(entry.getSecurity(), getClient().getSettings()))
            return LogoManager.instance().getCustomLogoImage(entry.getSecurity(), getClient().getSettings(),
                            WATERFALL_LOGO_SIZE, WATERFALL_LOGO_SIZE);
        return null;
    }
}
