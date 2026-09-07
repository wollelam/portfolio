package name.abuchen.portfolio.cli;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Calculates discrete red/green shades for comparable signed values. */
final class ValueColourScale
{
    private static final double THRESHOLD_EPSILON = 1e-12;
    static final String RESET = "\033[0m"; //$NON-NLS-1$
    static final String DIM_GREEN = "\033[2;32m"; //$NON-NLS-1$
    static final String GREEN = "\033[32m"; //$NON-NLS-1$
    static final String BRIGHT_GREEN = "\033[92m"; //$NON-NLS-1$
    static final String DIM_RED = "\033[2;31m"; //$NON-NLS-1$
    static final String RED = "\033[31m"; //$NON-NLS-1$
    static final String BRIGHT_RED = "\033[91m"; //$NON-NLS-1$

    private final double maximum;

    private ValueColourScale(double maximum)
    {
        this.maximum = maximum;
    }

    static Map<CliLine.Metric, ValueColourScale> forLines(List<CliLine> lines)
    {
        var maximums = new EnumMap<CliLine.Metric, Double>(CliLine.Metric.class);
        var infinite = java.util.EnumSet.noneOf(CliLine.Metric.class);
        for (CliLine line : lines)
        {
            for (CliLine.Value value : line.values())
            {
                if (Double.isNaN(value.value()))
                    continue;

                if (!Double.isFinite(value.value()))
                {
                    infinite.add(value.metric());
                    continue;
                }

                double magnitude = Math.abs(value.value());
                maximums.merge(value.metric(), magnitude, Math::max);
            }
        }

        var result = new EnumMap<CliLine.Metric, ValueColourScale>(CliLine.Metric.class);
        for (CliLine.Metric metric : CliLine.Metric.values())
        {
            if (maximums.containsKey(metric) || infinite.contains(metric))
            {
                double maximum = maximums.getOrDefault(metric, 0d);
                if (infinite.contains(metric))
                    maximum = Math.max(maximum, 1d);
                result.put(metric, new ValueColourScale(maximum));
            }
        }
        return result;
    }

    static String apply(CliLine line, Map<CliLine.Metric, ValueColourScale> scales)
    {
        if (line.values().isEmpty())
            return line.text();

        StringBuilder styled = new StringBuilder(line.text().length() + line.values().size() * 16);
        int cursor = 0;
        for (CliLine.Value value : line.values())
        {
            styled.append(line.text(), cursor, value.start());
            String text = line.text().substring(value.start(), value.end());
            ValueColourScale scale = scales.get(value.metric());
            styled.append(scale == null ? text : scale.colour(text, value.value()));
            cursor = value.end();
        }
        styled.append(line.text(), cursor, line.text().length());
        return styled.toString();
    }

    private String colour(String text, double value)
    {
        if (value == 0 || Double.isNaN(value) || maximum == 0)
            return text;

        double magnitude = Double.isFinite(value) ? Math.abs(value) : maximum;
        double fraction = Math.min(1d, magnitude / maximum);
        boolean strong = fraction > 2d / 3d + THRESHOLD_EPSILON;
        boolean medium = fraction > 1d / 3d + THRESHOLD_EPSILON;
        String colour;
        if (value > 0)
            colour = strong ? BRIGHT_GREEN : medium ? GREEN : DIM_GREEN;
        else
            colour = strong ? BRIGHT_RED : medium ? RED : DIM_RED;
        return colour + text + RESET;
    }
}
