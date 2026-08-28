package name.abuchen.portfolio.ui.views.securitychart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;

public final class SecurityPriceSeries
{
    /**
     * The outcome of converting a price series. Prices whose exchange rate is
     * unavailable are deliberately not converted. This lets callers draw the
     * available portions and mark the remaining dates instead of presenting a
     * fabricated one-to-one conversion.
     */
    public static final class ConversionResult
    {
        private final List<SecurityPrice> sourcePrices;
        private final List<SecurityPrice> convertedPrices;
        private final List<List<SecurityPrice>> convertedPriceSegments;
        private final List<MissingExchangeRateInterval> missingExchangeRateIntervals;
        private final List<RateUse> rateUses;
        private final Optional<LocalDate> sourceStartDate;
        private final Optional<LocalDate> sourceEndDate;

        private ConversionResult(List<SecurityPrice> sourcePrices, List<SecurityPrice> convertedPrices,
                        List<List<SecurityPrice>> convertedPriceSegments,
                        List<MissingExchangeRateInterval> missingExchangeRateIntervals, List<RateUse> rateUses)
        {
            this.sourcePrices = List.copyOf(sourcePrices);
            this.convertedPrices = List.copyOf(convertedPrices);
            this.convertedPriceSegments = convertedPriceSegments.stream().map(List::copyOf).toList();
            this.missingExchangeRateIntervals = List.copyOf(missingExchangeRateIntervals);
            this.rateUses = List.copyOf(rateUses);
            this.sourceStartDate = this.sourcePrices.stream().map(SecurityPrice::getDate).min(LocalDate::compareTo);
            this.sourceEndDate = this.sourcePrices.stream().map(SecurityPrice::getDate).max(LocalDate::compareTo);
        }

        /**
         * Returns the unmodified source prices, including those that could not
         * be converted. The first and last entries define the full chart
         * extent.
         */
        public List<SecurityPrice> getSourcePrices()
        {
            return sourcePrices;
        }

        /**
         * Returns every successfully converted price in source order.
         */
        public List<SecurityPrice> getConvertedPrices()
        {
            return convertedPrices;
        }

        /**
         * Returns the contiguous portions of successfully converted prices.
         * Render each portion separately so a line is not drawn across a
         * missing exchange-rate interval.
         */
        public List<List<SecurityPrice>> getConvertedPriceSegments()
        {
            return convertedPriceSegments;
        }

        /**
         * Returns closed intervals of source-price dates for which no
         * exchange rate was available. The interval boundaries are inclusive.
         */
        public List<MissingExchangeRateInterval> getMissingExchangeRateIntervals()
        {
            return missingExchangeRateIntervals;
        }

        /**
         * Returns the source-price date and the exchange-rate date used for
         * each converted price. A caller can use this provenance to identify
         * stale carried-forward rates separately from unavailable rates.
         */
        public List<RateUse> getRateUses()
        {
            return rateUses;
        }

        public Optional<LocalDate> getSourceStartDate()
        {
            return sourceStartDate;
        }

        public Optional<LocalDate> getSourceEndDate()
        {
            return sourceEndDate;
        }
    }

    /**
     * A closed date interval. Both {@link #start()} and {@link #end()} are
     * included.
     */
    public record MissingExchangeRateInterval(LocalDate start, LocalDate end)
    {
        public MissingExchangeRateInterval
        {
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            if (end.isBefore(start))
                throw new IllegalArgumentException("The end date must not be before the start date"); //$NON-NLS-1$
        }

        public boolean contains(LocalDate date)
        {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    /**
     * Records which historical rate was used to convert a source price.
     */
    public record RateUse(LocalDate priceDate, LocalDate exchangeRateDate)
    {
    }

    private SecurityPriceSeries()
    {
    }

    /**
     * Converts every source price for which an exchange rate is available. The
     * source list and its entries are not modified. Missing rates are returned
     * as explicit intervals; they are never converted at a one-to-one rate.
     */
    public static ConversionResult convert(List<SecurityPrice> prices, String sourceCurrency,
                    CurrencyConverter converter)
    {
        Objects.requireNonNull(prices);
        Objects.requireNonNull(converter);

        if (sourceCurrency == null || sourceCurrency.equals(converter.getTermCurrency()))
            return new ConversionResult(prices, prices, prices.isEmpty() ? List.of() : List.of(prices), List.of(),
                            List.of());

        var convertedPrices = new ArrayList<SecurityPrice>(prices.size());
        var convertedPriceSegments = new ArrayList<List<SecurityPrice>>();
        var convertedPriceSegment = new ArrayList<SecurityPrice>();
        var missingExchangeRateIntervals = new ArrayList<MissingExchangeRateInterval>();
        var rateUses = new ArrayList<RateUse>(prices.size());
        LocalDate missingStart = null;
        LocalDate missingEnd = null;

        for (SecurityPrice price : prices)
        {
            if (price.getValue() == 0)
            {
                if (missingStart != null)
                {
                    missingExchangeRateIntervals.add(new MissingExchangeRateInterval(missingStart, missingEnd));
                    missingStart = null;
                }

                var convertedPrice = new SecurityPrice(price.getDate(), 0);
                convertedPrices.add(convertedPrice);
                convertedPriceSegment.add(convertedPrice);
                continue;
            }

            Optional<ExchangeRate> rate = converter.getRateIfAvailable(price.getDate(), sourceCurrency);
            if (rate.isEmpty())
            {
                if (!convertedPriceSegment.isEmpty())
                {
                    convertedPriceSegments.add(convertedPriceSegment);
                    convertedPriceSegment = new ArrayList<>();
                }

                if (missingStart == null)
                    missingStart = price.getDate();
                missingEnd = price.getDate();
                continue;
            }

            if (missingStart != null)
            {
                missingExchangeRateIntervals.add(new MissingExchangeRateInterval(missingStart, missingEnd));
                missingStart = null;
            }

            long convertedValue = rate.get().getValue().multiply(BigDecimal.valueOf(price.getValue())).setScale(0,
                            RoundingMode.HALF_DOWN).longValue();
            var convertedPrice = new SecurityPrice(price.getDate(), convertedValue);
            convertedPrices.add(convertedPrice);
            convertedPriceSegment.add(convertedPrice);
            rateUses.add(new RateUse(price.getDate(), rate.get().getTime()));
        }

        if (!convertedPriceSegment.isEmpty())
            convertedPriceSegments.add(convertedPriceSegment);
        if (missingStart != null)
            missingExchangeRateIntervals.add(new MissingExchangeRateInterval(missingStart, missingEnd));

        return new ConversionResult(prices, convertedPrices, convertedPriceSegments, missingExchangeRateIntervals,
                        rateUses);
    }
}
