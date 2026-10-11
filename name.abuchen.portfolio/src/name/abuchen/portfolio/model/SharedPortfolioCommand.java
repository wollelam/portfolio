package name.abuchen.portfolio.model;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.money.Money;

/**
 * An immutable, append-only change request for a shared portfolio.
 * <p>
 * Commands deliberately contain business operations rather than a serialized
 * replacement portfolio. The owner applies a command once and publishes an
 * accepted event. Keeping the envelope independent of the portfolio file
 * format lets XML, compressed and encrypted local files use the same mailbox.
 */
public final class SharedPortfolioCommand
{
    public static final int PROTOCOL_VERSION = 1;

    /** A command operation supported by the first semantic sync slice. */
    public interface Operation
    {
        String operationType();

        JsonObject toJson();
    }

    /** Adds or confirms one historic or latest quote for an existing security. */
    public record AddQuote(String securityId, LocalDate date, long value, boolean latest, long high, long low,
                    long volume) implements Operation
    {
        public AddQuote
        {
            requireUuid(securityId);
            Objects.requireNonNull(date);
            if (!latest && (high != 0 || low != 0 || volume != 0))
                throw new IllegalArgumentException("Historic quotes cannot contain latest quote fields."); //$NON-NLS-1$
        }

        public AddQuote(String securityId, LocalDate date, long value)
        {
            this(securityId, date, value, false, 0, 0, 0);
        }

        public AddQuote(String securityId, LocalDate date, long value, long high, long low, long volume)
        {
            this(securityId, date, value, true, high, low, volume);
        }

        @Override
        public String operationType()
        {
            return "addQuote"; //$NON-NLS-1$
        }

        @Override
        public JsonObject toJson()
        {
            JsonObject json = new JsonObject();
            json.addProperty("type", operationType()); //$NON-NLS-1$
            json.addProperty("securityId", securityId); //$NON-NLS-1$
            json.addProperty("date", date.toString()); //$NON-NLS-1$
            json.addProperty("value", value);
            json.addProperty("latest", latest);
            if (latest)
            {
                json.addProperty("high", high);
                json.addProperty("low", low);
                json.addProperty("volume", volume);
            }
            return json;
        }
    }

    /** Adds one standalone account transaction to an existing account. */
    public record AddAccountTransaction(String transactionId, String accountId, LocalDateTime dateTime,
                    String currencyCode, long amount, String securityId, long shares, AccountTransaction.Type type,
                    String note, String source, LocalDateTime exDate, List<UnitData> units) implements Operation
    {
        public AddAccountTransaction
        {
            requireUuid(transactionId);
            requireUuid(accountId);
            Objects.requireNonNull(dateTime);
            requireCurrency(currencyCode);
            Objects.requireNonNull(type);
            if (securityId != null)
                requireUuid(securityId);
            units = copyUnits(units);
            if (type == AccountTransaction.Type.TRANSFER_IN || type == AccountTransaction.Type.TRANSFER_OUT)
                throw new IllegalArgumentException("Transfer transactions must be submitted as a linked transaction group."); //$NON-NLS-1$
        }

        public AddAccountTransaction(String transactionId, String accountId, LocalDateTime dateTime,
                        String currencyCode, long amount, String securityId, long shares, AccountTransaction.Type type,
                        String note, String source, LocalDateTime exDate)
        {
            this(transactionId, accountId, dateTime, currencyCode, amount, securityId, shares, type, note, source,
                            exDate, List.of());
        }

        @Override
        public String operationType()
        {
            return "addAccountTransaction"; //$NON-NLS-1$
        }

        @Override
        public JsonObject toJson()
        {
            JsonObject json = transactionJson(operationType(), transactionId, dateTime, currencyCode, amount, securityId,
                            shares, note, source, units);
            json.addProperty("accountId", accountId); //$NON-NLS-1$
            json.addProperty("transactionType", type.name()); //$NON-NLS-1$
            if (exDate != null)
                json.addProperty("exDate", exDate.toString()); //$NON-NLS-1$
            return json;
        }
    }

