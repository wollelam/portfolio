package name.abuchen.portfolio.ui.util.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swtchart.IAxis;
import org.eclipse.swtchart.IAxis.Position;
import org.eclipse.swtchart.ICustomPaintListener;
import org.eclipse.swtchart.LineStyle;
import org.eclipse.swtchart.Range;

import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.format.AmountNumberFormat;

/**
 * A floating-bar waterfall chart rendered with SWTChart's custom paint API.
 */
public class WaterfallChart extends PlainChart // NOSONAR
{
    private static final int MINIMUM_BAR_WIDTH = 5;
    private static final int MAXIMUM_BAR_WIDTH = 60;
    private static final int CATEGORY_LABEL_LINE_LENGTH = 16;

    private final ChartContextMenu contextMenu;
    private final WaterfallChartToolTip toolTip;
    private final List<Consumer<WaterfallDataset.Bar>> selectionListeners = new ArrayList<>();

    private WaterfallDataset dataset = new WaterfallDataset("XXX", Collections.emptyList()); //$NON-NLS-1$
    private List<PaintedBar> paintedBars = Collections.emptyList();

    private Color positiveColor;
    private Color negativeColor;
    private Color totalColor;
    private Color connectorColor;
    private Function<WaterfallDataset.Bar, Color> barColorProvider;
    private Function<WaterfallDataset.Bar, Image> barImageProvider;
    private boolean showValueLabels;

