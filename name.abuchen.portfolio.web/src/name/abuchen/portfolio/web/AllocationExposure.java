package name.abuchen.portfolio.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.InvestmentVehicle;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.filter.ReadOnlyAccount;

@SuppressWarnings("nls")
final class AllocationExposure
{
    record Slice(String group, String label, String name, String uuid, long value)
    {
    }

    record Target(String group, String label, double weight)
    {
    }

    record Targets(List<Target> categories, String message)
    {
    }

    private record Category(String id, String name)
    {
    }

    private static final Category UNCLASSIFIED = new Category("unclassified", "Unclassified");
    private static final int FULL_WEIGHT = Classification.ONE_HUNDRED_PERCENT;

    private AllocationExposure()
    {
    }

    private static Optional<Taxonomy> assetClassTaxonomy(PortfolioScope scope)
    {
        return scope.client.getTaxonomies().stream().filter(PortfolioScope::isAssetClass).findFirst();
    }

    static Targets targets(PortfolioScope scope)
    {
        var classes = assetClassTaxonomy(scope).map(taxonomy -> taxonomy.getRoot().getChildren().stream()
                        .filter(classification -> !Classification.UNASSIGNED_ID.equals(classification.getId())).toList())
                        .orElse(List.of());
        if (classes.isEmpty() || classes.stream().noneMatch(classification -> classification.getWeight() > 0))
            return new Targets(List.of(), "No Asset Classes targets are saved in this portfolio.");
        if (classes.stream().anyMatch(classification -> classification.getWeight() < 0)
                        || classes.stream().mapToInt(Classification::getWeight).sum() != FULL_WEIGHT
                        || classes.stream().map(Classification::getName).distinct().count() != classes.size())
            return new Targets(List.of(), "Asset Classes targets must total 100% and use distinct class names.");
        return new Targets(classes.stream().sorted(Comparator.comparing(Classification::getName))
                        .map(classification -> new Target(category(classification).id(), classification.getName(),
                                        classification.getWeight() / (double) FULL_WEIGHT)).toList(), null);
    }

    private static Category category(Classification classification)
    {
        return new Category("class:" + classification.getName(), classification.getName());
    }

    static List<Slice> byAssetClass(ClientSnapshot snapshot, PortfolioScope scope, List<String> exclusions)
    {
        var assignments = new LinkedHashMap<String, Map<Category, Integer>>();
        assetClassTaxonomy(scope).ifPresent(taxonomy -> {
            for (var classification : taxonomy.getRoot().getChildren())
            {
                collectAssignments(taxonomy, classification, category(classification), exclusions, assignments);
            }
        });

        var excludedWeights = new LinkedHashMap<String, Integer>();
        scope.excludedClassWeights(exclusions).forEach((vehicle, weight) -> excludedWeights.put(vehicle.getUUID(), weight));
        var slices = new ArrayList<Slice>();
        snapshot.getAssetPositions().forEach(position -> {
            var vehicle = position.getInvestmentVehicle();
            if (vehicle instanceof Account account)
                vehicle = ReadOnlyAccount.unwrap(account);
            var weights = assignments.getOrDefault(vehicle.getUUID(), Map.of());
            int assigned = weights.values().stream().mapToInt(Integer::intValue).sum();
            int retained = FULL_WEIGHT - Math.min(FULL_WEIGHT, excludedWeights.getOrDefault(vehicle.getUUID(), 0));
            int denominator = Math.max(retained, assigned);
            long value = position.getValuation().getAmount();
            long distributed = 0;
            int cumulative = 0;
            for (var entry : weights.entrySet())
            {
                cumulative += entry.getValue();
                long throughCategory = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(cumulative))
                                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP).longValueExact();
                add(slices, entry.getKey(), vehicle, throughCategory - distributed);
                distributed = throughCategory;
            }
            add(slices, UNCLASSIFIED, vehicle, value - distributed);
        });
        return List.copyOf(slices);
    }

    private static void collectAssignments(Taxonomy taxonomy, Classification classification, Category category,
                    List<String> exclusions, Map<String, Map<Category, Integer>> assignments)
    {
        if (Classification.UNASSIGNED_ID.equals(classification.getId())
                        || exclusions.contains(PortfolioScope.classificationId(taxonomy, classification)))
            return;
        for (var assignment : classification.getAssignments())
        {
            if (assignment.getWeight() > 0)
                assignments.computeIfAbsent(assignment.getInvestmentVehicle().getUUID(), ignored -> new LinkedHashMap<>())
                                .merge(category, assignment.getWeight(), Integer::sum);
        }
        for (var child : classification.getChildren())
            collectAssignments(taxonomy, child, category, exclusions, assignments);
    }

    static List<Slice> byAccount(ClientSnapshot snapshot)
    {
        var slices = new ArrayList<Slice>();
        for (var account : snapshot.getAccounts())
        {
            var vehicle = ReadOnlyAccount.unwrap(account.getAccount());
            add(slices, new Category("cash:" + vehicle.getUUID(), vehicle.getName()), vehicle,
                            account.getFunds().getAmount());
        }

        var rate = snapshot.getCurrencyConverter().at(snapshot.getTime());
        var bySecurity = new LinkedHashMap<Security, List<Slice>>();
        for (var portfolio : snapshot.getPortfolios())
        {
            var owner = portfolio.unwrapPortfolio();
            var group = "securities:" + owner.getUUID();
            for (var position : portfolio.getPositions())
            {
                var security = position.getSecurity();
                var value = position.calculateValue().with(rate).getAmount();
                bySecurity.computeIfAbsent(security, ignored -> new ArrayList<>())
                                .add(new Slice(group, owner.getName(), security.getName(), security.getUUID(), value));
            }
        }
        var joint = snapshot.getJointPortfolio().getPositionsBySecurity();
        bySecurity.forEach((security, parts) -> {
            var position = joint.get(security);
            long total = position == null ? 0 : position.calculateValue().with(rate).getAmount();
            long difference = total - parts.stream().mapToLong(Slice::value).sum();
            // Use the same joint valuation as Holdings, including its quote fallback and conversion rounding.
            var largest = parts.stream().max(Comparator.comparingLong(slice -> Math.abs(slice.value()))).orElseThrow();
            for (var part : parts)
            {
                long value = part.value() + (part == largest ? difference : 0);
                if (value != 0)
                    slices.add(new Slice(part.group(), part.label(), part.name(), part.uuid(), value));
            }
        });
        return List.copyOf(slices);
    }

    private static void add(List<Slice> slices, Category category, InvestmentVehicle vehicle, long value)
    {
        if (value != 0)
            slices.add(new Slice(category.id(), category.name(), vehicle.getName(),
                            vehicle instanceof Security security ? security.getUUID() : null, value));
    }
}
