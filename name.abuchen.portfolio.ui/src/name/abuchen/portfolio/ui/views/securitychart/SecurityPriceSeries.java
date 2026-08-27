package name.abuchen.portfolio.ui.views.securitychart;

import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Quote;

public final class SecurityPriceSeries
{
    private SecurityPriceSeries()
    {
    }

    /**
     * Converts every price with the exchange rate applicable on the price date.
     * The source list and its entries are not modified.
     */
    public static List<SecurityPrice> convert(List<SecurityPrice> prices, String sourceCurrency,
                    CurrencyConverter converter)
    {
        Objects.requireNonNull(prices);
        Objects.requireNonNull(converter);

        if (sourceCurrency == null || sourceCurrency.equals(converter.getTermCurrency()))
            return List.copyOf(prices);

        return prices.stream().map(price -> {
            Quote quote = Quote.of(sourceCurrency, price.getValue());
            Quote converted = converter.convert(price.getDate(), quote);
            return new SecurityPrice(price.getDate(), converted.getAmount());
        }).toList();
    }
}
