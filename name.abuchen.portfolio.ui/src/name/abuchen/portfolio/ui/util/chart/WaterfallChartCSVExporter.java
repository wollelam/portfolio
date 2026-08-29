package name.abuchen.portfolio.ui.util.chart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import org.apache.commons.csv.CSVPrinter;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.AbstractCSVExporter;

/** CSV export for the complete calculated waterfall dataset. */
public class WaterfallChartCSVExporter extends AbstractCSVExporter
{
    private final WaterfallChart chart;

    public WaterfallChartCSVExporter(WaterfallChart chart)
    {
        this.chart = chart;
    }

    @Override
    protected Shell getShell()
    {
        return chart.getShell();
    }

    @Override
    protected void writeToFile(File file) throws IOException
    {
        try (CSVPrinter printer = new CSVPrinter(
                        new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8), STRATEGY))
        {
            printer.print(Messages.ColumnLabel);
            printer.print(Messages.LabelPerformanceWaterfallType);
            printer.print(Messages.LabelPerformanceWaterfallStart);
            printer.print(Messages.LabelPerformanceWaterfallChange);
            printer.print(Messages.LabelPerformanceWaterfallEnd);
            printer.print(Messages.ColumnCurrency);
            printer.println();

            var dataset = chart.getDataset();
            for (var bar : dataset.getBars())
            {
                printer.print(bar.getLabel());
                printer.print(bar.getKind());
                printer.print(Values.Amount.format(bar.getStart()));
                printer.print(Values.Amount.format(bar.getChange()));
                printer.print(Values.Amount.format(bar.getEnd()));
                printer.print(dataset.getCurrencyCode());
                printer.println();
            }
        }
    }
}
