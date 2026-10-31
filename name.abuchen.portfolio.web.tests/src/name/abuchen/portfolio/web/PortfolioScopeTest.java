package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.ConfigurationSet.Configuration;
import name.abuchen.portfolio.model.ConfigurationSet.WellKnownConfigurationSets;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class PortfolioScopeTest
{
    private final LocalDate today = LocalDate.of(2026, 10, 23);
    private final Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private PortfolioSource.LoadedPortfolio fixture()
    {
        var loaded = DemoPortfolio.create(today);
        var account = new Account("Pension cash");
        account.setCurrencyCode("EUR");
        loaded.client().addAccount(account);
        var portfolio = new Portfolio("Pension securities");
        portfolio.setReferenceAccount(account);
        loaded.client().addPortfolio(portfolio);
        account.addTransaction(new AccountTransaction(today.minusDays(40).atTime(9, 0), "EUR",
                        Values.Amount.factorize(50000), null, AccountTransaction.Type.DEPOSIT));
        var purchase = new BuySellEntry(portfolio, account);
        purchase.setType(PortfolioTransaction.Type.BUY);
        purchase.setDate(today.minusDays(40).atTime(12, 0));
        purchase.setSecurity(loaded.client().getSecurities().getFirst());
        purchase.setShares(Values.Share.factorize(100));
        purchase.setMonetaryAmount(Money.of("EUR", Values.Amount.factorize(10000)));
        purchase.insert();
        var config = loaded.client().getSettings().getConfigurationSet(WellKnownConfigurationSets.CLIENT_FILTER_DEFINITIONS);
        config.add(new Configuration("pensions", "Pensions", portfolio.getUUID() + "," + account.getUUID()));
        config.add(new Configuration("half-pensions", "Half pension ownership",
                        portfolio.getUUID() + ":" + Classification.ONE_HUNDRED_PERCENT / 2 + ","
                                        + account.getUUID() + ":" + Classification.ONE_HUNDRED_PERCENT / 2));
        return loaded;
    }

    @Test
    public void assetClassPensionCanBeExcludedWithoutRemovingOtherAssets()
    {
        var loaded = DemoPortfolio.create(today);
        var pensionCash = new Account("Pension cash");
        pensionCash.setCurrencyCode("EUR");
        loaded.client().addAccount(pensionCash);
        var pensionPortfolio = new Portfolio("Pension investments");
        pensionPortfolio.setReferenceAccount(pensionCash);
        loaded.client().addPortfolio(pensionPortfolio);
        pensionCash.addTransaction(new AccountTransaction(today.minusDays(30).atTime(9, 0), "EUR",
                        Values.Amount.factorize(1000), null, AccountTransaction.Type.DEPOSIT));
        var pensionSecurity = new Security("Pension security", "EUR");
        pensionSecurity.addPrice(new SecurityPrice(today.minusDays(30), Values.Quote.factorize(10)));
        pensionSecurity.addPrice(new SecurityPrice(today, Values.Quote.factorize(12)));
        loaded.client().addSecurity(pensionSecurity);
        var purchase = new BuySellEntry(pensionPortfolio, pensionCash);
        purchase.setType(PortfolioTransaction.Type.BUY);
        purchase.setDate(today.minusDays(30).atTime(12, 0));
        purchase.setSecurity(pensionSecurity);
        purchase.setShares(Values.Share.factorize(50));
        purchase.setMonetaryAmount(Money.of("EUR", Values.Amount.factorize(500)));
        purchase.insert();
        var taxonomy = new Taxonomy("Asset Classes");
        var root = new Classification("asset-class-root", "Asset class");
        var pension = new Classification(root, "pension", "Pension");
        pension.addAssignment(new Classification.Assignment(pensionCash));
        pension.addAssignment(new Classification.Assignment(pensionSecurity));
        root.addChild(pension);
        taxonomy.setRootNode(root);
        loaded.client().addTaxonomy(taxonomy);

        var service = new DashboardService(loaded, clock, List.of());
        var before = service.dashboard(today.minusYears(1), today);
        var option = before.scope().options().stream()
                        .filter(o -> o.name().equals("Pension") && o.type().equals("Asset Classes"))
                        .findFirst().orElseThrow();
        var after = service.excluding(option.id()).dashboard(today.minusYears(1), today);
        var nonPension = new DashboardService(DemoPortfolio.create(today), clock, List.of())
                        .dashboard(today.minusYears(1), today);
        assertEquals(nonPension.totalValue(), after.totalValue());
        assertTrue(after.holdings().stream().noneMatch(h -> pensionSecurity.getUUID().equals(h.uuid())));
        assertEquals(before.totalValue(), service.dashboard(today.minusYears(1), today).totalValue());
    }

    @Test
    public void excludingSavedPensionsRecalculatesEverythingAndKeepsOtherHoldingsOfTheSameSecurity()
    {
        var source = fixture();
        var service = new DashboardService(source, clock, List.of());
        var before = service.dashboard(today.minusYears(1), today);
        var filtered = service.excluding("g-pensions").dashboard(today.minusYears(1), today);
        var expected = new DashboardService(DemoPortfolio.create(today), clock, List.of()).dashboard(today.minusYears(1), today);
        assertEquals(expected.totalValue(), filtered.totalValue());
        assertEquals(before.benchmarks(), filtered.benchmarks());
        assertEquals(expected.period(), filtered.period());
        assertEquals(expected.day(), filtered.day());
        assertEquals(expected.week(), filtered.week());
        assertEquals(expected.series(), filtered.series());
        assertEquals(expected.dividends().size(), filtered.dividends().size());
        assertEquals(Values.Share.factorize(700), filtered.holdings().stream().filter(h -> h.uuid() != null).findFirst().orElseThrow().shares());
        assertEquals(before.totalValue(), service.dashboard(today.minusYears(1), today).totalValue());
        assertEquals(2, source.client().getPortfolios().size());
        assertEquals(2, source.client().getAccounts().size());
        assertTrue(filtered.scope().options().stream().anyMatch(option -> option.name().equals("Pensions")));
        var overlap = "g-pensions,a-" + source.client().getAccounts().getLast().getUUID();
        assertEquals(filtered.totalValue(), service.excluding(overlap).dashboard(today.minusYears(1), today).totalValue());
        var all = String.join(",", before.scope().options().stream().map(PortfolioScope.Option::id).toList());
        var empty = service.excluding(all).dashboard(today.minusYears(1), today);
        assertEquals(0, empty.totalValue());
        assertEquals(0, empty.period().gain());
        assertTrue(empty.holdings().isEmpty());
    }

    @Test
    public void weightedGroupsExcludeOnlyTheConfiguredOwnershipShare()
    {
        var service = new DashboardService(fixture(), clock, List.of());
        var all = service.dashboard(today.minusMonths(1), today);
        var none = service.excluding("g-pensions").dashboard(today.minusMonths(1), today);
        var half = service.excluding("g-half-pensions").dashboard(today.minusMonths(1), today);
        assertEquals((all.totalValue() + none.totalValue()) / 2d, half.totalValue(), 1d);
        assertEquals(none.totalValue(), service.excluding("g-pensions,g-half-pensions").dashboard(today.minusMonths(1), today).totalValue());
    }

    @Test
    public void filteringRetainsTheOriginalCurrencyConversionAndBenchmarkContext()
    {
        var loaded = fixture();
        var foreign = new Account("Foreign cash");
        foreign.setCurrencyCode("USD");
        foreign.addTransaction(new AccountTransaction(today.minusDays(5).atStartOfDay(), "USD", 20000,
                        null, AccountTransaction.Type.DEPOSIT));
        loaded.client().addAccount(foreign);
        var fx = new Security("Stored EUR/USD", "EUR");
        fx.setTargetCurrencyCode("USD");
        fx.addPrice(new SecurityPrice(today.minusYears(2), Values.Quote.factorize(2)));
        loaded.client().addSecurity(fx);
        var service = new DashboardService(loaded, clock, List.of());
        var before = service.dashboard(today.minusMonths(1), today);
        var filtered = service.excluding("g-pensions").dashboard(today.minusMonths(1), today);
        var beforeCash = before.holdings().stream().filter(h -> foreign.getUUID().equals(h.id())).findFirst().orElseThrow();
        var afterCash = filtered.holdings().stream().filter(h -> foreign.getUUID().equals(h.id())).findFirst().orElseThrow();
        assertEquals(beforeCash.value(), afterCash.value());
        assertEquals(before.benchmarks(), filtered.benchmarks());
    }

    @Test
    public void bufferedAllocationFramesMatchIndividualNativeValuationsAcrossTrades()
    {
        var root = new DashboardService(fixture(), clock, List.of());
        for (var exclusions : List.of("", "g-pensions"))
        {
            var service = root.excluding(exclusions);
            var frames = service.allocationFrames(today.minusDays(42), today.minusDays(38));
            assertEquals(5, frames.frames().size());
            for (var frame : frames.frames())
            {
                var date = LocalDate.parse(frame.date());
                var expected = service.dashboard(date.minusDays(1), date);
                assertEquals(expected.totalValue(), frame.totalValue());
                assertEquals(expected.holdings(), frame.holdings());
            }
            assertEquals(exclusions.isEmpty() ? List.of() : List.of(exclusions), frames.scope().excluded());
        }
    }

    @Test
    public void scopeAndFrameQueriesAreValidatedByTheProtectedTransport() throws Exception
    {
        var service = new DashboardService(fixture(), clock, List.of());
        try (var server = new WebServer(new InetSocketAddress("127.0.0.1", 0), service);
                        var client = HttpClient.newHttpClient())
        {
            server.start();
            var base = "http://127.0.0.1:" + server.port() + "/api/dashboard";
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "?exclude=g-pensions&frames=true&from="
                            + today.minusDays(2) + "&to=" + today)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(3, JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("frames").size());
            for (var query : List.of("?exclude=unknown", "?frames=false", "?exclude=g-pensions&exclude=g-pensions",
                            "?frames=true&from=" + today.minusDays(93) + "&to=" + today,
                            "?frames=true&from=" + today + "&to=" + today.plusDays(1)))
                assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + query)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
