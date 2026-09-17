package name.abuchen.portfolio.ui.util.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceBreakdown;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceRecord;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

/**
 * Immutable data model for a waterfall chart.
 * <p>
 * Values are stored in the minor unit of {@link #getCurrencyCode()}, just like
 * {@code Money}. It remains useful for every additive monetary breakdown while
 * also supporting performance labels for instrument contributions.
 */
public final class WaterfallDataset
{
    public enum EntryKind
    {
        START, CHANGE, SUBTOTAL, TOTAL
    }

    /** A source entry used to create a dataset. */
    public static final class Entry
    {
        private final String label;
        private final String categoryLabel;
        private final EntryKind kind;
        private final long value;
        private final Object source;

        private Entry(String label, EntryKind kind, long value, Object source)
        {
            this(label, label, kind, value, source);
        }

        private Entry(String label, String categoryLabel, EntryKind kind, long value, Object source)
        {
            this.label = Objects.requireNonNull(label, "label"); //$NON-NLS-1$
            this.categoryLabel = Objects.requireNonNull(categoryLabel, "categoryLabel"); //$NON-NLS-1$
            this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
            this.value = value;
            this.source = source;
        }

        public static Entry start(String label, long value)
        {
            return new Entry(label, EntryKind.START, value, null);
        }

        public static Entry change(String label, long value)
        {
            return new Entry(label, EntryKind.CHANGE, value, null);
        }

        /**
         * Adds a total-like bar at the supplied absolute value without changing
         * the cumulative value used by following changes.
         */
        public static Entry subtotal(String label, long value)
        {
            return new Entry(label, EntryKind.SUBTOTAL, value, null);
        }

        /**
         * Adds a final total at the supplied absolute value. Subsequent changes
         * (if any) start at this value.
         */
        public static Entry total(String label, long value)
        {
            return new Entry(label, EntryKind.TOTAL, value, null);
        }

        public String getLabel()
        {
            return label;
        }

        /**
         * Label used for the category axis. It can contain presentation-only
         * details while {@link #getLabel()} remains the semantic label used by
         * tooltips and exports.
         */
        public String getCategoryLabel()
        {
            return categoryLabel;
        }

        public EntryKind getKind()
        {
            return kind;
        }

        /**
         * The signed change for {@link EntryKind#CHANGE}, otherwise the
         * absolute value of a start, subtotal, or total.
         */
        public long getValue()
        {
            return value;
        }

        public Object getSource()
        {
            return source;
        }
    }

    /** A fully calculated floating bar. */
    public static final class Bar
    {
        private final Entry entry;
        private final long start;
        private final long end;
        private final long change;

        private Bar(Entry entry, long start, long end, long change)
        {
            this.entry = entry;
            this.start = start;
            this.end = end;
            this.change = change;
        }

        public Entry getEntry()
        {
            return entry;
        }

        public String getLabel()
        {
            return entry.getLabel();
        }

        public String getCategoryLabel()
        {
            return entry.getCategoryLabel();
        }

        public EntryKind getKind()
        {
            return entry.getKind();
        }

        /** Value at which the floating bar starts, in minor currency units. */
        public long getStart()
        {
            return start;
        }

        /** Value at which the floating bar ends, in minor currency units. */
        public long getEnd()
        {
            return end;
        }

        /** Signed contribution of this bar, in minor currency units. */
        public long getChange()
        {
            return change;
        }

        public boolean isTotal()
        {
            return getKind() != EntryKind.CHANGE;
        }

        public Object getSource()
        {
            return entry.getSource();
        }
    }

    private final String currencyCode;
    private final List<Bar> bars;
    private final long minimum;
    private final long minimumValue;
    private final long maximum;
    private final long maximumValue;

    public WaterfallDataset(String currencyCode, List<Entry> entries)
    {
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode"); //$NON-NLS-1$
        Objects.requireNonNull(entries, "entries"); //$NON-NLS-1$

        var calculated = new ArrayList<Bar>(entries.size());
        long cumulative = 0;
        long min = 0;
        long minValue = Long.MAX_VALUE;
        long max = 0;
        long maxValue = Long.MIN_VALUE;

        for (var entry : entries)
        {
            Objects.requireNonNull(entry, "entry"); //$NON-NLS-1$

            long start;
            long end;
            long change;

            switch (entry.getKind())
            {
                case CHANGE:
                    start = cumulative;
                    change = entry.getValue();
                    end = start + change;
                    cumulative = end;
                    break;
                case SUBTOTAL:
                    start = 0;
                    end = entry.getValue();
                    change = 0;
                    break;
                case TOTAL:
                    start = 0;
                    end = entry.getValue();
                    change = end - cumulative;
                    cumulative = end;
                    break;
                case START:
                    start = 0;
                    end = entry.getValue();
                    change = end;
                    cumulative = end;
                    break;
                default:
                    throw new IllegalArgumentException(entry.getKind().toString());
            }

            calculated.add(new Bar(entry, start, end, change));
            min = Math.min(min, Math.min(start, end));
            long entryMinimum = entry.getKind() == EntryKind.CHANGE ? Math.min(start, end) : end;
            long entryMaximum = entry.getKind() == EntryKind.CHANGE ? Math.max(start, end) : end;
            minValue = Math.min(minValue, entryMinimum);
            maxValue = Math.max(maxValue, entryMaximum);
            max = Math.max(max, Math.max(start, end));
        }

        this.bars = Collections.unmodifiableList(calculated);
        this.minimum = min;
        this.minimumValue = minValue == Long.MAX_VALUE ? 0 : minValue;
        this.maximum = max;
        this.maximumValue = maxValue == Long.MIN_VALUE ? 0 : maxValue;
    }

