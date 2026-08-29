package name.abuchen.portfolio.ui.util.chart;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.Messages;

/** Tooltip for a single waterfall bar. */
public class WaterfallChartToolTip extends AbstractChartToolTip
{
    private final WaterfallChart waterfallChart;

    public WaterfallChartToolTip(WaterfallChart chart)
    {
        super(chart);
        this.waterfallChart = chart;
    }

    @Override
    protected Object getFocusObjectAt(Event event)
    {
        return waterfallChart.getBarAt(event.x, event.y);
    }

    @Override
    protected void createComposite(Composite parent)
    {
        var bar = (WaterfallDataset.Bar) getFocusedObject();
        var data = new Composite(parent, SWT.NONE);
        GridLayoutFactory.swtDefaults().numColumns(2).applyTo(data);

        add(data, Messages.ColumnLabel, bar.getLabel());
        add(data, Messages.LabelPerformanceWaterfallType, label(bar.getKind()));
        add(data, Messages.LabelPerformanceWaterfallStart, format(bar.getStart()));
        add(data, Messages.LabelPerformanceWaterfallChange, format(bar.getChange()));
        add(data, Messages.LabelPerformanceWaterfallEnd, format(bar.getEnd()));
    }

    private void add(Composite parent, String name, String value)
    {
        var left = new Label(parent, SWT.NONE);
        left.setText(name);

        var right = new Label(parent, SWT.RIGHT);
        right.setText(value);
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.END, SWT.FILL).applyTo(right);
    }

    private String format(long amount)
    {
        return Values.Money.format(Money.of(waterfallChart.getDataset().getCurrencyCode(), amount));
    }

    private String label(WaterfallDataset.EntryKind kind)
    {
        return switch (kind)
        {
            case START -> Messages.LabelPerformanceWaterfallStart;
            case CHANGE -> Messages.LabelPerformanceWaterfallChange;
            case SUBTOTAL, TOTAL -> Messages.ColumnValue;
        };
    }
}
