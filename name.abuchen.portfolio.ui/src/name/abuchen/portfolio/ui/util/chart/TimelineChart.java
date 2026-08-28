package name.abuchen.portfolio.ui.util.chart;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swtchart.Chart;
import org.eclipse.swtchart.IAxis;
import org.eclipse.swtchart.IAxis.Position;
import org.eclipse.swtchart.IBarSeries;
import org.eclipse.swtchart.ICustomPaintListener;
import org.eclipse.swtchart.ILineSeries;
import org.eclipse.swtchart.ILineSeries.PlotSymbolType;
import org.eclipse.swtchart.ISeries.SeriesType;
import org.eclipse.swtchart.LineStyle;
import org.eclipse.swtchart.Range;
import org.eclipse.swtchart.internal.PlotArea;

import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.Colors;

public class TimelineChart extends Chart // NOSONAR
{

    private static class MarkerLine
    {
        private LocalDate date;
        private Color color;
        private String label;
        private Double value;

        private MarkerLine(LocalDate date, Color color, String label, Double value)
        {
            this.date = date;
            this.color = color;
            this.label = label;
            this.value = value;
        }

        public long getEpochDay()
        {
            return date.toEpochDay();
        }
    }

    private static class NonTradingDayMarker
    {
        private LocalDate date;
        private Color color;

        private NonTradingDayMarker(LocalDate date, Color color)
        {
            this.date = date;
            this.color = color;
        }

        public long getEpochDay()
        {
            return date.toEpochDay();
        }
    }

    /**
     * A coloured, closed date range painted behind the time grid and chart
     * series. It is intended for annotations that apply to a period rather
     * than a single date, for example unavailable market data.
     */
    private static class BackgroundBand
    {
        private final LocalDate start;
        private final LocalDate end;
        private final Color color;
        private final int alpha;

        private BackgroundBand(LocalDate start, LocalDate end, Color color, int alpha)
        {
            this.start = start;
            this.end = end;
            this.color = color;
            this.alpha = alpha;
        }
    }

    private List<MarkerLine> markerLines = new ArrayList<>();
    private List<NonTradingDayMarker> nonTradingDayMarkers = new ArrayList<>();
    private List<BackgroundBand> backgroundBands = new ArrayList<>();
    private Map<Object, IAxis> addedAxis = new HashMap<>();

    private ChartToolsManager chartTools;
    private TimelineChartToolTip toolTip;
    private ChartContextMenu contextMenu;

