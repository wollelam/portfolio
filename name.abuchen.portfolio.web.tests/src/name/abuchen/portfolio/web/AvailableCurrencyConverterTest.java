package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.impl.EmptyExchangeRateTimeSeries;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;

@SuppressWarnings("nls")
public class AvailableCurrencyConverterTest
{
    @Test
    public void usesRecordedRateThroughDateAndFirstRateBeforeHistory()
    {
        var series = new ExchangeRateTimeSeriesImpl();
        series.setBaseCurrency("RUB");
        series.setTermCurrency("DKK");
        series.addRate(new ExchangeRate(LocalDate.of(2017, 1, 2), new BigDecimal("0.12")));
        series.addRate(new ExchangeRate(LocalDate.of(2020, 1, 2), new BigDecimal("0.10")));
        var factory = new ExchangeRateProviderFactory(new Client())
        {
            @Override
            public ExchangeRateTimeSeries getTimeSeries(String baseCurrency, String termCurrency)
            {
                return baseCurrency.equals("RUB") && termCurrency.equals("DKK") ? series
                                : new EmptyExchangeRateTimeSeries(baseCurrency, termCurrency);
            }
        };
        var converter = new AvailableCurrencyConverter(factory, "DKK");

        assertEquals(LocalDate.of(2017, 1, 2), converter.getRate(LocalDate.of(2016, 10, 31), "RUB").getTime());
        assertEquals(new BigDecimal("0.12"), converter.getRate(LocalDate.of(2018, 1, 2), "RUB").getValue());
        assertEquals(new BigDecimal("0.10"), converter.getRate(LocalDate.of(2021, 1, 2), "RUB").getValue());
        assertThrows(DashboardService.MissingReportingRateException.class,
                        () -> converter.getRate(LocalDate.of(2018, 1, 2), "ZZZ"));
    }
}
