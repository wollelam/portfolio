package name.abuchen.portfolio.ui.views.dataseries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.StrictCurrencyConverter;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyAccount;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyPortfolio;
import name.abuchen.portfolio.snapshot.filter.WithoutTaxesFilter;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.util.CacheKey;
import name.abuchen.portfolio.ui.util.ClientFilterMenu;
import name.abuchen.portfolio.ui.util.chart.ChartCurrencySelection;
import name.abuchen.portfolio.util.Interval;

/**
 * Cache for calculation results of DataSeries.
 */
@Creatable
public class DataSeriesCache
{
    private final Client client;
    private final Map<CacheKey, PerformanceIndex> cache = Collections.synchronizedMap(new HashMap<>());

    private CurrencyConverter converter;

    @Inject
    public DataSeriesCache(Client client, ExchangeRateProviderFactory factory)
    {
        this.client = client;
        this.converter = new CurrencyConverterImpl(factory, client.getBaseCurrency());
    }

    public void clear()
    {
        // the base currency might have changed
        this.converter = this.converter.with(client.getBaseCurrency());

        this.cache.clear();
    }

    public PerformanceIndex lookup(DataSeries series, Interval reportingPeriod)
    {
        return lookup(series, reportingPeriod, ChartCurrencySelection.PORTFOLIO, false);
    }

    public PerformanceIndex lookup(DataSeries series, Interval reportingPeriod, String currencySelection)
    {
        return lookup(series, reportingPeriod, currencySelection, true);
    }

    private PerformanceIndex lookup(DataSeries series, Interval reportingPeriod, String currencySelection,
                    boolean strict)
    {
        // Every data series is cached separately except the for the client. The
        // client data series are created out of the same PerformanceIndex
        // instance, e.g. accumulated and delta performance.
        String uuid = series.getType() == DataSeries.Type.CLIENT ? "$client$" : series.getUUID(); //$NON-NLS-1$

        Security security = getSecurity(series);
        String targetCurrency = ChartCurrencySelection.resolve(currencySelection, client, security);
        CurrencyConverter calculationConverter = converter.with(targetCurrency);
        if (strict)
            calculationConverter = new StrictCurrencyConverter(calculationConverter);

        CacheKey key = new CacheKey(uuid, reportingPeriod, targetCurrency, strict);

        // #computeIfAbsent leads to a ConcurrentMapModificdation b/c #calculate
        // might call #lookup to calculate other cache entries
        PerformanceIndex result = cache.get(key);
        if (result != null)
            return result;

        result = calculate(series, reportingPeriod, currencySelection, calculationConverter, strict);
        cache.put(key, result);

        return result;
    }

    private Security getSecurity(DataSeries series)
    {
        if (series.getInstance() instanceof Security security)
            return security;
        else if (series.getInstance() instanceof DerivedDataSeries derived)
            return getSecurity(derived.getBaseDataSeries());
        else
            return null;
    }