    @SuppressWarnings("restriction")
    public TimelineChart(Composite parent)
    {
        super(parent, SWT.NONE, null);

        // we must use the secondary constructor that is not creating the
        // PlotArea because the default constructor adds a mouse move listener
        // that is redrawing the chart on every mouse move. That leads to janky
        // UI when the tooltip is shown.
        new PlotArea(this, SWT.NONE);

        setData(UIConstants.CSS.CLASS_NAME, "chart"); //$NON-NLS-1$

        getLegend().setVisible(false);

        // x axis
        IAxis xAxis = getAxisSet().getXAxis(0);
        xAxis.getTitle().setVisible(false);
        xAxis.getTick().setVisible(false);
        xAxis.getGrid().setStyle(LineStyle.NONE);

        // y axis
        IAxis yAxis = getAxisSet().getYAxis(0);
        yAxis.getTitle().setVisible(false);
        yAxis.setPosition(Position.Secondary);

        getPlotArea().addCustomPaintListener(new ICustomPaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                paintBackgroundBands(e);
                paintTimeGrid(e);
                paintNonTradingDayMarker(e);
            }

            @Override
            public boolean drawBehindSeries()
            {
                return true;
            }
        });

        getPlotArea().getControl().addPaintListener(this::paintMarkerLines);

        toolTip = new TimelineChartToolTip(this);

        chartTools = new ChartToolsManager(this);

        ZoomMouseWheelListener.attachTo(this);
        MovePlotKeyListener.attachTo(this);
        ZoomInAreaListener.attachTo(this);
        getPlotArea().getControl().addTraverseListener(event -> event.doit = true);

        this.contextMenu = new ChartContextMenu(this);
        this.contextMenu.setLineWidthConfigurable(true);
    }

    /**
     * Offers the line width in the context menu. Offered by default; charts
     * with configurable data series switch it off because the user picks a line
     * width per data series there.
     */
    public void setLineWidthConfigurable(boolean configurable)
    {
        contextMenu.setLineWidthConfigurable(configurable);
    }

    public void addMarkerLine(LocalDate date, Color color, String label)
    {
        addMarkerLine(date, color, label, null);
    }

    public void addMarkerLine(LocalDate date, Color color, String label, Double value)
    {
        this.markerLines.add(new MarkerLine(date, color, label, value));
        Collections.sort(this.markerLines, (ml1, ml2) -> ml1.date.compareTo(ml2.date));
    }

    public void clearMarkerLines()
    {
        this.markerLines.clear();
    }

    public void addNonTradingDayMarker(LocalDate date, Color color)
    {
        this.nonTradingDayMarkers.add(new NonTradingDayMarker(date, color));
        Collections.sort(this.nonTradingDayMarkers, (ml1, ml2) -> ml1.date.compareTo(ml2.date));
    }

    public void removeNonTradingDayMarker(LocalDate date, Color color)
    {
        this.nonTradingDayMarkers.remove(new NonTradingDayMarker(date, color));
    }

    public void clearNonTradingDayMarker()
    {
        this.nonTradingDayMarkers.clear();
    }

    /**
     * Paints a subtle background band covering the closed date range from
     * {@code start} through {@code end}. The band remains behind the time grid
     * and all data series when the chart is repainted, zoomed or panned.
     *
     * @param start
     *            first date included in the band
     * @param end
     *            last date included in the band
     * @param color
     *            background colour of the band
     * @param alpha
     *            opacity between {@code 0} (transparent) and {@code 255}
     *            (opaque)
     */
    public void addBackgroundBand(LocalDate start, LocalDate end, Color color, int alpha)
    {
        Objects.requireNonNull(start, "start"); //$NON-NLS-1$
        Objects.requireNonNull(end, "end"); //$NON-NLS-1$
        Objects.requireNonNull(color, "color"); //$NON-NLS-1$

        if (end.isBefore(start))
            throw new IllegalArgumentException("The end of a background band must not precede its start"); //$NON-NLS-1$
        if (alpha < 0 || alpha > 255)
            throw new IllegalArgumentException("Alpha must be between 0 and 255"); //$NON-NLS-1$

        backgroundBands.add(new BackgroundBand(start, end, color, alpha));
    }

    /**
     * Removes all background bands added with {@link #addBackgroundBand(LocalDate, LocalDate, Color, int)}.
     */
    public void clearBackgroundBands()
    {
        backgroundBands.clear();
    }

    public void addPlotPaintListener(PaintListener listener)
    {
        getPlotArea().addCustomPaintListener(new ICustomPaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                listener.paintControl(e);
            }

            @Override
            public boolean drawBehindSeries()
            {
                return false;
            }
        });
    }

    public ILineSeries<Integer> addDateSeries(String id, LocalDate[] dates, double[] values, String label)
    {
        return addDateSeries(id, dates, values, Colors.BLACK, false, label);
    }

    public ILineSeries<Integer> addDateSeries(String id, LocalDate[] dates, double[] values, Color color, String label)
    {
        return addDateSeries(id, dates, values, color, false, label);
    }

    private ILineSeries<Integer> addDateSeries(String id, LocalDate[] dates, double[] values, Color color,
                    boolean showArea, String label)
    {
        @SuppressWarnings("unchecked")
        var lineSeries = (ILineSeries<Integer>) getSeriesSet().createSeries(SeriesType.LINE, id);
        lineSeries.setDescription(label);
        lineSeries.setDataModel(new TimelineSeriesModel(dates, values));
        lineSeries.enableArea(showArea);
        lineSeries.setLineWidth(ChartLineWidth.get());
        lineSeries.setSymbolType(PlotSymbolType.NONE);
        lineSeries.setLineColor(color);
        lineSeries.setAntialias(SWT.ON);
        return lineSeries;
    }

    public IBarSeries<Integer> addDateBarSeries(String id, LocalDate[] dates, double[] values, String label)
    {
        return addDateBarSeries(id, dates, values, Colors.DARK_GRAY, label);
    }

    public IBarSeries<Integer> addDateBarSeries(String id, LocalDate[] dates, double[] values, Color color,
                    String label)
    {
        @SuppressWarnings("unchecked")
        var barSeries = (IBarSeries<Integer>) getSeriesSet().createSeries(SeriesType.BAR, id);
        barSeries.setDescription(label);
        barSeries.setDataModel(new TimelineSeriesModel(dates, values));
        barSeries.setBarColor(color);
        barSeries.setBarPadding(100);
        return barSeries;
    }

    public TimelineChartToolTip getToolTip()
    {
        return toolTip;
    }

    public ChartToolsManager getChartToolsManager()
    {
        return chartTools;
    }

    private void paintTimeGrid(PaintEvent e)
    {
        IAxis xAxis = getAxisSet().getXAxis(0);
        Range range = xAxis.getRange();

        LocalDate start = LocalDate.ofEpochDay((long) range.lower);
        LocalDate end = LocalDate.ofEpochDay((long) range.upper);

        TimeGridHelper.paintTimeGrid(this, e, start, end, cursor -> xAxis.getPixelCoordinate(cursor.toEpochDay()));
    }

    private void paintBackgroundBands(PaintEvent event)
    {
        if (backgroundBands.isEmpty())
            return;

        IAxis xAxis = getAxisSet().getXAxis(0);
        Range range = xAxis.getRange();
        LocalDate visibleStart = LocalDate.ofEpochDay((long) range.lower);
        LocalDate visibleEnd = LocalDate.ofEpochDay((long) range.upper);

        int alpha = event.gc.getAlpha();
        try
        {
            for (BackgroundBand band : backgroundBands)
            {
                if (band.end.isBefore(visibleStart) || band.start.isAfter(visibleEnd))
                    continue;

                LocalDate start = band.start.isBefore(visibleStart) ? visibleStart : band.start;
                LocalDate end = band.end.isAfter(visibleEnd) ? visibleEnd : band.end;

                int startX = getStartPixel(xAxis, start);
                int endX = getEndPixel(xAxis, end);

                event.gc.setBackground(band.color);
                event.gc.setAlpha(band.alpha);
                event.gc.fillRectangle(startX, 0, Math.max(1, endX - startX), event.height);
            }
        }
        finally
        {
            event.gc.setAlpha(alpha);
        }
    }

    private int getStartPixel(IAxis axis, LocalDate date)
    {
        int pixel = axis.getPixelCoordinate(date.toEpochDay());
        int previousPixel = axis.getPixelCoordinate(date.minusDays(1).toEpochDay());
        return pixel - Math.round((pixel - previousPixel) / 2f);
    }

    private int getEndPixel(IAxis axis, LocalDate date)
    {
        int pixel = axis.getPixelCoordinate(date.toEpochDay());
        int nextPixel = axis.getPixelCoordinate(date.plusDays(1).toEpochDay());
        return pixel + Math.round((nextPixel - pixel) / 2f);
    }

    private void paintMarkerLines(PaintEvent e) // NOSONAR
    {
        if (markerLines.isEmpty())
            return;

        IAxis xAxis = getAxisSet().getXAxis(0);
        IAxis yAxis = getAxisSet().getYAxis(0);

        int labelExtentX = 0;
        int labelStackY = 0;

        for (MarkerLine marker : markerLines)
        {
            int x = xAxis.getPixelCoordinate(marker.getEpochDay());

            e.gc.setLineStyle(SWT.LINE_SOLID);
            e.gc.setForeground(marker.color);
            e.gc.setLineWidth(ChartLineWidth.get());
            e.gc.drawLine(x, 0, x, e.height);

            if (marker.label != null)
            {
                Point textExtent = e.gc.textExtent(marker.label);
                boolean flip = x + 5 + textExtent.x > e.width;
                int textX = flip ? x - 5 - textExtent.x : x + 5;
                labelStackY = labelExtentX > textX ? labelStackY + textExtent.y : 0;
                labelExtentX = x + 5 + textExtent.x;

                e.gc.drawText(marker.label, textX, e.height - 20 - labelStackY, true);
            }

            if (marker.value != null)
            {
                int y = yAxis.getPixelCoordinate(marker.value);
                e.gc.drawLine(x - 5, y, x + 5, y);
            }
        }
    }

    private void paintNonTradingDayMarker(PaintEvent eventNonTradingDay) // NOSONAR
    {
        if (nonTradingDayMarkers.isEmpty())
            return;
        IAxis xAxis = getAxisSet().getXAxis(0);
        Range range = xAxis.getRange();
        LocalDate start = LocalDate.ofEpochDay((long) range.lower);
        LocalDate end = LocalDate.ofEpochDay((long) range.upper);
        long days = ChronoUnit.DAYS.between(start, end);
        double dayWidth = Math.ceil((eventNonTradingDay.width) / (days - 1d));
        int markerWidthXLeft = (int) (dayWidth / 2);
        ArrayList<LocalDate> nonTradingDayDates = new ArrayList<>();
        for (NonTradingDayMarker marker : nonTradingDayMarkers)
        {
            nonTradingDayDates.add(marker.date);
        }
        for (NonTradingDayMarker marker : nonTradingDayMarkers)
        {
            int x = xAxis.getPixelCoordinate(marker.getEpochDay());
            double barWidth = 0;
            for (LocalDate date = marker.date; date.isBefore(end); date = date.plusDays(1))
            {
                if (!nonTradingDayDates.contains(date))
                    break;
                barWidth = barWidth + dayWidth;
            }
            if (nonTradingDayDates.contains(marker.date.minusDays(1)))
                continue;

            if (barWidth < 3)
                barWidth = 3;
            eventNonTradingDay.gc.setBackground(marker.color);
            eventNonTradingDay.gc.setLineWidth(0);
            eventNonTradingDay.gc.setAlpha(100);
            eventNonTradingDay.gc.fillRectangle(x - markerWidthXLeft, 25, (int) barWidth,
                            eventNonTradingDay.height - 25);
            eventNonTradingDay.gc.setBackgroundPattern(null);
            eventNonTradingDay.gc.setAlpha(255); // Dirty fix to avoid that the
                                                 // whole chart is adjusted
        }
    }

    public void exportMenuAboutToShow(IMenuManager manager, String label)
    {
        this.contextMenu.exportMenuAboutToShow(manager, label);
    }

    public void resetAxes()
    {
        for (IAxis axis : getAxisSet().getAxes())
            axis.setRange(new Range(0, 1));
    }

    public void adjustRange()
    {
        try
        {
            setRedraw(false);

            getAxisSet().adjustRange();
            adjustXAxisRangeForBackgroundBands();
            ChartUtil.addYMargins(this, 0.08);
        }
        finally
        {
            setRedraw(true);
        }
    }

    @Override
    public void save(String filename, int format)
    {
        ChartUtil.save(this, filename, format);
    }

    @Override
    public boolean setFocus()
    {
        return getPlotArea().getControl().setFocus();
    }

    public IAxis getOrCreateAxis(Object key, Supplier<IAxis> axisFactory)
    {
        return addedAxis.computeIfAbsent(key, x -> {
            IAxis axis = axisFactory.get();

            // As we are dynamically adding the series, we have to check whether
            // the updates on the chart are suspended. Otherwise, the layout
            // information is not available for the axis and then #adjustRange
            // will fail

            if (isUpdateSuspended())
            {
                // resuming updates will trigger #updateLayout
                suspendUpdate(false);
                suspendUpdate(true);
            }

            return axis;
        });
    }

    /**
     * Extends the x-axis to make every background band visible. Subclasses
     * overriding {@link #adjustRange()} must call this after adjusting their
     * axis set.
     */
    protected void adjustXAxisRangeForBackgroundBands()
    {
        if (backgroundBands.isEmpty())
            return;

        IAxis xAxis = getAxisSet().getXAxis(0);
        Range range = xAxis.getRange();
        double lower = range.lower;
        double upper = range.upper;

        for (BackgroundBand band : backgroundBands)
        {
            lower = Math.min(lower, band.start.toEpochDay());
            upper = Math.max(upper, band.end.toEpochDay());
        }

        if (lower != range.lower || upper != range.upper)
            xAxis.setRange(new Range(lower, upper));
    }
}