    /** Adds one standalone portfolio transaction to an existing portfolio. */
    public record AddPortfolioTransaction(String transactionId, String portfolioId, LocalDateTime dateTime,
                    String currencyCode, long amount, String securityId, long shares, PortfolioTransaction.Type type,
                    String note, String source, List<UnitData> units) implements Operation
    {
        public AddPortfolioTransaction
        {
            requireUuid(transactionId);
            requireUuid(portfolioId);
            Objects.requireNonNull(dateTime);
            requireCurrency(currencyCode);
            Objects.requireNonNull(type);
            if (securityId != null)
                requireUuid(securityId);
            units = copyUnits(units);
            if (type == PortfolioTransaction.Type.TRANSFER_IN || type == PortfolioTransaction.Type.TRANSFER_OUT)
                throw new IllegalArgumentException("Transfer transactions must be submitted as a linked transaction group."); //$NON-NLS-1$
        }

        public AddPortfolioTransaction(String transactionId, String portfolioId, LocalDateTime dateTime,
                        String currencyCode, long amount, String securityId, long shares, PortfolioTransaction.Type type,
                        String note, String source)
        {
            this(transactionId, portfolioId, dateTime, currencyCode, amount, securityId, shares, type, note, source,
                            List.of());
        }

        @Override
        public String operationType()
        {
            return "addPortfolioTransaction"; //$NON-NLS-1$
        }

        @Override
        public JsonObject toJson()
        {
            return transactionJson(operationType(), transactionId, dateTime, currencyCode, amount, securityId, shares, note,
                            source, units, "portfolioId", portfolioId, "transactionType", type.name()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /** A transaction unit, including optional foreign-currency information. */
    public record UnitData(Transaction.Unit.Type type, String currencyCode, long amount, String forexCurrencyCode,
                    Long forexAmount, BigDecimal exchangeRate)
    {
        public UnitData
        {
            Objects.requireNonNull(type);
            requireCurrency(currencyCode);
            boolean hasForex = forexCurrencyCode != null || forexAmount != null || exchangeRate != null;
            if (hasForex)
            {
                requireCurrency(forexCurrencyCode);
                Objects.requireNonNull(forexAmount);
                Objects.requireNonNull(exchangeRate);
            }
            if (type == Transaction.Unit.Type.GROSS_VALUE && !hasForex)
                throw new IllegalArgumentException("Gross-value units must include foreign-currency data."); //$NON-NLS-1$
        }

        public UnitData(Transaction.Unit.Type type, String currencyCode, long amount)
        {
            this(type, currencyCode, amount, null, null, null);
        }

        private Transaction.Unit toModelUnit()
        {
            Money amountValue = Money.of(currencyCode, amount);
            if (forexCurrencyCode == null)
                return new Transaction.Unit(type, amountValue);
            return new Transaction.Unit(type, amountValue, Money.of(forexCurrencyCode, forexAmount), exchangeRate);
        }

        private JsonObject toJson()
        {
            JsonObject json = new JsonObject();
            json.addProperty("type", type.name()); //$NON-NLS-1$
            json.addProperty("currencyCode", currencyCode); //$NON-NLS-1$
            json.addProperty("amount", amount);
            if (forexCurrencyCode != null)
            {
                json.addProperty("forexCurrencyCode", forexCurrencyCode); //$NON-NLS-1$
                json.addProperty("forexAmount", forexAmount);
                json.addProperty("exchangeRate", exchangeRate.toPlainString()); //$NON-NLS-1$
            }
            return json;
        }

        private static UnitData fromJson(JsonObject json)
        {
            String forexCurrency = string(json, "forexCurrencyCode", null); //$NON-NLS-1$
            Long forexAmount = json.has("forexAmount") ? requiredLong(json, "forexAmount") : null; //$NON-NLS-1$
            BigDecimal exchangeRate = json.has("exchangeRate")
                            ? new BigDecimal(requiredString(json, "exchangeRate")) : null; //$NON-NLS-1$
            return new UnitData(Transaction.Unit.Type.valueOf(requiredString(json, "type")), //$NON-NLS-1$
                            requiredString(json, "currencyCode"), requiredLong(json, "amount"), forexCurrency,
                            forexAmount, exchangeRate);
        }
    }

    public record ApplyResult(int appliedOperations, int noOpOperations)
    {
    }

    /** Raised when an operation cannot be safely applied to the current model. */
    public static final class ConflictException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public ConflictException(String message)
        {
            super(message);
        }
    }

    /** Raised when a local edit is not representable by the additive command slice. */
    public static final class UnsupportedChangeException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public UnsupportedChangeException(String message)
        {
            super(message);
        }
    }

    private final String id;
    private final String workspaceId;
    private final String parentRevision;
    private final String actorId;
    private final Instant createdAt;
    private final List<Operation> operations;

    public SharedPortfolioCommand(String id, String workspaceId, String parentRevision, String actorId,
                    Instant createdAt, List<? extends Operation> operations)
    {
        requireUuid(id);
        requireUuid(workspaceId);
        requireRevision(parentRevision);
        requireUuid(actorId);
        Objects.requireNonNull(createdAt);
        if (operations == null || operations.isEmpty())
            throw new IllegalArgumentException("A shared portfolio command must contain at least one operation."); //$NON-NLS-1$
        if (operations.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("A shared portfolio command cannot contain a null operation."); //$NON-NLS-1$
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentRevision = parentRevision;
        this.actorId = actorId;
        this.createdAt = createdAt;
        this.operations = List.copyOf(operations);
    }

    public static SharedPortfolioCommand create(String workspaceId, String actorId, String parentRevision,
                    List<? extends Operation> operations)
    {
        return new SharedPortfolioCommand(UUID.randomUUID().toString(), workspaceId, parentRevision, actorId,
                        Instant.now(), operations);
    }

    /**
     * Derives additive operations from two model snapshots. Existing records
     * must be unchanged; only new quotes and standalone transactions are
     * emitted. Callers can fall back to the complete-file submission when this
     * method reports an unsupported edit.
     */
    public static List<Operation> additiveChanges(Client base, Client current)
                    throws IOException, UnsupportedChangeException
    {
        Objects.requireNonNull(base);
        Objects.requireNonNull(current);
        Map<String, Security> baseSecurities = base.getSecurities().stream()
                        .collect(java.util.stream.Collectors.toMap(Security::getUUID, security -> security));
        Map<String, Account> baseAccounts = base.getAccounts().stream()
                        .collect(java.util.stream.Collectors.toMap(Account::getUUID, account -> account));
        Map<String, Portfolio> basePortfolios = base.getPortfolios().stream()
                        .collect(java.util.stream.Collectors.toMap(Portfolio::getUUID, portfolio -> portfolio));
        if (baseSecurities.size() != base.getSecurities().size() || baseAccounts.size() != base.getAccounts().size()
                        || basePortfolios.size() != base.getPortfolios().size())
            throw new UnsupportedChangeException("The base portfolio contains duplicate entity identifiers."); //$NON-NLS-1$

        List<Operation> operations = new ArrayList<>();
        for (Security security : current.getSecurities())
        {
            Security old = baseSecurities.get(security.getUUID());
            if (old == null)
                throw new UnsupportedChangeException("Adding securities is not yet supported."); //$NON-NLS-1$
            for (SecurityPrice price : security.getPrices())
            {
                SecurityPrice previous = old.getPrices().stream()
                                .filter(candidate -> candidate.getDate().equals(price.getDate())).findFirst()
                                .orElse(null);
                if (previous == null)
                    operations.add(new AddQuote(security.getUUID(), price.getDate(), price.getValue()));
                else if (previous.getValue() != price.getValue())
                    throw new UnsupportedChangeException("Updating an existing quote is not yet supported."); //$NON-NLS-1$
            }
            LatestSecurityPrice latest = security.getLatest();
            if (old.getLatest() == null && latest != null)
                operations.add(new AddQuote(security.getUUID(), latest.getDate(), latest.getValue(), latest.getHigh(),
                                latest.getLow(), latest.getVolume()));
            else if (!Objects.equals(old.getLatest(), latest))
                throw new UnsupportedChangeException("Updating an existing latest quote is not yet supported."); //$NON-NLS-1$
        }
        for (Account account : current.getAccounts())
        {
            Account old = baseAccounts.get(account.getUUID());
            if (old == null)
                throw new UnsupportedChangeException("Adding accounts is not yet supported."); //$NON-NLS-1$
            for (AccountTransaction transaction : account.getTransactions())
            {
                AccountTransaction previous = old.getTransactions().stream()
                                .filter(candidate -> candidate.getUUID().equals(transaction.getUUID())).findFirst()
                                .orElse(null);
                if (previous == null)
                    operations.add(accountOperation(account, transaction));
                // Existing transaction updates are detected by the canonical
                // comparison below.
            }
        }
        for (Portfolio portfolio : current.getPortfolios())
        {
            Portfolio old = basePortfolios.get(portfolio.getUUID());
            if (old == null)
                throw new UnsupportedChangeException("Adding portfolios is not yet supported."); //$NON-NLS-1$
            for (PortfolioTransaction transaction : portfolio.getTransactions())
            {
                PortfolioTransaction previous = old.getTransactions().stream()
                                .filter(candidate -> candidate.getUUID().equals(transaction.getUUID())).findFirst()
                                .orElse(null);
                if (previous == null)
                    operations.add(portfolioOperation(portfolio, transaction));
                // Existing transaction updates are detected by the canonical
                // comparison below.
            }
        }

        // Detect deletions and edits to fields outside the supported additions.
        Client stripped = ClientFactory.duplicate(current);
        for (Account account : stripped.getAccounts())
            account.getTransactions().removeIf(transaction -> !containsTransaction(base, transaction.getUUID()));
        for (Portfolio portfolio : stripped.getPortfolios())
            portfolio.getTransactions().removeIf(transaction -> !containsTransaction(base, transaction.getUUID()));
        for (Security security : stripped.getSecurities())
        {
            Security old = baseSecurities.get(security.getUUID());
            List<SecurityPrice> added = security.getPrices().stream()
                            .filter(price -> old.getPrices().stream()
                                            .noneMatch(previous -> previous.getDate().equals(price.getDate())))
                            .toList();
            added.forEach(security::removePrice);
            if (old.getLatest() == null && security.getLatest() != null)
                security.setLatest(null);
        }
        stripped.getSecurities().stream().filter(security -> !baseSecurities.containsKey(security.getUUID()))
                        .toList().forEach(stripped::removeSecurity);
        stripped.getAccounts().stream().filter(account -> !baseAccounts.containsKey(account.getUUID()))
                        .toList().forEach(stripped::removeAccount);
        stripped.getPortfolios().stream().filter(portfolio -> !basePortfolios.containsKey(portfolio.getUUID()))
                        .toList().forEach(stripped::removePortfolio);
        if (!java.util.Arrays.equals(canonicalBytes(base), canonicalBytes(stripped)))
            throw new UnsupportedChangeException("The edit contains updates or deletions; use a complete-file submission."); //$NON-NLS-1$

        return List.copyOf(operations);
    }

    private static Operation accountOperation(Account account, AccountTransaction transaction)
                    throws UnsupportedChangeException
    {
        if (transaction.getCrossEntry() != null)
            throw new UnsupportedChangeException("Linked transactions must be submitted as one group."); //$NON-NLS-1$
        try
        {
            return new AddAccountTransaction(transaction.getUUID(), account.getUUID(), transaction.getDateTime(),
                            transaction.getCurrencyCode(), transaction.getAmount(),
                            transaction.getSecurity() == null ? null : transaction.getSecurity().getUUID(),
                            transaction.getShares(), transaction.getType(), transaction.getNote(), transaction.getSource(),
                            transaction.getExDate(), unitData(transaction));
        }
        catch (RuntimeException e)
        {
            throw new UnsupportedChangeException("Account transaction cannot be represented as an additive command: "
                            + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static Operation portfolioOperation(Portfolio portfolio, PortfolioTransaction transaction)
                    throws UnsupportedChangeException
    {
        if (transaction.getCrossEntry() != null)
            throw new UnsupportedChangeException("Linked transactions must be submitted as one group."); //$NON-NLS-1$
        try
        {
            return new AddPortfolioTransaction(transaction.getUUID(), portfolio.getUUID(), transaction.getDateTime(),
                            transaction.getCurrencyCode(), transaction.getAmount(),
                            transaction.getSecurity() == null ? null : transaction.getSecurity().getUUID(),
                            transaction.getShares(), transaction.getType(), transaction.getNote(), transaction.getSource(),
                            unitData(transaction));
        }
        catch (RuntimeException e)
        {
            throw new UnsupportedChangeException("Portfolio transaction cannot be represented as an additive command: "
                            + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static List<UnitData> unitData(Transaction transaction)
    {
        return transaction.getUnits().map(unit -> new UnitData(unit.getType(), unit.getAmount().getCurrencyCode(),
                        unit.getAmount().getAmount(), unit.getForex() == null ? null : unit.getForex().getCurrencyCode(),
                        unit.getForex() == null ? null : unit.getForex().getAmount(), unit.getExchangeRate())).toList();
    }

    private static boolean containsTransaction(Client client, String id)
    {
        return client.getAccounts().stream().flatMap(account -> account.getTransactions().stream())
                        .anyMatch(transaction -> id.equals(transaction.getUUID()))
                        || client.getPortfolios().stream().flatMap(portfolio -> portfolio.getTransactions().stream())
                                        .anyMatch(transaction -> id.equals(transaction.getUUID()));
    }

    private static byte[] canonicalBytes(Client client) throws IOException
    {
        Path temporary = Files.createTempFile("shared-command-", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            ClientFactory.save(client, temporary.toFile());
            return Files.readAllBytes(temporary);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    public String id()
    {
        return id;
    }

    public String workspaceId()
    {
        return workspaceId;
    }

    public String parentRevision()
    {
        return parentRevision;
    }

    public String actorId()
    {
        return actorId;
    }

    public Instant createdAt()
    {
        return createdAt;
    }

    public List<Operation> operations()
    {
        return operations;
    }

    public String toJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("protocolVersion", PROTOCOL_VERSION); //$NON-NLS-1$
        json.addProperty("id", id); //$NON-NLS-1$
        json.addProperty("workspaceId", workspaceId); //$NON-NLS-1$
        json.addProperty("parentRevision", parentRevision); //$NON-NLS-1$
        json.addProperty("actorId", actorId); //$NON-NLS-1$
        json.addProperty("createdAt", createdAt.toString()); //$NON-NLS-1$
        JsonArray values = new JsonArray();
        operations.forEach(operation -> values.add(operation.toJson()));
        json.add("operations", values); //$NON-NLS-1$
        return json.toString();
    }

    public static SharedPortfolioCommand fromJson(String value) throws IOException
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject())
                throw new IllegalArgumentException("Command envelope must be a JSON object."); //$NON-NLS-1$
            JsonObject json = parsed.getAsJsonObject();
            if (requiredInt(json, "protocolVersion") != PROTOCOL_VERSION) //$NON-NLS-1$
                throw new IllegalArgumentException("Unsupported shared portfolio command version."); //$NON-NLS-1$
            List<Operation> operations = new ArrayList<>();
            JsonArray values = requiredArray(json, "operations"); //$NON-NLS-1$
            for (JsonElement valueElement : values)
            {
                if (!valueElement.isJsonObject())
                    throw new IllegalArgumentException("Command operation must be an object."); //$NON-NLS-1$
                operations.add(operationFromJson(valueElement.getAsJsonObject()));
            }
            return new SharedPortfolioCommand(requiredString(json, "id"), requiredString(json, "workspaceId"), //$NON-NLS-1$ //$NON-NLS-2$
                            requiredString(json, "parentRevision"), requiredString(json, "actorId"), //$NON-NLS-1$ //$NON-NLS-2$
                            Instant.parse(requiredString(json, "createdAt")), operations); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            throw new IOException("Shared portfolio command is invalid.", e); //$NON-NLS-1$
        }
    }

    /** Applies all operations after validating the complete batch. */
    public ApplyResult apply(Client client) throws ConflictException
    {
        Objects.requireNonNull(client);
        List<PreparedOperation> prepared = new ArrayList<>();
        java.util.Set<String> operationKeys = new HashSet<>();
        int noOp = 0;
        for (Operation operation : operations)
        {
            String key = operationKey(operation);
            if (!operationKeys.add(key))
                throw new ConflictException("A command contains the same entity more than once: " + key); //$NON-NLS-1$
            PreparedOperation item = prepare(client, operation);
            prepared.add(item);
            if (item.isNoOp())
                noOp++;
        }

        for (PreparedOperation item : prepared)
        {
            if (!item.isNoOp())
                item.apply();
        }
        return new ApplyResult(prepared.size() - noOp, noOp);
    }

    private static String operationKey(Operation operation)
    {
        if (operation instanceof AddQuote quote)
            return "quote:" + quote.securityId() + ":" + quote.date() + ":" + quote.latest(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (operation instanceof AddAccountTransaction transaction)
            return "transaction:" + transaction.transactionId(); //$NON-NLS-1$
        if (operation instanceof AddPortfolioTransaction transaction)
            return "transaction:" + transaction.transactionId(); //$NON-NLS-1$
        return operation.operationType() + ":" + operation.hashCode(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static PreparedOperation prepare(Client client, Operation operation) throws ConflictException
    {
        if (operation instanceof AddQuote quote)
            return prepareQuote(client, quote);
        if (operation instanceof AddAccountTransaction transaction)
            return prepareAccountTransaction(client, transaction);
        if (operation instanceof AddPortfolioTransaction transaction)
            return preparePortfolioTransaction(client, transaction);
        throw new ConflictException("Unsupported shared portfolio command operation: " + operation.operationType()); //$NON-NLS-1$
    }

    private static PreparedOperation prepareQuote(Client client, AddQuote operation) throws ConflictException
    {
        Security security = findSecurity(client, operation.securityId());
        if (security == null)
            throw new ConflictException("Security " + operation.securityId() + " does not exist."); //$NON-NLS-1$

        if (!operation.latest())
        {
            SecurityPrice existing = security.getPrices().stream().filter(p -> operation.date().equals(p.getDate()))
                            .findFirst().orElse(null);
            if (existing != null)
            {
                if (existing.getValue() == operation.value())
                    return PreparedOperation.empty();
                throw new ConflictException("A different historic quote already exists for " + operation.date()
                                + " on security " + operation.securityId() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new PreparedOperation(false, () -> security.addPrice(new SecurityPrice(operation.date(),
                            operation.value())));
        }

        LatestSecurityPrice existing = security.getLatest();
        if (existing != null)
        {
            if (existing.getDate().equals(operation.date()) && existing.getValue() == operation.value()
                            && existing.getHigh() == operation.high() && existing.getLow() == operation.low()
                            && existing.getVolume() == operation.volume())
                return PreparedOperation.empty();
            throw new ConflictException("A different latest quote already exists for security " //$NON-NLS-1$
                            + operation.securityId() + "."); //$NON-NLS-1$
        }
        return new PreparedOperation(false, () -> security.setLatest(new LatestSecurityPrice(operation.date(),
                        operation.value(), operation.high(), operation.low(), operation.volume())));
    }

    private static PreparedOperation prepareAccountTransaction(Client client, AddAccountTransaction operation)
                    throws ConflictException
    {
        Account account = client.getAccounts().stream().filter(a -> operation.accountId().equals(a.getUUID())).findFirst()
                        .orElse(null);
        if (account == null)
            throw new ConflictException("Account " + operation.accountId() + " does not exist."); //$NON-NLS-1$
        if (!account.getCurrencyCode().equals(operation.currencyCode()))
            throw new ConflictException("Account currency " + account.getCurrencyCode() + " does not match transaction currency " //$NON-NLS-1$ //$NON-NLS-2$
                            + operation.currencyCode() + "."); //$NON-NLS-1$
        Security security = findSecurity(client, operation.securityId());
        if (operation.securityId() != null && security == null)
            throw new ConflictException("Security " + operation.securityId() + " does not exist."); //$NON-NLS-1$
        Transaction existing = findTransaction(client, operation.transactionId());
        if (existing != null)
        {
            if (sameTransaction(client, existing, operation.accountId(), operation.dateTime(), operation.currencyCode(),
                            operation.amount(), security, operation.shares(), operation.type().name(), operation.note(),
                            operation.source(), operation.exDate(), operation.units()))
                return PreparedOperation.empty();
            throw new ConflictException("Transaction " + operation.transactionId() + " already exists with different data."); //$NON-NLS-1$
        }
        AccountTransaction transaction = new AccountTransaction(operation.transactionId());
        transaction.setDateTime(operation.dateTime());
        transaction.setCurrencyCode(operation.currencyCode());
        transaction.setAmount(operation.amount());
        transaction.setSecurity(security);
        transaction.setShares(operation.shares());
        transaction.setType(operation.type());
        transaction.setNote(operation.note());
        transaction.setSource(operation.source());
        transaction.setExDate(operation.exDate());
        try
        {
            addUnits(transaction, operation.units());
        }
        catch (IllegalArgumentException e)
        {
            throw new ConflictException("Account transaction units are invalid: " + e.getMessage()); //$NON-NLS-1$
        }
        return new PreparedOperation(false, () -> account.addTransaction(transaction));
    }

    private static PreparedOperation preparePortfolioTransaction(Client client, AddPortfolioTransaction operation)
                    throws ConflictException
    {
        Portfolio portfolio = client.getPortfolios().stream().filter(p -> operation.portfolioId().equals(p.getUUID()))
                        .findFirst().orElse(null);
        if (portfolio == null)
            throw new ConflictException("Portfolio " + operation.portfolioId() + " does not exist."); //$NON-NLS-1$
        Security security = findSecurity(client, operation.securityId());
        if (operation.securityId() != null && security == null)
            throw new ConflictException("Security " + operation.securityId() + " does not exist."); //$NON-NLS-1$
        Transaction existing = findTransaction(client, operation.transactionId());
        if (existing != null)
        {
            if (sameTransaction(client, existing, operation.portfolioId(), operation.dateTime(), operation.currencyCode(),
                            operation.amount(), security, operation.shares(), operation.type().name(), operation.note(),
                            operation.source(), null, operation.units()))
                return PreparedOperation.empty();
            throw new ConflictException("Transaction " + operation.transactionId() + " already exists with different data."); //$NON-NLS-1$
        }
        PortfolioTransaction transaction = new PortfolioTransaction(operation.transactionId());
        transaction.setDateTime(operation.dateTime());
        transaction.setCurrencyCode(operation.currencyCode());
        transaction.setAmount(operation.amount());
        transaction.setSecurity(security);
        transaction.setShares(operation.shares());
        transaction.setType(operation.type());
        transaction.setNote(operation.note());
        transaction.setSource(operation.source());
        try
        {
            addUnits(transaction, operation.units());
        }
        catch (IllegalArgumentException e)
        {
            throw new ConflictException("Portfolio transaction units are invalid: " + e.getMessage()); //$NON-NLS-1$
        }
        return new PreparedOperation(false, () -> portfolio.addTransaction(transaction));
    }

    private static void addUnits(Transaction transaction, List<UnitData> units)
    {
        units.stream().map(UnitData::toModelUnit).forEach(transaction::addUnit);
    }

    private static boolean sameTransaction(Client client, Transaction existing, String ownerId, LocalDateTime dateTime,
                    String currencyCode, long amount, Security security, long shares, String type, String note,
                    String source, LocalDateTime exDate, List<UnitData> units)
    {
        boolean ownedByTarget = existing instanceof AccountTransaction
                        ? client.getAccounts().stream().anyMatch(account -> ownerId.equals(account.getUUID())
                                        && account.getTransactions().contains(existing))
                        : client.getPortfolios().stream().anyMatch(portfolio -> ownerId.equals(portfolio.getUUID())
                                        && portfolio.getTransactions().contains(existing));
        if (!ownedByTarget || !Objects.equals(existing.getDateTime(), dateTime)
                        || !Objects.equals(existing.getCurrencyCode(), currencyCode) || existing.getAmount() != amount
                        || !Objects.equals(existing.getSecurity(), security) || existing.getShares() != shares
                        || !Objects.equals(existing.getNote(), note) || !Objects.equals(existing.getSource(), source))
            return false;
        String actualType = existing instanceof AccountTransaction account ? account.getType().name()
                        : existing instanceof PortfolioTransaction portfolio ? portfolio.getType().name() : null;
        if (!Objects.equals(actualType, type))
            return false;
        if (existing instanceof AccountTransaction account
                        && !Objects.equals(account.getExDate(), exDate))
            return false;
        List<Transaction.Unit> actualUnits = existing.getUnits().toList();
        if (actualUnits.size() != units.size())
            return false;
        for (int ii = 0; ii < units.size(); ii++)
        {
            UnitData expected = units.get(ii);
            Transaction.Unit actual = actualUnits.get(ii);
            if (actual.getType() != expected.type() || !actual.getAmount().equals(Money.of(expected.currencyCode(),
                            expected.amount())))
                return false;
            if (expected.forexCurrencyCode() == null)
            {
                if (actual.getForex() != null || actual.getExchangeRate() != null)
                    return false;
            }
            else if (!Money.of(expected.forexCurrencyCode(), expected.forexAmount()).equals(actual.getForex())
                            || !Objects.equals(expected.exchangeRate(), actual.getExchangeRate()))
                return false;
        }
        return true;
    }

    private static Transaction findTransaction(Client client, String id)
    {
        for (Account account : client.getAccounts())
            for (AccountTransaction transaction : account.getTransactions())
                if (id.equals(transaction.getUUID()))
                    return transaction;
        for (Portfolio portfolio : client.getPortfolios())
            for (PortfolioTransaction transaction : portfolio.getTransactions())
                if (id.equals(transaction.getUUID()))
                    return transaction;
        return null;
    }

    private static Security findSecurity(Client client, String id)
    {
        if (id == null)
            return null;
        return client.getSecurities().stream().filter(s -> id.equals(s.getUUID())).findFirst().orElse(null);
    }

    private record PreparedOperation(boolean isNoOp, Runnable action)
    {
        static PreparedOperation empty()
        {
            return new PreparedOperation(true, () -> {
            });
        }

        void apply()
        {
            action.run();
        }
    }

    private static Operation operationFromJson(JsonObject json)
    {
        return switch (requiredString(json, "type")) //$NON-NLS-1$
        {
            case "addQuote" -> new AddQuote(requiredString(json, "securityId"), //$NON-NLS-1$
                            LocalDate.parse(requiredString(json, "date")), requiredLong(json, "value"), //$NON-NLS-1$
                            json.has("latest") && json.get("latest").getAsBoolean(), //$NON-NLS-1$
                            json.has("high") ? requiredLong(json, "high") : 0, //$NON-NLS-1$ //$NON-NLS-2$
                            json.has("low") ? requiredLong(json, "low") : 0, //$NON-NLS-1$ //$NON-NLS-2$
                            json.has("volume") ? requiredLong(json, "volume") : 0); //$NON-NLS-1$ //$NON-NLS-2$
            case "addAccountTransaction" -> new AddAccountTransaction(requiredString(json, "transactionId"), //$NON-NLS-1$
                            requiredString(json, "accountId"), LocalDateTime.parse(requiredString(json, "dateTime")), //$NON-NLS-1$
                            requiredString(json, "currencyCode"), requiredLong(json, "amount"), //$NON-NLS-1$ //$NON-NLS-2$
                            string(json, "securityId", null), json.has("shares") ? requiredLong(json, "shares") : 0, //$NON-NLS-1$ //$NON-NLS-2$
                            AccountTransaction.Type.valueOf(requiredString(json, "transactionType")), //$NON-NLS-1$
                            string(json, "note", null), string(json, "source", null), //$NON-NLS-1$ //$NON-NLS-2$
                            json.has("exDate") ? LocalDateTime.parse(requiredString(json, "exDate")) : null, //$NON-NLS-1$
                            unitsFromJson(json));
            case "addPortfolioTransaction" -> new AddPortfolioTransaction(requiredString(json, "transactionId"), //$NON-NLS-1$
                            requiredString(json, "portfolioId"), LocalDateTime.parse(requiredString(json, "dateTime")), //$NON-NLS-1$
                            requiredString(json, "currencyCode"), requiredLong(json, "amount"), //$NON-NLS-1$ //$NON-NLS-2$
                            string(json, "securityId", null), json.has("shares") ? requiredLong(json, "shares") : 0, //$NON-NLS-1$ //$NON-NLS-2$
                            PortfolioTransaction.Type.valueOf(requiredString(json, "transactionType")), //$NON-NLS-1$
                            string(json, "note", null), string(json, "source", null), unitsFromJson(json)); //$NON-NLS-1$
            default -> throw new IllegalArgumentException("Unsupported shared portfolio command operation."); //$NON-NLS-1$
        };
    }

    private static List<UnitData> unitsFromJson(JsonObject json)
    {
        List<UnitData> result = new ArrayList<>();
        if (json.has("units")) //$NON-NLS-1$
        {
            JsonArray units = json.getAsJsonArray("units"); //$NON-NLS-1$
            for (JsonElement unit : units)
                result.add(UnitData.fromJson(unit.getAsJsonObject()));
        }
        return result;
    }

    private static JsonObject transactionJson(String operationType, String transactionId, LocalDateTime dateTime,
                    String currencyCode, long amount, String securityId, long shares, String note, String source,
                    List<UnitData> units, Object... extra)
    {
        JsonObject json = new JsonObject();
        json.addProperty("type", operationType); //$NON-NLS-1$
        json.addProperty("transactionId", transactionId); //$NON-NLS-1$
        json.addProperty("dateTime", dateTime.toString()); //$NON-NLS-1$
        json.addProperty("currencyCode", currencyCode); //$NON-NLS-1$
        json.addProperty("amount", amount);
        json.addProperty("shares", shares);
        if (securityId != null)
            json.addProperty("securityId", securityId); //$NON-NLS-1$
        if (note != null)
            json.addProperty("note", note); //$NON-NLS-1$
        if (source != null)
            json.addProperty("source", source); //$NON-NLS-1$
        if (!units.isEmpty())
        {
            JsonArray values = new JsonArray();
            units.forEach(unit -> values.add(unit.toJson()));
            json.add("units", values); //$NON-NLS-1$
        }
        for (int ii = 0; ii + 1 < extra.length; ii += 2)
            json.addProperty((String) extra[ii], (String) extra[ii + 1]);
        return json;
    }

    private static List<UnitData> copyUnits(List<UnitData> units)
    {
        return units == null ? List.of() : List.copyOf(units);
    }

    private static String requiredString(JsonObject json, String name)
    {
        if (!json.has(name) || json.get(name).isJsonNull())
            throw new IllegalArgumentException("Missing command field '" + name + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        String value = json.get(name).getAsString();
        if (value.isEmpty())
            throw new IllegalArgumentException("Command field '" + name + "' is empty."); //$NON-NLS-1$ //$NON-NLS-2$
        return value;
    }

    private static String string(JsonObject json, String name, String fallback)
    {
        return !json.has(name) || json.get(name).isJsonNull() ? fallback : json.get(name).getAsString();
    }

    private static long requiredLong(JsonObject json, String name)
    {
        return Long.parseLong(requiredString(json, name));
    }

    private static int requiredInt(JsonObject json, String name)
    {
        return Integer.parseInt(requiredString(json, name));
    }

    private static JsonArray requiredArray(JsonObject json, String name)
    {
        if (!json.has(name) || !json.get(name).isJsonArray())
            throw new IllegalArgumentException("Missing command array '" + name + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        return json.getAsJsonArray(name);
    }

    private static void requireUuid(String value)
    {
        if (value == null)
            throw new IllegalArgumentException("Missing shared portfolio identifier."); //$NON-NLS-1$
        try
        {
            UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException("Invalid shared portfolio identifier.", e); //$NON-NLS-1$
        }
    }

    private static void requireRevision(String value)
    {
        if (value == null || !value.matches("[0-9a-f]{64}")) //$NON-NLS-1$
            throw new IllegalArgumentException("Invalid shared portfolio revision."); //$NON-NLS-1$
    }

    private static void requireCurrency(String value)
    {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing transaction currency."); //$NON-NLS-1$
    }
}
