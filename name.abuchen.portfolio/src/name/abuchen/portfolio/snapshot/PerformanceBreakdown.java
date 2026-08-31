package name.abuchen.portfolio.snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.MutableMoney;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.Category;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.CategoryType;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.Position;

/**
 * A presentation-independent, additive explanation of a client's performance
 * over a reporting period.
 */
public final class PerformanceBreakdown
{
    public enum EntryKind
    {
        START, CHANGE, SUBTOTAL, TOTAL
    }

    /**
     * The stable semantic meaning of an entry. Labels are localized and must
     * not be used for identifying entries.
     */
    public enum EntryType
    {
        INITIAL_VALUE, CAPITAL_GAINS, REALIZED_CAPITAL_GAINS, EARNINGS, FEES, TAXES, CURRENCY_GAINS, TRANSFERS,
        FINAL_VALUE, SECURITY, OTHER, TOTAL_PERFORMANCE
    }

    public static final class Entry
    {
        private final EntryKind kind;
        private final EntryType type;
        private final String label;
        private final Money amount;
        private final Security security;
        private final Category sourceCategory;
        private final Position sourcePosition;

        private Entry(EntryKind kind, EntryType type, String label, Money amount, Security security,
                        Category sourceCategory, Position sourcePosition)
        {
            this.kind = kind;
            this.type = type;
            this.label = label;
            this.amount = amount;
            this.security = security;
            this.sourceCategory = sourceCategory;
            this.sourcePosition = sourcePosition;
        }

        public EntryKind getKind()
        {
            return kind;
        }

        public EntryType getType()
        {
            return type;
        }

        public String getLabel()
        {
            return label;
        }

        /**
         * The signed amount represented by this entry. For start and total
         * entries, this is the respective total value rather than a change.
         */
        public Money getAmount()
        {
            return amount;
        }

        /**
         * Returns the security represented by this entry, if any.
         */
        public Security getSecurity()
        {
            return security;
        }

        /**
         * Returns the source performance category, if this entry has one.
         */
        public Category getSourceCategory()
        {
            return sourceCategory;
        }

        /**
         * Returns the source position, if this entry represents one position.
         */
        public Position getSourcePosition()
        {
            return sourcePosition;
        }
    }

    private static final EnumSet<CategoryType> PERFORMANCE_CATEGORIES = EnumSet.of(CategoryType.CAPITAL_GAINS,
                    CategoryType.REALIZED_CAPITAL_GAINS, CategoryType.EARNINGS, CategoryType.FEES,
                    CategoryType.TAXES);

    private final List<Entry> entries;

    private PerformanceBreakdown(List<Entry> entries)
    {
        this.entries = List.copyOf(entries);
    }

    /**
     * Creates a bridge from the initial to the final portfolio value. Transfers
     * are included because this mode reconciles portfolio values.
     */
    public static PerformanceBreakdown createCalculation(ClientPerformanceSnapshot snapshot)
    {
        List<Entry> entries = new ArrayList<>();

        addCategory(entries, snapshot, CategoryType.INITIAL_VALUE, EntryKind.START);
        addCategory(entries, snapshot, CategoryType.CAPITAL_GAINS, EntryKind.CHANGE);
        addCategory(entries, snapshot, CategoryType.REALIZED_CAPITAL_GAINS, EntryKind.CHANGE);
        addCategory(entries, snapshot, CategoryType.EARNINGS, EntryKind.CHANGE);
        addCategory(entries, snapshot, CategoryType.FEES, EntryKind.CHANGE);
        addCategory(entries, snapshot, CategoryType.TAXES, EntryKind.CHANGE);
        addCategory(entries, snapshot, CategoryType.CURRENCY_GAINS, EntryKind.CHANGE);
        addCategory(entries, snapshot, CategoryType.TRANSFERS, EntryKind.CHANGE);

        // Values are rounded at several stages of the performance calculation,
        // especially when foreign currencies are involved. Keep a resulting
        // residual explicit so this bridge always arrives at the authoritative
        // final portfolio value.
        Money reconciled = snapshot.getCategoryByType(CategoryType.INITIAL_VALUE).getValuation();
        for (Entry entry : entries)
        {
            if (entry.kind == EntryKind.CHANGE)
                reconciled = reconciled.add(entry.amount);
        }

        Money residual = snapshot.getCategoryByType(CategoryType.FINAL_VALUE).getValuation().subtract(reconciled);
        if (!residual.isZero())
            entries.add(new Entry(EntryKind.CHANGE, EntryType.OTHER, Messages.LabelOtherCategory, residual, null,
                            null, null));

        addCategory(entries, snapshot, CategoryType.FINAL_VALUE, EntryKind.TOTAL);

        return new PerformanceBreakdown(entries);
    }

