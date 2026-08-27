package name.abuchen.portfolio.ui.views.securitychart;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    public static Optional<List<SecurityPrice>> convert(List<SecurityPrice> prices, String sourceCurrency,
                    CurrencyConverter converter)
    {
        Objects.requireNonNull(prices);
        Objects.requireNonNull(converter);

        if (sourceCurrency == null || sourceCurrency.equals(converter.getTermCurrency()))
            return Optional.of(List.copyOf(prices));

        var convertedPrices = new java.util.ArrayList<SecurityPrice>(prices.size());
        for (SecurityPrice price : prices)
        {
            Quote quote = Quote.of(sourceCurrency, price.getValue());
            Optional<Quote> converted = converter.convertIfAvailable(price.getDate(), quote);
            if (converted.isEmpty())
                return Optional.empty();

            convertedPrices.add(new SecurityPrice(price.getDate(), converted.get().getAmount()));
        }
        return Optional.of(convertedPrices);
    }
}
