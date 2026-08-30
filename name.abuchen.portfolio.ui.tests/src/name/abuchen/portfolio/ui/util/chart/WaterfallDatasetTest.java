package name.abuchen.portfolio.ui.util.chart;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class WaterfallDatasetTest
{
    @Test
    public void testLongCategoryLabelsAreWrapped()
    {
        assertThat(WaterfallChart.formatCategoryLabel("Very Long Instrument Name"), is("Very Long\nInstrument Name"));
        assertThat(WaterfallChart.formatCategoryLabel("Very Long Instrument Name With More Details"),
                        is("Very Long\nInstrument Name…"));
        assertThat(WaterfallChart.formatCategoryLabel("abcdefghijklmnopqrst"), is("abcdefghijklmnop…"));
        assertThat(WaterfallChart.formatCategoryLabel("Short"), is("Short"));
    }

    @Test
    public void testChartValueConvertsMinorToMajorCurrencyUnits()
    {
        assertThat(WaterfallChart.toChartValue(Values.Amount.factorize(250_000)), is(250_000.0));
        assertThat(WaterfallChart.toChartValue(Values.Amount.factorize(-12.34)), is(-12.34));
    }

    @Test
    public void testCalculatesFloatingBarsAndTotals()
    {
        var dataset = new WaterfallDataset("EUR", List.of(WaterfallDataset.Entry.start("Initial", 10_000),
                        WaterfallDataset.Entry.change("Gain", 2_500), WaterfallDataset.Entry.change("Fee", -500),
                        WaterfallDataset.Entry.subtotal("Intermediate", 12_000),
                        WaterfallDataset.Entry.total("Final", 12_000)));

        assertThat(dataset.getBars().stream().map(WaterfallDataset.Bar::getStart).toList(),
                        contains(0L, 10_000L, 12_500L, 0L, 0L));
        assertThat(dataset.getBars().stream().map(WaterfallDataset.Bar::getEnd).toList(),
                        contains(10_000L, 12_500L, 12_000L, 12_000L, 12_000L));
        assertThat(dataset.getBars().stream().map(WaterfallDataset.Bar::getChange).toList(),
                        contains(10_000L, 2_500L, -500L, 0L, 0L));
        assertThat(dataset.getMinimum(), is(0L));
        assertThat(dataset.getMaximum(), is(12_500L));
    }

    @Test
    public void testIncludesZeroAndNegativeValuesInRange()
    {
        var dataset = new WaterfallDataset("EUR", List.of(WaterfallDataset.Entry.start("Initial", -1_000),
                        WaterfallDataset.Entry.change("Gain", 500)));

        assertThat(dataset.getMinimum(), is(-1_000L));
        assertThat(dataset.getMaximum(), is(0L));
        assertThat(dataset.getBars().get(1).getStart(), is(-1_000L));
        assertThat(dataset.getBars().get(1).getEnd(), is(-500L));
    }
}
