package name.abuchen.portfolio.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ConfigurationSet.WellKnownConfigurationSets;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;

/** Exclusions reuse native account/portfolio groups and native transaction filtering. */
@SuppressWarnings("nls")
final class PortfolioScope
{
    record Option(String id, String name, String type)
    {
    }

    record Selection(List<Option> options, List<String> excluded)
    {
    }

    final Client client;
    private final List<Option> options = new ArrayList<>();
    private final Map<String, Map<Object, Integer>> groups = new LinkedHashMap<>();

    PortfolioScope(Client client)
    {
        this.client = client;
        var elements = new HashMap<String, Object>();
        client.getPortfolios().forEach(p -> elements.put(p.getUUID(), p));
        client.getAccounts().forEach(a -> elements.put(a.getUUID(), a));
        // Reading an absent configuration must not create one in the source model.
        if (client.getSettings().hasConfigurationSet(WellKnownConfigurationSets.CLIENT_FILTER_DEFINITIONS.getKey()))
            client.getSettings().getConfigurationSet(WellKnownConfigurationSets.CLIENT_FILTER_DEFINITIONS)
                            .getConfigurations().forEach(config -> {
                                var members = new HashMap<Object, Integer>();
                                for (var token : config.getData().split(","))
                                {
                                    var parts = token.split(":", -1);
                                    var element = elements.get(parts[0]);
                                    if (element == null)
                                        continue;
                                    int weight = Classification.ONE_HUNDRED_PERCENT;
                                    if (parts.length == 2)
                                    {
                                        try
                                        {
                                            int parsed = Integer.parseInt(parts[1]);
                                            if (parsed > 0 && parsed <= weight)
                                                weight = parsed;
                                        }
                                        catch (NumberFormatException ignored)
                                        {
                                            // Match the desktop's lenient handling of legacy weights.
                                        }
                                    }
                                    members.put(element, weight);
                                }
                                if (!members.isEmpty())
                                    add("g-" + config.getUUID(), config.getName(), "Saved groups", members);
                            });
        client.getPortfolios().forEach(p -> add("p-" + p.getUUID(), p.getName(), "Securities accounts",
                        Map.of(p, Classification.ONE_HUNDRED_PERCENT)));
        client.getAccounts().forEach(a -> add("a-" + a.getUUID(), a.getName(), "Cash accounts",
                        Map.of(a, Classification.ONE_HUNDRED_PERCENT)));
    }

    private void add(String id, String name, String type, Map<Object, Integer> members)
    {
        if (groups.putIfAbsent(id, Map.copyOf(members)) == null)
            options.add(new Option(id, name, type));
    }

    List<String> parse(String value)
    {
        if (value == null || value.isEmpty())
            return List.of();
        if (value.length() > 16000)
            throw new IllegalArgumentException("Too many excluded groups");
        var ids = new TreeSet<String>();
        for (var id : value.split(",", -1))
        {
            if (!groups.containsKey(id))
                throw new IllegalArgumentException("Unknown excluded group");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    Selection selection(List<String> excluded)
    {
        return new Selection(List.copyOf(options), excluded);
    }

    Client filter(List<String> excluded)
    {
        var removed = new HashMap<Object, Integer>();
        excluded.forEach(id -> groups.get(id).forEach((element, weight) -> removed.merge(element, weight, Math::max)));
        var portfolios = client.getPortfolios().stream()
                        .filter(p -> removed.getOrDefault(p, 0) < Classification.ONE_HUNDRED_PERCENT).toList();
        var accounts = client.getAccounts().stream()
                        .filter(a -> removed.getOrDefault(a, 0) < Classification.ONE_HUNDRED_PERCENT).toList();
        var remainingWeights = new HashMap<Object, Integer>();
        removed.forEach((element, weight) -> {
            if (weight < Classification.ONE_HUNDRED_PERCENT)
                remainingWeights.put(element, Classification.ONE_HUNDRED_PERCENT - weight);
        });
        return new PortfolioClientFilter(portfolios, accounts, remainingWeights).filter(client);
    }
}
