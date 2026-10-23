package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class BenchmarkServiceTest
{
    private final LocalDate from = LocalDate.of(2025, 6, 10);
    private final LocalDate to = from.plusDays(2);
    private final Client client = new Client();

    @Test
    public void matchesTheCoreBenchmarkWithAnOpeningBaselineAndCarriesPriorQuotesForward()
    {
        client.setBaseCurrency("EUR");
        var account = new Account("Cash");
        account.setCurrencyCode("EUR");
        account.addTransaction(new AccountTransaction(from.minusDays(1).atStartOfDay(), "EUR", 100_000,
                        null, AccountTransaction.Type.DEPOSIT));
        client.addAccount(account);
        var security = security("EUR");
        price(security, from, 100);
        price(security, to, 110);
        var converter = converter(false);
        var index = PerformanceIndex.forClient(client, converter, Interval.of(from, to), new ArrayList<>());
        var expected = PerformanceIndex.forSecurity(index, security);
        var result = calculate(security, converter);
        assertNull(result.error());
        assertEquals(3, result.series().size());
        for (int ii = 0; ii < expected.getDates().length; ii++)
        {
            assertEquals(expected.getDates()[ii].toString(), result.series().get(ii).date());
            assertEquals(expected.getAccumulatedPercentage()[ii], result.series().get(ii).rate(), 0.000000001);
        }
        assertEquals(0, result.series().getFirst().rate(), 0.000000001);
        assertEquals(0, result.series().get(1).rate(), 0.000000001);
        assertEquals(0.1, result.rate(), 0.000000001);
        assertEquals("EUR", result.reportingCurrency());
    }

    @Test
    public void foreignCurrencySeriesIncludesFxChanges()
    {
        var security = security("USD");
        price(security, from, 100);
        price(security, to, 120);
        var result = calculate(security, converter(false));
        assertEquals(0.8, result.rate(), 0.000000001); // 120 * .75 / (100 * .50) - 1
        assertEquals("USD", result.sourceCurrency());
        assertEquals("EUR", result.reportingCurrency());
    }

    @Test
    public void missingFxDoesNotUseTheCoreFallbackRate()
    {
        var security = security("USD");
        price(security, from, 100);
        price(security, to, 120);
        var result = calculate(security, converter(true));
        assertNull(result.rate());
        assertTrue(result.series().isEmpty());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Exchange rates"));
    }

    @Test
    public void currencylessIndexIsExplicitlyNotFxAdjusted()
    {
        var security = security(null);
        price(security, from, 100);
        price(security, to, 120);
        var result = calculate(security, converter(true));
        assertEquals(0.2, result.rate(), 0.000000001);
        assertNull(result.reportingCurrency());
        assertTrue(result.warnings().getFirst().contains("without FX adjustment"));
    }

    @Test
    public void refusesHistoryStartingAfterTheOpeningValuation()
    {
        var security = security("EUR");
        price(security, from.plusDays(1), 100);
        price(security, to, 120);
        var result = calculate(security, converter(false));
        assertNotNull(result.error());
        assertTrue(result.error().contains("opening date"));
        assertTrue(result.series().isEmpty());
    }

    @Test
    public void futurePricesAreIgnoredAndZeroPricesAreNotPlotted()
    {
        var security = security("EUR");
        price(security, from, 100);
        price(security, to, 110);
        price(security, to.plusDays(1), 1000);
        var result = calculate(security, converter(false));
        assertEquals(0.1, result.rate(), 0.000000001);
        assertEquals(to.toString(), result.closingQuoteDate());
        price(security, from.plusDays(1), 0);
        assertNotNull(calculate(security, converter(false)).error());
    }

    @Test
    public void latestOnlyPricesAreSelectableAndStaleQuotesAreFlagged()
    {
        var security = security("EUR");
        security.setLatest(new LatestSecurityPrice(from.minusDays(10), Values.Quote.factorize(100)));
        assertEquals(1, BenchmarkService.options(client).size());
        var result = calculate(security, converter(false));
        assertEquals(from.minusDays(10).toString(), result.closingQuoteDate());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("older than seven")));
        assertEquals(0, result.rate(), 0.000000001);
    }

    @Test
    public void noSelectionAndRemovedSelectionDoNotBreakThePortfolioReport()
    {
        assertNull(BenchmarkService.calculate(client, converter(false), from, to, null));
        assertNotNull(BenchmarkService.calculate(client, converter(false), from, to, "removed").error());
        var security = security("EUR");
        assertTrue(BenchmarkService.options(client).isEmpty());
        assertNotNull(calculate(security, converter(false)).error());
        price(security, from, 100);
        assertFalse(BenchmarkService.options(client).isEmpty());
    }

    private Security security(String currency)
    {
        var security = new Security("Test series", currency);
        client.addSecurity(security);
        return security;
    }

    private void price(Security security, LocalDate date, double value)
    {
        security.addPrice(new SecurityPrice(date, Values.Quote.factorize(value)));
    }

    private BenchmarkService.Result calculate(Security security, CurrencyConverter converter)
    {
        return BenchmarkService.calculate(client, converter, from, to, security.getUUID());
    }

    private CurrencyConverter converter(boolean missing)
    {
        return new CurrencyConverter()
        {
            @Override
            public String getTermCurrency()
            {
                return "EUR";
            }

            @Override
            public ExchangeRate getRate(LocalDate date, String currency)
            {
                if (currency.equals("EUR"))
                    return new ExchangeRate(date, BigDecimal.ONE);
                throw new AssertionError("Benchmark conversion must not use fallback FX");
            }

            @Override
            public Optional<ExchangeRate> getRateIfAvailable(LocalDate date, String currency)
            {
                if (missing && date.equals(to))
                    return Optional.empty();
                return Optional.of(new ExchangeRate(date, new BigDecimal(date.equals(to) ? "0.75" : "0.50")));
            }

            @Override
            public CurrencyConverter with(String currency)
            {
                if (!currency.equals("EUR"))
                    throw new AssertionError("Unexpected reporting currency");
                return this;
            }
        };
    }
}