    /**
     * Creates an additive attribution of absolute performance by security.
     * Transfers are intentionally excluded because they are not performance.
     */
    public static PerformanceBreakdown createContributions(ClientPerformanceSnapshot snapshot)
    {
        String currencyCode = snapshot.getAbsoluteDelta().getCurrencyCode();
        Map<Security, MutableMoney> bySecurity = new LinkedHashMap<>();
        MutableMoney other = MutableMoney.of(currencyCode);

        for (CategoryType type : PERFORMANCE_CATEGORIES)
        {
            Category category = snapshot.getCategoryByType(type);
            MutableMoney positionsTotal = MutableMoney.of(currencyCode);

            for (Position position : category.getPositions())
            {
                Money amount = signed(type, position.getValue());
                positionsTotal.add(amount);

                if (position.getSecurity() != null)
                    bySecurity.computeIfAbsent(position.getSecurity(), s -> MutableMoney.of(currencyCode)).add(amount);
                else
                    other.add(amount);
            }

            // A category valuation remains authoritative. Normally it equals
            // the sum of its positions; keeping a possible difference in the
            // synthetic bucket preserves the reconciliation invariant.
            other.add(signed(type, category.getValuation()).subtract(positionsTotal.toMoney()));
        }

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(EntryKind.START, EntryType.INITIAL_VALUE, Messages.LabelStartValue,
                        Money.of(currencyCode, 0), null, null, null));

        bySecurity.entrySet().stream() //
                        .filter(entry -> !entry.getValue().isZero()) //
                        .map(entry -> new Entry(EntryKind.CHANGE, EntryType.SECURITY, entry.getKey().getName(),
                                        entry.getValue().toMoney(), entry.getKey(), null, null)) //
                        .sorted(Comparator.comparing(Entry::getLabel, String.CASE_INSENSITIVE_ORDER)) //
                        .forEach(entries::add);

        if (!other.isZero())
            entries.add(new Entry(EntryKind.CHANGE, EntryType.OTHER, Messages.LabelOtherCategory, other.toMoney(),
                            null, null, null));

        Category currencyGains = snapshot.getCategoryByType(CategoryType.CURRENCY_GAINS);
        if (!currencyGains.getValuation().isZero())
            entries.add(new Entry(EntryKind.CHANGE, EntryType.CURRENCY_GAINS, currencyGains.getLabel(),
                            currencyGains.getValuation(), null, currencyGains, null));

        entries.add(new Entry(EntryKind.TOTAL, EntryType.TOTAL_PERFORMANCE, Messages.LabelTotalPerformance,
                        snapshot.getAbsoluteDelta(), null, null, null));