    /**
     * Maps a completed domain breakdown to chart data. This constructor does
     * not perform any domain calculation; callers can use
     * {@link PerformanceBreakdown#limitContributions(int)} before mapping.
     */
    public WaterfallDataset(PerformanceBreakdown breakdown)
    {
        this(currencyCodeOf(breakdown), entriesOf(breakdown));
    }

    /**
     * Convenience constructor for the performance waterfall view. Top-N
     * aggregation remains part of {@link PerformanceBreakdown}.
     */
    public WaterfallDataset(PerformanceBreakdown breakdown, int topN)
    {
        this(breakdown.limitContributions(topN));
    }

    /**
     * Convenience constructor for instrument contribution waterfalls. The
     * category labels include each instrument's reporting-period and
     * annualized true time-weighted return.
     */
    public WaterfallDataset(PerformanceBreakdown breakdown, int topN, ClientPerformanceSnapshot snapshot)
    {
        this(breakdown.limitContributions(topN), instrumentLabelProvider(snapshot));
    }

    private WaterfallDataset(PerformanceBreakdown breakdown,
                    Function<PerformanceBreakdown.Entry, String> categoryLabelProvider)
    {
        this(currencyCodeOf(breakdown), entriesOf(breakdown, categoryLabelProvider));
    }

    private static String currencyCodeOf(PerformanceBreakdown breakdown)
    {
        Objects.requireNonNull(breakdown, "breakdown"); //$NON-NLS-1$
        return breakdown.getEntries().isEmpty() ? "XXX" //$NON-NLS-1$
                        : breakdown.getEntries().get(0).getAmount().getCurrencyCode();
    }

    private static List<Entry> entriesOf(PerformanceBreakdown breakdown)
    {
        return entriesOf(breakdown, PerformanceBreakdown.Entry::getLabel);
    }

    private static List<Entry> entriesOf(PerformanceBreakdown breakdown,
                    Function<PerformanceBreakdown.Entry, String> categoryLabelProvider)
    {
        Objects.requireNonNull(breakdown, "breakdown"); //$NON-NLS-1$
        Objects.requireNonNull(categoryLabelProvider, "categoryLabelProvider"); //$NON-NLS-1$
        return breakdown.getEntries().stream()
                        .map(entry -> new Entry(entry.getLabel(), categoryLabelProvider.apply(entry),
                                        EntryKind.valueOf(entry.getKind().name()), entry.getAmount().getAmount(),
                                        entry))
                        .toList();
    }

    private static Function<PerformanceBreakdown.Entry, String> instrumentLabelProvider(
                    ClientPerformanceSnapshot snapshot)
    {
        Objects.requireNonNull(snapshot, "snapshot"); //$NON-NLS-1$

        var start = snapshot.getStartClientSnapshot();
        var end = snapshot.getEndClientSnapshot();
        var interval = Interval.of(start.getTime(), end.getTime());
        var records = new HashMap<Security, LazySecurityPerformanceRecord>();

        LazySecurityPerformanceSnapshot.create(snapshot.getClient(), start.getCurrencyConverter(), interval, start, end)
                        .getRecords().forEach(record -> records.put(record.getSecurity(), record));

        return entry -> {
            if (entry.getSecurity() == null)
                return entry.getLabel();

            var record = records.get(entry.getSecurity());
            if (record == null)
                return entry.getLabel();

            String period = Values.Percent2.format(record.getTrueTimeWeightedRateOfReturn());
            String annualized = Values.AnnualizedPercent2
                            .format(record.getTrueTimeWeightedRateOfReturnAnnualized());
            return entry.getLabel() + "\n" + period + " / " + annualized; //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    public String getCurrencyCode()
    {
        return currencyCode;
    }

    public List<Bar> getBars()
    {
        return bars;
    }

    public boolean isEmpty()
    {
        return bars.isEmpty();
    }

    public long getMinimum()
    {
        return minimum;
    }

    public long getMaximum()
    {
        return maximum;
    }

    /** Lowest actual cumulative value, without forcing the range to include zero. */
    public long getMinimumValue()
    {
        return minimumValue;
    }

    /** Highest actual cumulative value, without forcing the range to include zero. */
    public long getMaximumValue()
    {
        return maximumValue;
    }
}