    public WaterfallChart(Composite parent)
    {
        super(parent, SWT.NONE);

        setData(UIConstants.CSS.CLASS_NAME, "chart"); //$NON-NLS-1$
        getLegend().setVisible(false);
        getTitle().setVisible(false);

        IAxis xAxis = getAxisSet().getXAxis(0);
        xAxis.getTitle().setVisible(false);
        xAxis.getTick().setVisible(true);
        xAxis.getGrid().setStyle(LineStyle.NONE);
        xAxis.enableCategory(true);

        IAxis yAxis = getAxisSet().getYAxis(0);
        yAxis.getTitle().setVisible(false);
        yAxis.setPosition(Position.Secondary);
        yAxis.getTick().setFormat(new AmountNumberFormat());

        getPlotArea().addCustomPaintListener(new ICustomPaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                paintWaterfall(e);
            }

            @Override
            public boolean drawBehindSeries()
            {
                return false;
            }
        });

        toolTip = new WaterfallChartToolTip(this);
        contextMenu = new ChartContextMenu(this);

        ZoomMouseWheelListener.attachTo(this);
        MovePlotKeyListener.attachTo(this);
        ZoomInAreaListener.attachTo(this);
        getPlotArea().getControl().addTraverseListener(event -> event.doit = true);
        getPlotArea().getControl().addMouseListener(MouseListener.mouseUpAdapter(event -> {
            if (event.button != 1)
                return;

            var selected = getBarAt(event.x, event.y);
            if (selected != null)
                selectionListeners.forEach(listener -> listener.accept(selected));
        }));
    }

    public WaterfallDataset getDataset()
    {
        return dataset;
    }

    public void setDataset(WaterfallDataset dataset)
    {
        toolTip.reset();
        this.dataset = dataset == null ? new WaterfallDataset("XXX", Collections.emptyList()) : dataset; //$NON-NLS-1$

        var categories = this.dataset.getBars().stream().map(WaterfallDataset.Bar::getLabel)
                        .map(WaterfallChart::formatCategoryLabel).toArray(String[]::new);
        IAxis xAxis = getAxisSet().getXAxis(0);
        xAxis.setCategorySeries(categories);
        xAxis.enableCategory(true);

        adjustRange();
        redraw();
    }

    static String formatCategoryLabel(String label)
    {
        if (label.length() <= CATEGORY_LABEL_LINE_LENGTH)
            return label;

        int wrapAt = label.lastIndexOf(' ', CATEGORY_LABEL_LINE_LENGTH);
        if (wrapAt <= 0)
            return limitCategoryLabel(label);

        String firstLine = label.substring(0, wrapAt);
        String secondLine = limitCategoryLabel(label.substring(wrapAt + 1));
        return firstLine + '\n' + secondLine;
    }

    private static String limitCategoryLabel(String label)
    {
        if (label.length() <= CATEGORY_LABEL_LINE_LENGTH)
            return label;
        return label.substring(0, CATEGORY_LABEL_LINE_LENGTH).stripTrailing() + "…"; //$NON-NLS-1$
    }

    private void adjustCategoryRange(IAxis xAxis, int categoryCount)
    {
        // No SWTChart series is registered for the custom-painted bars, so the
        // axis set cannot infer the category range through adjustRange(). A
        // category axis already reserves half a slot on either side of its
        // range, therefore 0..n-1 displays all bars without clipping them.
        if (categoryCount > 0)
            xAxis.setRange(new Range(0, categoryCount - 1));
        else
            xAxis.setRange(new Range(0, 1));
    }

    public WaterfallChartToolTip getToolTip()
    {
        return toolTip;
    }

    public void addSelectionListener(Consumer<WaterfallDataset.Bar> listener)
    {
        selectionListeners.add(listener);
    }

    public boolean isShowValueLabels()
    {
        return showValueLabels;
    }

    public void setShowValueLabels(boolean showValueLabels)
    {
        this.showValueLabels = showValueLabels;
        adjustRange();
        redraw();
    }

    public Color getPositiveColor()
    {
        return positiveColor != null ? positiveColor : Colors.theme().greenBackground();
    }

    public void setPositiveColor(Color positiveColor)
    {
        this.positiveColor = positiveColor;
    }

    public Color getNegativeColor()
    {
        return negativeColor != null ? negativeColor : Colors.theme().redBackground();
    }

    public void setNegativeColor(Color negativeColor)
    {
        this.negativeColor = negativeColor;
    }

    public Color getTotalColor()
    {
        return totalColor != null ? totalColor : Colors.theme().grayForeground();
    }

    public void setTotalColor(Color totalColor)
    {
        this.totalColor = totalColor;
    }

    public Color getConnectorColor()
    {
        return connectorColor != null ? connectorColor : Colors.theme().grayForeground();
    }

    public void setConnectorColor(Color connectorColor)
    {
        this.connectorColor = connectorColor;
    }

    /**
     * Sets an optional colour resolver for individual bars. A {@code null}
     * return value falls back to the semantic increase, decrease, and total
     * colours.
     */
    public void setBarColorProvider(Function<WaterfallDataset.Bar, Color> barColorProvider)
    {
        this.barColorProvider = barColorProvider;
        redraw();
    }

    /**
     * Sets an optional image resolver for individual bars. Images are drawn
     * only if the rendered bar has sufficient space.
     */
    public void setBarImageProvider(Function<WaterfallDataset.Bar, Image> barImageProvider)
    {
        this.barImageProvider = barImageProvider;
        adjustRange();
        redraw();
    }

    public void adjustRange()
    {
        adjustCategoryRange(getAxisSet().getXAxis(0), dataset.getBars().size());

        IAxis yAxis = getAxisSet().getYAxis(0);
        if (dataset.isEmpty())
        {
            yAxis.setRange(new Range(-1, 1));
            return;
        }

        double lower = toChartValue(dataset.getMinimum());
        double upper = toChartValue(dataset.getMaximum());
        double span = upper - lower;
        double marginFactor = showValueLabels && barImageProvider != null ? 0.2
                        : showValueLabels || barImageProvider != null ? 0.14 : 0.08;
        double margin = span == 0 ? Math.max(Math.abs(upper) * marginFactor, 1) : span * marginFactor;
        yAxis.setRange(new Range(lower - margin, upper + margin));
    }

    /**
     * Converts a monetary value in its stored minor units to the major-unit
     * coordinate used by SWTChart.
     */
    static double toChartValue(long amount)
    {
        return amount / Values.Amount.divider();
    }

    public void exportMenuAboutToShow(IMenuManager manager, String label)
    {
        contextMenu.exportMenuAboutToShow(manager, label);
    }

    @Override
    public void save(String filename, int format)
    {
        ChartUtil.save(this, filename, format);
    }

    WaterfallDataset.Bar getBarAt(int x, int y)
    {
        for (var painted : paintedBars)
        {
            if (painted.bounds.contains(x, y))
                return painted.bar;
        }
        return null;
    }

    List<Rectangle> getPaintedBarBounds()
    {
        return paintedBars.stream().map(painted -> new Rectangle(painted.bounds.x, painted.bounds.y,
                        painted.bounds.width, painted.bounds.height)).toList();
    }

    private void paintWaterfall(PaintEvent event)
    {
        if (dataset.isEmpty())
        {
            paintedBars = Collections.emptyList();
            return;
        }

        IAxis xAxis = getAxisSet().getXAxis(0);
        IAxis yAxis = getAxisSet().getYAxis(0);
        var bars = dataset.getBars();
        var painted = new ArrayList<PaintedBar>(bars.size());
        int plotWidth = getPlotArea().getControl().getSize().x;

        for (int index = 0; index < bars.size(); index++)
        {
            var bar = bars.get(index);
            int x = xAxis.getPixelCoordinate(index);
            int yStart = yAxis.getPixelCoordinate(toChartValue(bar.getStart()));
            int yEnd = yAxis.getPixelCoordinate(toChartValue(bar.getEnd()));
            int width = getBarWidth(xAxis, index, bars.size(), plotWidth);
            int height = Math.max(1, Math.abs(yEnd - yStart));
            int top = Math.min(yStart, yEnd);
            painted.add(new PaintedBar(bar, new Rectangle(x - width / 2, top, width, height), yEnd));
        }

        paintedBars = Collections.unmodifiableList(painted);

        var oldForeground = event.gc.getForeground();
        var oldBackground = event.gc.getBackground();
        var oldLineStyle = event.gc.getLineStyle();

        try
        {
            event.gc.setForeground(getConnectorColor());
            event.gc.setLineStyle(SWT.LINE_DASH);
            int zero = yAxis.getPixelCoordinate(0);
            event.gc.drawLine(0, zero, plotWidth, zero);

            event.gc.setLineStyle(SWT.LINE_SOLID);
            for (int index = 0; index < painted.size() - 1; index++)
            {
                var current = painted.get(index);
                var next = painted.get(index + 1);
                event.gc.drawLine(current.bounds.x + current.bounds.width, current.endY, next.bounds.x, current.endY);
            }

            for (var paintedBar : painted)
            {
                event.gc.setBackground(getColor(paintedBar.bar));
                event.gc.fillRectangle(paintedBar.bounds);
                event.gc.setForeground(getConnectorColor());
                event.gc.drawRectangle(paintedBar.bounds.x, paintedBar.bounds.y, paintedBar.bounds.width - 1,
                                paintedBar.bounds.height - 1);

                paintBarImage(event, paintedBar);

                if (showValueLabels)
                    paintValueLabel(event, paintedBar);
            }
        }
        finally
        {
            event.gc.setForeground(oldForeground);
            event.gc.setBackground(oldBackground);
            event.gc.setLineStyle(oldLineStyle);
        }
    }

    private int getBarWidth(IAxis xAxis, int index, int count, int chartWidth)
    {
        if (count <= 1)
            return Math.min(MAXIMUM_BAR_WIDTH, Math.max(MINIMUM_BAR_WIDTH, chartWidth / 3));

        int current = xAxis.getPixelCoordinate(index);
        int neighbour = xAxis.getPixelCoordinate(index == count - 1 ? index - 1 : index + 1);
        return Math.min(MAXIMUM_BAR_WIDTH, Math.max(MINIMUM_BAR_WIDTH, Math.abs(neighbour - current) * 2 / 3));
    }

    private Color getColor(WaterfallDataset.Bar bar)
    {
        if (barColorProvider != null)
        {
            Color color = barColorProvider.apply(bar);
            if (color != null)
                return color;
        }

        if (bar.isTotal())
            return getTotalColor();
        return bar.getChange() < 0 ? getNegativeColor() : getPositiveColor();
    }

    private void paintValueLabel(PaintEvent event, PaintedBar paintedBar)
    {
        long value = paintedBar.bar.isTotal() ? paintedBar.bar.getEnd() : paintedBar.bar.getChange();
        String text = Values.Amount.format(value);
        var extent = event.gc.textExtent(text);
        int x = paintedBar.bounds.x + (paintedBar.bounds.width - extent.x) / 2;
        int y = paintedBar.bar.getChange() < 0 ? paintedBar.bounds.y + paintedBar.bounds.height + 2
                        : paintedBar.bounds.y - extent.y - 2 - getBarImageSize(paintedBar) - 3;
        event.gc.setForeground(Colors.theme().defaultForeground());
        event.gc.drawText(text, x, y, true);
    }

    private void paintBarImage(PaintEvent event, PaintedBar paintedBar)
    {
        if (barImageProvider == null)
            return;

        Image image = getBarImage(paintedBar);
        if (image == null || image.isDisposed())
            return;

        int size = getBarImageSize(paintedBar);
        if (size < 10)
            return;

        var source = image.getBounds();
        int x = paintedBar.bounds.x + (paintedBar.bounds.width - size) / 2;
        int y = paintedBar.bounds.y - size - 3;
        event.gc.drawImage(image, 0, 0, source.width, source.height, x, y, size, size);
    }

    private Image getBarImage(PaintedBar paintedBar)
    {
        return barImageProvider == null ? null : barImageProvider.apply(paintedBar.bar);
    }

    private int getBarImageSize(PaintedBar paintedBar)
    {
        if (getBarImage(paintedBar) == null)
            return 0;
        return Math.min(18, paintedBar.bounds.width - 4);
    }

    private static final class PaintedBar
    {
        private final WaterfallDataset.Bar bar;
        private final Rectangle bounds;
        private final int endY;

        private PaintedBar(WaterfallDataset.Bar bar, Rectangle bounds, int endY)
        {
            this.bar = bar;
            this.bounds = bounds;
            this.endY = endY;
        }
    }
}
