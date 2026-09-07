package name.abuchen.portfolio.cli;

import java.util.ArrayList;
import java.util.List;

/** A CLI line with optional semantic values that can be coloured at render time. */
final class CliLine
{
    enum Metric
    {
        RETURN, IRR, CONTRIBUTION, IMPACT
    }

    record Value(int start, int end, double value, Metric metric)
    {
    }

    private final String text;
    private final List<Value> values;

    private CliLine(String text, List<Value> values)
    {
        this.text = text;
        this.values = List.copyOf(values);
    }

    static CliLine plain(String text)
    {
        return new CliLine(text, List.of());
    }

    static Builder builder()
    {
        return new Builder();
    }

    String text()
    {
        return text;
    }

    List<Value> values()
    {
        return values;
    }

    static final class Builder
    {
        private final StringBuilder text = new StringBuilder();
        private final List<Value> values = new ArrayList<>();

        Builder append(String value)
        {
            text.append(value);
            return this;
        }

        Builder appendLeft(String value, int width)
        {
            text.append(value);
            appendSpaces(width - value.length());
            return this;
        }

        Builder appendRight(String value, int width)
        {
            appendSpaces(width - value.length());
            text.append(value);
            return this;
        }

        Builder appendValue(String value, int width, double number, Metric metric)
        {
            appendSpaces(width - value.length());
            int start = text.length();
            text.append(value);
            values.add(new Value(start, text.length(), number, metric));
            return this;
        }

        CliLine build()
        {
            return new CliLine(text.toString(), values);
        }

        private void appendSpaces(int count)
        {
            if (count > 0)
                text.append(" ".repeat(count)); //$NON-NLS-1$
        }
    }
}