        return new PerformanceBreakdown(entries);
    }

    private static void addCategory(List<Entry> entries, ClientPerformanceSnapshot snapshot, CategoryType type,
                    EntryKind kind)
    {
        Category category = snapshot.getCategoryByType(type);
        Money amount = signed(type, category.getValuation());
        if (kind == EntryKind.CHANGE && amount.isZero())
            return;

        entries.add(new Entry(kind, type(type), category.getLabel(), amount, null, category, null));
    }

    private static Money signed(CategoryType type, Money amount)
    {
        return type == CategoryType.FEES || type == CategoryType.TAXES ? amount.multiply(-1) : amount;
    }

    private static EntryType type(CategoryType type)
    {
        return switch (type)
        {
            case INITIAL_VALUE -> EntryType.INITIAL_VALUE;
            case CAPITAL_GAINS -> EntryType.CAPITAL_GAINS;
            case REALIZED_CAPITAL_GAINS -> EntryType.REALIZED_CAPITAL_GAINS;
            case EARNINGS -> EntryType.EARNINGS;
            case FEES -> EntryType.FEES;
            case TAXES -> EntryType.TAXES;
            case CURRENCY_GAINS -> EntryType.CURRENCY_GAINS;
            case TRANSFERS -> EntryType.TRANSFERS;
            case FINAL_VALUE -> EntryType.FINAL_VALUE;
        };
    }

    public List<Entry> getEntries()
    {
        return entries;
    }

    /**
     * Limits the number of instrument contributions while preserving the
     * breakdown's total. Contributions not among the largest absolute values
     * are added to the existing (or a new) {@link EntryType#OTHER} entry.
     * <p>
     * Calculation breakdowns do not contain {@link EntryType#SECURITY}
     * entries and are returned unchanged.
     */
    public PerformanceBreakdown limitContributions(int maximum)
    {
        if (maximum < 1)
            throw new IllegalArgumentException("maximum must be positive"); //$NON-NLS-1$

        var securities = entries.stream().filter(entry -> entry.type == EntryType.SECURITY).toList();
        if (securities.size() <= maximum)
            return this;

        Set<Entry> included = new HashSet<>(securities.stream()
                        .sorted(Comparator.<Entry>comparingLong(entry -> absolute(entry.amount.getAmount())).reversed()
                                        .thenComparing(Entry::getLabel, String.CASE_INSENSITIVE_ORDER))
                        .limit(maximum).toList());

        String currencyCode = securities.get(0).amount.getCurrencyCode();
        MutableMoney other = MutableMoney.of(currencyCode);
        List<Entry> limited = new ArrayList<>(entries.size() - securities.size() + maximum + 1);
        int otherIndex = -1;

        for (Entry entry : entries)
        {
            if (entry.type == EntryType.SECURITY && !included.contains(entry))
            {
                other.add(entry.amount);
                continue;
            }

            if (entry.type == EntryType.OTHER)
                otherIndex = limited.size();

            limited.add(entry);
        }

        if (!other.isZero())
        {
            if (otherIndex >= 0)
            {
                Entry existing = limited.get(otherIndex);
                limited.set(otherIndex, new Entry(existing.kind, existing.type, existing.label,
                                existing.amount.add(other.toMoney()), existing.security, existing.sourceCategory,
                                existing.sourcePosition));
            }
            else
            {
                int totalIndex = limited.size();
                for (int index = 0; index < limited.size(); index++)
                {
                    if (limited.get(index).kind == EntryKind.TOTAL)
                    {
                        totalIndex = index;
                        break;
                    }
                }
                limited.add(totalIndex, new Entry(EntryKind.CHANGE, EntryType.OTHER, Messages.LabelOtherCategory,
                                other.toMoney(), null, null, null));
            }
        }

        return new PerformanceBreakdown(limited);
    }

    private static long absolute(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    /**
     * Returns whether the start value plus all changes equals the total value.
     */
    public boolean isReconciled()
    {
        Entry start = entries.stream().filter(entry -> entry.kind == EntryKind.START).findFirst().orElse(null);
        Entry total = entries.stream().filter(entry -> entry.kind == EntryKind.TOTAL).reduce((first, second) -> second)
                        .orElse(null);

        if (start == null || total == null)
            return false;

        Money calculated = start.amount;
        for (Entry entry : entries)
        {
            if (entry.kind == EntryKind.CHANGE)
                calculated = calculated.add(entry.amount);
        }

        return calculated.equals(total.amount);
    }
}
