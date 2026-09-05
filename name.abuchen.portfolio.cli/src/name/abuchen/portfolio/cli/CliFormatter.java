package name.abuchen.portfolio.cli;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import name.abuchen.portfolio.money.DiscreetMode;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.FormatHelper;

final class CliFormatter
{
    private CliFormatter()
    {
    }

    static String format(String pattern, Object... arguments)
    {
        return String.format(Locale.ROOT, pattern, arguments);
    }

    static String money(Money value)
    {
        if (DiscreetMode.isActive())
            return Values.Money.format(value);
        return value.getCurrencyCode() + " " + decimal("#,##0.00", value.getAmount() / Values.Money.divider()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String money(Money value, String skipCurrencyCode)
    {
        return !FormatHelper.alwaysDisplayCurrencyCode() && skipCurrencyCode.equals(value.getCurrencyCode())
                        ? decimal("#,##0.00", value.getAmount() / Values.Money.divider()) //$NON-NLS-1$
                        : money(value);
    }

    static String share(long value)
    {
        return decimal("#,##0.########", value / Values.Share.divider()); //$NON-NLS-1$
    }

    static String quote(long value)
    {
        return decimal("#,##0.00######", value / Values.Quote.divider()); //$NON-NLS-1$
    }

    static String percent(double value)
    {
        return decimal("0.00%", value); //$NON-NLS-1$
    }

    private static String decimal(String pattern, double value)
    {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
    }
}
