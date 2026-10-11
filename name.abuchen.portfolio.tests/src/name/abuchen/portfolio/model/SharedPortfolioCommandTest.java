package name.abuchen.portfolio.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.money.CurrencyUnit;

public class SharedPortfolioCommandTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void commandRoundTripAndReplayAreIdempotent() throws Exception
    {
        Client initial = client();
        Security security = initial.getSecurities().get(0);
        Account account = initial.getAccounts().get(0);
        Path ownerFile = temporaryFolder.newFile("owner.portfolio").toPath(); //$NON-NLS-1$
        ClientFactory.save(initial, ownerFile.toFile());
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$

        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        Path contributorFile = ownerFile.resolveSibling("contributor.portfolio"); //$NON-NLS-1$
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory, contributorFile);
        Client ownerClient = ClientFactory.load(ownerFile.toFile(), null, new NullProgressMonitor());

        SharedPortfolioCommand command = SharedPortfolioCommand.create(owner.getWorkspace().getWorkspaceId(),
                        contributor.getActorId(), contributor.getParentRevision(), List.of(
                                        new SharedPortfolioCommand.AddQuote(security.getUUID(), LocalDate.of(2026, 9, 1),
                                                        1234),
                                        new SharedPortfolioCommand.AddAccountTransaction(UUID.randomUUID().toString(),
                                                        account.getUUID(), LocalDateTime.of(2026, 9, 1, 12, 0),
                                                        CurrencyUnit.EUR, 2500, null, 0,
                                                        AccountTransaction.Type.DEPOSIT, "import", "test", null)));
        assertEquals(command.id(), contributor.submitCommand(command));
        assertEquals(1, owner.getWorkspace().pendingCommands().size());

        SharedPortfolioWorkspace.CommandAcceptance acceptance = owner.acceptCommand(ownerClient, command.id());
        assertEquals(2, acceptance.appliedOperations());
        assertEquals(0, acceptance.noOpOperations());
        assertEquals(1, owner.getWorkspace().acceptedCommands().size());
        assertEquals(1, ownerClient.getAccounts().get(0).getTransactions().size());
        assertEquals(1, ownerClient.getSecurities().get(0).getPrices().size());
        assertEquals(acceptance.revision(), owner.getWorkspace().currentRevision());

        SharedPortfolioWorkspace.CommandAcceptance repeated = owner.getWorkspace().acceptCommand(command.id(),
                        ownerClient);
        assertEquals(acceptance.commandId(), repeated.commandId());
        assertEquals(acceptance.sequence(), repeated.sequence());
        assertEquals(acceptance.revision(), repeated.revision());
        assertFalse(owner.getWorkspace().pendingCommands().stream().anyMatch(c -> c.id().equals(command.id())));
    }

    @Test
    public void conflictingQuoteIsRejectedWithoutPartialMutation() throws Exception
    {
        Client client = client();
        Security security = client.getSecurities().get(0);
        security.addPrice(new SecurityPrice(LocalDate.of(2026, 9, 1), 100));
        SharedPortfolioCommand command = new SharedPortfolioCommand(UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", //$NON-NLS-1$
                        UUID.randomUUID().toString(), java.time.Instant.now(),
                        List.of(new SharedPortfolioCommand.AddQuote(security.getUUID(), LocalDate.of(2026, 9, 1), 200)));

        assertThrows(SharedPortfolioCommand.ConflictException.class, () -> command.apply(client));
        assertEquals(1, security.getPrices().size());
        assertEquals(100, security.getPrices().get(0).getValue());
    }

    @Test
    public void independentCommandsBasedOnSameParentCanBeAcceptedInSequence() throws Exception
    {
        Client initial = client();
        Path ownerFile = temporaryFolder.newFile("owner-sequence.portfolio").toPath(); //$NON-NLS-1$
        ClientFactory.save(initial, ownerFile.toFile());
        Path workspaceDirectory = temporaryFolder.newFolder("workspace-sequence").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        SharedPortfolioSession first = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("first-sequence.portfolio")); //$NON-NLS-1$
        SharedPortfolioSession second = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("second-sequence.portfolio")); //$NON-NLS-1$
        Security security = initial.getSecurities().get(0);
        String parent = first.getParentRevision();
        SharedPortfolioCommand firstCommand = SharedPortfolioCommand.create(owner.getWorkspace().getWorkspaceId(),
                        first.getActorId(), parent, List.of(new SharedPortfolioCommand.AddQuote(security.getUUID(),
                                        LocalDate.of(2026, 9, 3), 101)));
        SharedPortfolioCommand secondCommand = SharedPortfolioCommand.create(owner.getWorkspace().getWorkspaceId(),
                        second.getActorId(), parent, List.of(new SharedPortfolioCommand.AddQuote(security.getUUID(),
                                        LocalDate.of(2026, 9, 4), 102)));
        first.submitCommand(firstCommand);
        second.submitCommand(secondCommand);
        Client ownerClient = ClientFactory.load(ownerFile.toFile(), null, new NullProgressMonitor());

        owner.acceptCommand(ownerClient, firstCommand.id());
        owner.acceptCommand(ownerClient, secondCommand.id());

        assertEquals(2, owner.getWorkspace().acceptedCommands().size());
        assertEquals(2, ownerClient.getSecurities().get(0).getPrices().size());
        assertEquals(2, owner.getWorkspace().acceptedCommandsSince(0).size());
    }

    @Test
    public void commandJsonDoesNotDependOnGsonReflectionForDates() throws Exception
    {
        String workspace = UUID.randomUUID().toString();
        String actor = UUID.randomUUID().toString();
        String revision = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"; //$NON-NLS-1$
        SharedPortfolioCommand original = SharedPortfolioCommand.create(workspace, actor, revision,
                        List.of(new SharedPortfolioCommand.AddQuote(UUID.randomUUID().toString(), LocalDate.of(2026, 9, 2), 99)));
        SharedPortfolioCommand restored = SharedPortfolioCommand.fromJson(original.toJson());
        assertEquals(original.id(), restored.id());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.operations(), restored.operations());
    }

    @Test
    public void additiveChangesExtractsNewQuotesAndTransactions() throws Exception
    {
        Client base = client();
        Path baseFile = temporaryFolder.newFile("base-additions.portfolio").toPath(); //$NON-NLS-1$
        ClientFactory.save(base, baseFile.toFile());
        Client loadedBase = ClientFactory.load(baseFile.toFile(), null, new NullProgressMonitor());
        Client current = ClientFactory.duplicate(loadedBase);
        Security security = current.getSecurities().get(0);
        Account account = current.getAccounts().get(0);
        security.addPrice(new SecurityPrice(LocalDate.of(2026, 9, 5), 500));
        account.addTransaction(new AccountTransaction(LocalDateTime.of(2026, 9, 5, 8, 0), CurrencyUnit.EUR, 99,
                        null, AccountTransaction.Type.DEPOSIT));

        List<SharedPortfolioCommand.Operation> operations = SharedPortfolioCommand.additiveChanges(loadedBase, current);
        assertEquals(2, operations.size());
        assertEquals("addQuote", operations.get(0).operationType()); //$NON-NLS-1$
        assertEquals("addAccountTransaction", operations.get(1).operationType()); //$NON-NLS-1$
    }

    @Test
    public void sessionSubmitChangesUsesCommandAndRefreshesAfterAcceptance() throws Exception
    {
        Client initial = client();
        Path ownerFile = temporaryFolder.newFile("owner-session.portfolio").toPath(); //$NON-NLS-1$
        ClientFactory.save(initial, ownerFile.toFile());
        Path workspaceDirectory = temporaryFolder.newFolder("workspace-session").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        Path contributorPath = ownerFile.resolveSibling("contributor-session.portfolio"); //$NON-NLS-1$
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory, contributorPath);
        Client contributorClient = ClientFactory.load(contributorPath.toFile(), null, new NullProgressMonitor());
        contributorClient.getSecurities().get(0).addPrice(new SecurityPrice(LocalDate.of(2026, 9, 6), 600));
        ClientFactory.save(contributorClient, contributorPath.toFile());

        String commandId = contributor.submitChanges(contributorClient);
        assertEquals(1, owner.getWorkspace().pendingCommands().size());
        assertFalse(contributor.refresh());

        Client ownerClient = ClientFactory.load(ownerFile.toFile(), null, new NullProgressMonitor());
        owner.acceptCommand(ownerClient, commandId);
        assertTrue(contributor.refresh());
        assertEquals(owner.getParentRevision(), contributor.getParentRevision());
    }

    @Test
    public void ownerCanAcceptItsOwnStagedCommand() throws Exception
    {
        Client initial = client();
        Path ownerFile = temporaryFolder.newFile("owner-staged.portfolio").toPath(); //$NON-NLS-1$
        ClientFactory.save(initial, ownerFile.toFile());
        Path workspaceDirectory = temporaryFolder.newFolder("workspace-staged").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        Client ownerClient = ClientFactory.load(ownerFile.toFile(), null, new NullProgressMonitor());
        ownerClient.getSecurities().get(0).addPrice(new SecurityPrice(LocalDate.of(2026, 9, 7), 700));
        ClientFactory.save(ownerClient, ownerFile.toFile());

        String commandId = owner.submitChanges(ownerClient);
        SharedPortfolioWorkspace.CommandAcceptance acceptance = owner.acceptCommand(ownerClient, commandId);
        assertEquals(0, acceptance.appliedOperations());
        assertEquals(1, acceptance.noOpOperations());
        assertEquals(acceptance.revision(), owner.getParentRevision());
        assertEquals(700, ownerClient.getSecurities().get(0).getPrices().get(0).getValue());
    }

    @Test
    public void retryRepairsHeadAfterEventWasPublishedBeforeReceipt() throws Exception
    {
        Client initial = client();
        Path ownerFile = temporaryFolder.newFile("owner-recovery.portfolio").toPath(); //$NON-NLS-1$
        ClientFactory.save(initial, ownerFile.toFile());
        Path workspaceDirectory = temporaryFolder.newFolder("workspace-recovery").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("contributor-recovery.portfolio")); //$NON-NLS-1$
        String initialRevision = owner.getParentRevision();
        SharedPortfolioCommand command = SharedPortfolioCommand.create(owner.getWorkspace().getWorkspaceId(),
                        contributor.getActorId(), initialRevision,
                        List.of(new SharedPortfolioCommand.AddQuote(initial.getSecurities().get(0).getUUID(),
                                        LocalDate.of(2026, 9, 8), 800)));
        contributor.submitCommand(command);
        Client ownerClient = ClientFactory.load(ownerFile.toFile(), null, new NullProgressMonitor());
        SharedPortfolioWorkspace.CommandAcceptance accepted = owner.acceptCommand(ownerClient, command.id());

        // Simulate a process stopping after the immutable event was written,
        // but before the head and receipt became durable.
        Files.writeString(workspaceDirectory.resolve("head"), initialRevision); //$NON-NLS-1$
        Files.delete(workspaceDirectory.resolve("accepted").resolve(command.id() + ".ppreceipt")); //$NON-NLS-1$

        SharedPortfolioWorkspace.CommandAcceptance retried = owner.getWorkspace().acceptCommand(command.id(),
                        ClientFactory.load(ownerFile.toFile(), null, new NullProgressMonitor()));
        assertEquals(accepted.sequence(), retried.sequence());
        assertEquals(accepted.revision(), owner.getWorkspace().currentRevision());
        assertTrue(Files.isRegularFile(workspaceDirectory.resolve("accepted") //$NON-NLS-1$
                        .resolve(command.id() + ".ppreceipt"))); //$NON-NLS-1$
    }

    private Client client()
    {
        Client client = new Client();
        Security security = new Security("Example", CurrencyUnit.EUR); //$NON-NLS-1$
        client.addSecurity(security);
        Account account = new Account("Cash"); //$NON-NLS-1$
        account.setCurrencyCode(CurrencyUnit.EUR);
        client.addAccount(account);
        Portfolio portfolio = new Portfolio("Portfolio"); //$NON-NLS-1$
        client.addPortfolio(portfolio);
        return client;
    }
}
