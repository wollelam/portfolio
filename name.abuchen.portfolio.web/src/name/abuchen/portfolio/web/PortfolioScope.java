package name.abuchen.portfolio.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ConfigurationSet.WellKnownConfigurationSets;
import name.abuchen.portfolio.model.InvestmentVehicle;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.snapshot.filter.ClientClassificationFilter;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyAccount;

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
    private final Map<String, Map<InvestmentVehicle, Integer>> classifications = new LinkedHashMap<>();

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
        client.getTaxonomies().stream().filter(t -> t.getName() != null
                        && t.getName().toLowerCase(java.util.Locale.ROOT).startsWith("asset class"))
                        .forEach(taxonomy -> {
            for (var classification : taxonomy.getAllClassifications())
            {
                if (classification == taxonomy.getRoot() || Classification.UNASSIGNED_ID.equals(classification.getId()))
                    continue;
                var path = new ArrayList<String>();
                for (var node = classification; node != null && node != taxonomy.getRoot(); node = node.getParent())
                    path.addFirst(node.getName());
                var label = String.join(" / ", path);
                var id = "t-" + UUID.nameUUIDFromBytes((taxonomy.getName().toLowerCase(java.util.Locale.ROOT) + "/" + label)
                                .getBytes(StandardCharsets.UTF_8));
                var members = classifications.computeIfAbsent(id, ignored -> new HashMap<>());
                for (var assignment : classification.flattenAssignments().getAssignments())
                {
                    var vehicle = assignment.getInvestmentVehicle();
                    if (vehicle instanceof Security || vehicle instanceof Account)
                        members.merge(vehicle, assignment.getWeight(), Math::max);
                }
                if (!members.isEmpty() && options.stream().noneMatch(o -> o.id().equals(id)))
                    options.add(new Option(id, label, taxonomy.getName()));
            }
        });
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
            if (!groups.containsKey(id) && !classifications.containsKey(id))
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
        excluded.stream().filter(groups::containsKey).forEach(id -> groups.get(id)
                        .forEach((element, weight) -> removed.merge(element, weight, Math::max)));
        var portfolios = client.getPortfolios().stream()
                        .filter(p -> removed.getOrDefault(p, 0) < Classification.ONE_HUNDRED_PERCENT).toList();
        var accounts = client.getAccounts().stream()
                        .filter(a -> removed.getOrDefault(a, 0) < Classification.ONE_HUNDRED_PERCENT).toList();
        var remainingWeights = new HashMap<Object, Integer>();
        removed.forEach((element, weight) -> {
            if (weight < Classification.ONE_HUNDRED_PERCENT)
                remainingWeights.put(element, Classification.ONE_HUNDRED_PERCENT - weight);
        });
        var filtered = new PortfolioClientFilter(portfolios, accounts, remainingWeights).filter(client);
        var taxonomyIds = excluded.stream().filter(classifications::containsKey).toList();
        if (taxonomyIds.isEmpty())
            return filtered;
        var excludedVehicles = new HashMap<InvestmentVehicle, Integer>();
        taxonomyIds.forEach(id -> classifications.get(id).forEach((vehicle, weight) ->
                        excludedVehicles.merge(vehicle, weight, Math::max)));
        var kept = new Classification("asset-class-remainder", "Remaining asset classes");
        for (var security : filtered.getSecurities())
        {
            int weight = Classification.ONE_HUNDRED_PERCENT
                            - Math.min(Classification.ONE_HUNDRED_PERCENT, excludedVehicles.getOrDefault(security, 0));
            if (weight > 0)
                kept.addAssignment(new Classification.Assignment(security, weight));
        }
        for (var account : filtered.getAccounts())
        {
            var original = ReadOnlyAccount.unwrap(account);
            int weight = Classification.ONE_HUNDRED_PERCENT
                            - Math.min(Classification.ONE_HUNDRED_PERCENT, excludedVehicles.getOrDefault(original, 0));
            if (weight > 0)
                kept.addAssignment(new Classification.Assignment(original, weight));
        }
        return new ClientClassificationFilter(kept).filter(filtered);
    }
}