    private PerformanceIndex calculate(DataSeries series, Interval reportingPeriod, String currencySelection,
                    CurrencyConverter calculationConverter, boolean strict)
    {
        List<Exception> warnings = new ArrayList<>();

        try
        {
            switch (series.getType())
            {
                case CLIENT:
                    return PerformanceIndex.forClient(client, calculationConverter, reportingPeriod, warnings);

                case CLIENT_PRETAX:
                    return PerformanceIndex.forClient(new WithoutTaxesFilter().filter(client), calculationConverter,
                                    reportingPeriod, warnings);

                case SECURITY:
                    return PerformanceIndex.forInvestment(client, calculationConverter, (Security) series.getInstance(),
                                    reportingPeriod, warnings);

                case SECURITY_BENCHMARK:
                    return PerformanceIndex.forSecurity(
                                    lookup(new DataSeries(DataSeries.Type.CLIENT, null, null, null), reportingPeriod,
                                                    calculationConverter.getTermCurrency(), strict),
                                    (Security) series.getInstance());

                case PORTFOLIO:
                    return PerformanceIndex.forPortfolio(client, calculationConverter, (Portfolio) series.getInstance(),
                                    reportingPeriod, warnings);

                case PORTFOLIO_PRETAX:
                    return calculatePortfolioPretax(series, reportingPeriod, calculationConverter, warnings);

                case PORTFOLIO_PLUS_ACCOUNT:
                    return PerformanceIndex.forPortfolioPlusAccount(client, calculationConverter,
                                    (Portfolio) series.getInstance(), reportingPeriod, warnings);

                case PORTFOLIO_PLUS_ACCOUNT_PRETAX:
                    return calculatePortfolioPlusAccountPretax(series, reportingPeriod, calculationConverter,
                                    warnings);

                case ACCOUNT:
                    Account account = (Account) series.getInstance();
                    return PerformanceIndex.forAccount(client, calculationConverter, account, reportingPeriod,
                                    warnings);

                case ACCOUNT_PRETAX:
                    return calculateAccountPretax(series, reportingPeriod, calculationConverter, warnings);

                case CLASSIFICATION:
                    Classification classification = (Classification) series.getInstance();
                    return PerformanceIndex.forClassification(client, calculationConverter, classification,
                                    reportingPeriod, warnings);

                case CLIENT_FILTER:
                    ClientFilterMenu.Item item = (ClientFilterMenu.Item) series.getInstance();
                    return PerformanceIndex.forClient(item.getFilter().filter(client), calculationConverter,
                                    reportingPeriod, warnings);

                case CLIENT_FILTER_PRETAX:
                    ClientFilterMenu.Item pretax = (ClientFilterMenu.Item) series.getInstance();
                    return PerformanceIndex.forClient(
                                    new WithoutTaxesFilter().filter(pretax.getFilter().filter(client)),
                                    calculationConverter, reportingPeriod, warnings);

                case DERIVED_DATA_SERIES:
                    // redirect to the #lookup method to use the cached data, if
                    // available
                    var derivedDataSeries = (DerivedDataSeries) series.getInstance();
                    return lookup(derivedDataSeries.getBaseDataSeries(), reportingPeriod, currencySelection, strict);

                default:
                    throw new IllegalArgumentException(series.getType().name());
            }
        }
        finally
        {
            if (!warnings.isEmpty())
                PortfolioPlugin.log(warnings);
        }
    }

    private PerformanceIndex calculatePortfolioPretax(DataSeries series, Interval reportingPeriod,
                    CurrencyConverter calculationConverter, List<Exception> warnings)
    {
        Client filteredClient = new WithoutTaxesFilter().filter(client);
        Portfolio portfolio = filteredClient.getPortfolios().stream()
                        .filter(p -> ((ReadOnlyPortfolio) p).getSource().equals(series.getInstance())).findAny()
                        .orElseThrow(IllegalArgumentException::new);

        return PerformanceIndex.forPortfolio(filteredClient, calculationConverter, portfolio, reportingPeriod,
                        warnings);
    }

    private PerformanceIndex calculatePortfolioPlusAccountPretax(DataSeries series, Interval reportingPeriod,
                    CurrencyConverter calculationConverter, List<Exception> warnings)
    {
        Client filteredClient = new WithoutTaxesFilter().filter(client);
        Portfolio portfolio = filteredClient.getPortfolios().stream()
                        .filter(p -> ((ReadOnlyPortfolio) p).getSource().equals(series.getInstance())).findAny()
                        .orElseThrow(IllegalArgumentException::new);

        return PerformanceIndex.forPortfolioPlusAccount(client, calculationConverter, portfolio, reportingPeriod,
                        warnings);
    }

    private PerformanceIndex calculateAccountPretax(DataSeries series, Interval reportingPeriod,
                    CurrencyConverter calculationConverter, List<Exception> warnings)
    {
        Client filteredClient = new WithoutTaxesFilter().filter(client);
        Account account = filteredClient.getAccounts().stream()
                        .filter(a -> ((ReadOnlyAccount) a).getSource().equals(series.getInstance())).findAny()
                        .orElseThrow(IllegalArgumentException::new);

        return PerformanceIndex.forAccount(client, calculationConverter, account, reportingPeriod, warnings);
    }
}
