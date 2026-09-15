package name.abuchen.portfolio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.NullProgressMonitor;

/** Associates one local working file with a shared workspace revision. */
public final class SharedPortfolioSession
{
    private static final String SIDECAR_SUFFIX = ".ppsync"; //$NON-NLS-1$

    private final Path localFile;
    private final Path sidecar;
    private final SharedPortfolioWorkspace workspace;
    private final String actorId;
    private String parentRevision;
    private final List<String> pendingCommandIds;
    private String stagedContentHash;

    private SharedPortfolioSession(Path localFile, Path sidecar, SharedPortfolioWorkspace workspace, String actorId,
                    String parentRevision, List<String> pendingCommandIds, String stagedContentHash)
    {
        this.localFile = localFile;
        this.sidecar = sidecar;
        this.workspace = workspace;
        this.actorId = actorId;
        this.parentRevision = parentRevision;
        this.pendingCommandIds = new java.util.ArrayList<>(pendingCommandIds);
        this.stagedContentHash = stagedContentHash;
    }

    /** Creates a workspace from an existing local file and makes this device the owner. */
    public static SharedPortfolioSession createOwner(Path workspaceDirectory, Path localFile) throws IOException
    {
        Path local = localFile.toAbsolutePath().normalize();
        ensureOutsideWorkspace(local, workspaceDirectory);
        byte[] content = Files.readAllBytes(local);
        String actor = UUID.randomUUID().toString();
        SharedPortfolioWorkspace workspace = SharedPortfolioWorkspace.create(workspaceDirectory, content, actor);
        SharedPortfolioSession session = new SharedPortfolioSession(local, sidecarFor(local), workspace, actor,
                        workspace.currentRevision(), List.of(), null);
        session.writeSidecar();
        return session;
    }

    /** Copies the current master into a new local file and joins as a contributor. */
    public static SharedPortfolioSession join(Path workspaceDirectory, Path localFile) throws IOException
    {
        Path local = localFile.toAbsolutePath().normalize();
        if (Files.exists(local))
            throw new IOException("The local working file already exists; choose a new path."); //$NON-NLS-1$
        String actor = UUID.randomUUID().toString();
        SharedPortfolioWorkspace workspace = SharedPortfolioWorkspace.open(workspaceDirectory, actor);
        ensureOutsideWorkspace(local, workspace.getDirectory());
        byte[] content = workspace.readMaster();
        writeAtomic(local, content, false);
        SharedPortfolioSession session = new SharedPortfolioSession(local, sidecarFor(local), workspace, actor,
                        workspace.currentRevision(), List.of(), null);
        session.writeSidecar();
        return session;
    }

    /** Reattaches a local file using its local-only sidecar after application restart. */
    public static SharedPortfolioSession open(Path localFile) throws IOException
    {
        Path local = localFile.toAbsolutePath().normalize();
        Properties properties = readProperties(sidecarFor(local));
        if (!Integer.toString(SharedPortfolioWorkspace.PROTOCOL_VERSION)
                        .equals(properties.getProperty("protocolVersion"))) //$NON-NLS-1$
            throw new IOException("Unsupported shared-portfolio association version."); //$NON-NLS-1$
        String workspacePath = properties.getProperty("workspace"); //$NON-NLS-1$
        String actor = properties.getProperty("actorId"); //$NON-NLS-1$
        String workspaceId = properties.getProperty("workspaceId"); //$NON-NLS-1$
        String parent = properties.getProperty("parentRevision"); //$NON-NLS-1$
        List<String> pendingCommands = parsePendingCommands(properties.getProperty("pendingCommands")); //$NON-NLS-1$
        String stagedHash = properties.getProperty("stagedContentHash"); //$NON-NLS-1$
        validate(actor, workspacePath, workspaceId, parent);
        if (stagedHash != null && !stagedHash.matches("[0-9a-f]{64}")) //$NON-NLS-1$
            throw new IllegalArgumentException("The local shared-portfolio association is invalid."); //$NON-NLS-1$
        SharedPortfolioWorkspace workspace = SharedPortfolioWorkspace.open(Path.of(workspacePath), actor);
        if (!workspace.getWorkspaceId().equals(workspaceId))
            throw new IOException("The local shared-portfolio association points to another workspace."); //$NON-NLS-1$
        if (!Files.isRegularFile(local))
            throw new IOException("The local shared-portfolio working file is missing."); //$NON-NLS-1$
        return new SharedPortfolioSession(local, sidecarFor(local), workspace, actor, parent, pendingCommands, stagedHash);
    }

    /** Returns an association if a valid sidecar exists; unavailable sync is reported as absent. */
    public static SharedPortfolioSession tryOpen(Path localFile)
    {
        try
        {
            return open(localFile);
        }
        catch (IOException | IllegalArgumentException e)
        {
            return null;
        }
    }

    public Path getLocalFile()
    {
        return localFile;
    }

    public Path getWorkspaceDirectory()
    {
        return workspace.getDirectory();
    }

    public SharedPortfolioWorkspace getWorkspace()
    {
        return workspace;
    }

    public String getActorId()
    {
        return actorId;
    }

    public String getParentRevision()
    {
        return parentRevision;
    }

    public boolean isOwner()
    {
        return workspace.isOwner();
    }

    public List<SharedPortfolioWorkspace.Submission> pending() throws IOException
    {
        return workspace.pending();
    }

    /** Returns all additive commands in the mailbox that have no owner receipt. */
    public List<SharedPortfolioCommand> pendingCommands() throws IOException
    {
        return workspace.pendingCommands();
    }

    /** Returns additive commands submitted by this device that have no receipt. */
    public List<SharedPortfolioCommand> ownPendingCommands() throws IOException
    {
        return workspace.pendingCommands().stream().filter(command -> pendingCommandIds.contains(command.id()))
                        .collect(Collectors.toList());
    }

    /** Returns the complete accepted command/event stream for replication. */
    public List<SharedPortfolioWorkspace.AcceptedCommand> events() throws IOException
    {
        return workspace.acceptedCommands();
    }

    /**
     * Publishes an additive command and records it in the local sidecar. The
     * command's parent must be this working copy's revision.
     */
    public String submitCommand(SharedPortfolioCommand command) throws IOException
    {
        if (!parentRevision.equals(command.parentRevision()))
            throw new SharedPortfolioWorkspace.ConflictException(
                            "Command must be based on this working copy's parent revision."); //$NON-NLS-1$
        String id = workspace.submitCommand(command);
        if (!pendingCommandIds.contains(id))
            pendingCommandIds.add(id);
        writeSidecar();
        return id;
    }

    /**
     * Derives and publishes additive operations from the current in-memory
     * model. If the edit contains updates, deletions, new entities or linked
     * transactions, the established complete-file submission path is used.
     */
    public String submitChanges(Client current) throws IOException
    {
        if (current == null)
            throw new IOException("No portfolio model is loaded."); //$NON-NLS-1$
        byte[] local = Files.readAllBytes(localFile);
        String localHash = SharedPortfolioWorkspace.contentHash(local);
        if (stagedContentHash != null && !stagedContentHash.equals(localHash))
            throw new SharedPortfolioWorkspace.DirtyException(
                            "Save local changes before submitting them, or finish the pending command first."); //$NON-NLS-1$
        if (stagedContentHash == null && !pendingCommandIds.isEmpty() && !parentRevision.equals(localHash))
            throw new SharedPortfolioWorkspace.DirtyException(
                            "Finish the pending additive command before submitting another model diff."); //$NON-NLS-1$
        if (!pendingCommandIds.isEmpty() && stagedContentHash != null)
            throw new SharedPortfolioWorkspace.DirtyException(
                            "Wait for the pending additive command to be accepted before submitting another model diff."); //$NON-NLS-1$

        Path temporary = Files.createTempFile("shared-portfolio-base-", ".portfolio"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(temporary, workspace.readRevision(parentRevision), StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
            if (ClientFactory.isEncrypted(temporary.toFile()))
                throw new SharedPortfolioCommand.UnsupportedChangeException(
                                "Encrypted snapshots require an explicit additive command; using the complete-file fallback."); //$NON-NLS-1$
            Client base = ClientFactory.load(temporary.toFile(), null, new NullProgressMonitor());
            List<SharedPortfolioCommand.Operation> operations = SharedPortfolioCommand.additiveChanges(base, current);
            if (operations.isEmpty())
                return null;
            SharedPortfolioCommand command = SharedPortfolioCommand.create(workspace.getWorkspaceId(), actorId,
                            parentRevision, operations);
            String id = submitCommand(command);
            stagedContentHash = localHash;
            writeSidecar();
            return id;
        }
        catch (SharedPortfolioCommand.UnsupportedChangeException e)
        {
            // The byte-oriented protocol is retained as an intentional escape
            // hatch until every model mutation has an operation type.
            String id = submit();
            return id;
        }
        catch (IOException e)
        {
            // A previous complete-file proposal uses a hash that is not yet a
            // retained master revision. It cannot be diffed, so preserve the
            // established whole-file chain instead of losing the save.
            if (!workspace.hasRevision(parentRevision))
                return submit();
            throw e;
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    /** Applies an owner-approved additive command and refreshes the owner file. */
    public SharedPortfolioWorkspace.CommandAcceptance acceptCommand(Client client, String commandId)
                    throws IOException
    {
        if (!isOwner())
            throw new SharedPortfolioWorkspace.NotOwnerException();
        byte[] local = Files.readAllBytes(localFile);
        String localHash = SharedPortfolioWorkspace.contentHash(local);
        boolean acceptedStagedCommand = pendingCommandIds.contains(commandId)
                        && Objects.equals(stagedContentHash, localHash);
        if (!parentRevision.equals(localHash) && !acceptedStagedCommand)
            throw new SharedPortfolioWorkspace.DirtyException("Owner changes must be saved or submitted before accepting a command."); //$NON-NLS-1$
        if (!parentRevision.equals(workspace.currentRevision()))
            throw new SharedPortfolioWorkspace.ConflictException(
                            "Refresh the owner working copy before accepting a command."); //$NON-NLS-1$
        SharedPortfolioWorkspace.CommandAcceptance acceptance = workspace.acceptCommand(commandId, client);
        writeAtomic(localFile, workspace.readMaster(), true);
        parentRevision = acceptance.revision();
        pendingCommandIds.remove(commandId);
        stagedContentHash = null;
        writeSidecar();
        return acceptance;
    }

    /** Publishes this exact local file and advances the local proposal chain. */
    public String submit() throws IOException
    {
        byte[] content = Files.readAllBytes(localFile);
        SharedPortfolioWorkspace.validateContent(content);
        String hash = SharedPortfolioWorkspace.contentHash(content);
        if (!pendingCommandIds.isEmpty() && !parentRevision.equals(hash))
            throw new SharedPortfolioWorkspace.DirtyException(
                            "Finish the pending additive command before publishing a complete-file proposal."); //$NON-NLS-1$
        if (hash.equals(parentRevision))
            return null;
        String id = workspace.submit(localFile, parentRevision);
        parentRevision = hash;
        stagedContentHash = null;
        writeSidecar();
        return id;
    }

    /** Refreshes a clean working copy from the current master. */
    public boolean refresh() throws IOException
    {
        byte[] local = Files.readAllBytes(localFile);
        String localHash = SharedPortfolioWorkspace.contentHash(local);
        boolean stagedContent = Objects.equals(stagedContentHash, localHash);
        if (!parentRevision.equals(localHash) && !stagedContent)
            throw new SharedPortfolioWorkspace.DirtyException("Local changes must be submitted or saved elsewhere before refresh."); //$NON-NLS-1$
        String revision = workspace.currentRevision();
        if (revision.equals(parentRevision))
            return false;
        List<SharedPortfolioCommand> pendingBeforeRefresh = workspace.pendingCommands();
        boolean ownCommandPending = pendingCommandIds.stream()
                        .anyMatch(id -> pendingBeforeRefresh.stream().anyMatch(command -> id.equals(command.id())));
        if (ownCommandPending)
            throw new SharedPortfolioWorkspace.ConflictException(
                            "The local working copy has a pending additive command; review it before refreshing."); //$NON-NLS-1$
        if (!workspace.hasRevision(parentRevision))
            throw new SharedPortfolioWorkspace.ConflictException(
                            "The local working copy has a submitted proposal that is not yet the master; review it before refreshing."); //$NON-NLS-1$
        writeAtomic(localFile, workspace.readMaster(), true);
        parentRevision = revision;
        stagedContentHash = null;
        pendingCommandIds.removeIf(id -> pendingBeforeRefresh.stream().noneMatch(command -> id.equals(command.id())));
        writeSidecar();
        return true;
    }

    /** Applies an owner-approved submission to the local owner working copy. */
    public String accept(String submissionId) throws IOException
    {
        if (!isOwner())
            throw new SharedPortfolioWorkspace.NotOwnerException();
        byte[] local = Files.readAllBytes(localFile);
        String localHash = SharedPortfolioWorkspace.contentHash(local);
        if (!parentRevision.equals(localHash))
            throw new SharedPortfolioWorkspace.DirtyException("Owner changes must be saved or submitted before accepting a contribution."); //$NON-NLS-1$
        String revision = workspace.accept(submissionId);
        writeAtomic(localFile, workspace.readMaster(), true);
        parentRevision = revision;
        writeSidecar();
        return revision;
    }

    private void writeSidecar() throws IOException
    {
        Properties properties = new Properties();
        properties.setProperty("protocolVersion", Integer.toString(SharedPortfolioWorkspace.PROTOCOL_VERSION)); //$NON-NLS-1$
        properties.setProperty("workspace", workspace.getDirectory().toString()); //$NON-NLS-1$
        properties.setProperty("workspaceId", workspace.getWorkspaceId()); //$NON-NLS-1$
        properties.setProperty("actorId", actorId); //$NON-NLS-1$
        properties.setProperty("parentRevision", parentRevision); //$NON-NLS-1$
        if (!pendingCommandIds.isEmpty())
            properties.setProperty("pendingCommands", String.join(",", pendingCommandIds)); //$NON-NLS-1$ //$NON-NLS-2$
        if (stagedContentHash != null)
            properties.setProperty("stagedContentHash", stagedContentHash); //$NON-NLS-1$
        Path temporary = sidecar.resolveSibling(sidecar.getFileName() + ".tmp-" + UUID.randomUUID()); //$NON-NLS-1$
        try
        {
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW))
            {
                properties.store(output, "Portfolio Performance local shared-portfolio association"); //$NON-NLS-1$
            }
            try
            {
                Files.move(temporary, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e)
            {
                Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path sidecarFor(Path localFile)
    {
        return localFile.resolveSibling(localFile.getFileName() + SIDECAR_SUFFIX);
    }

    private static void ensureOutsideWorkspace(Path localFile, Path workspaceDirectory) throws IOException
    {
        Path workspace = workspaceDirectory.toAbsolutePath().normalize();
        if (localFile.startsWith(workspace))
            throw new IOException("Keep the local working file and its sidecar outside the synchronized workspace."); //$NON-NLS-1$
    }

    private static void writeAtomic(Path path, byte[] bytes, boolean replace) throws IOException
    {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID()); //$NON-NLS-1$
        try
        {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try
            {
                if (replace)
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException e)
            {
                if (replace)
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                else
                    Files.move(temporary, path);
            }
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static Properties readProperties(Path path) throws IOException
    {
        if (!Files.isRegularFile(path))
            throw new IOException("No shared-portfolio association exists for this file."); //$NON-NLS-1$
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            properties.load(reader);
        }
        return properties;
    }

    private static List<String> parsePendingCommands(String value)
    {
        if (value == null || value.isBlank())
            return List.of();
        List<String> result = new java.util.ArrayList<>();
        for (String id : value.split(",")) //$NON-NLS-1$
        {
            try
            {
                UUID.fromString(id);
                result.add(id);
            }
            catch (IllegalArgumentException e)
            {
                throw new IllegalArgumentException("The local shared-portfolio association is invalid."); //$NON-NLS-1$
            }
        }
        return result;
    }

    private static void validate(String actor, String workspace, String workspaceId, String parent)
    {
        try
        {
            UUID.fromString(actor);
            UUID.fromString(workspaceId);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException("The local shared-portfolio association is invalid.", e); //$NON-NLS-1$
        }
        if (workspace == null || workspace.isBlank() || parent == null || !parent.matches("[0-9a-f]{64}")) //$NON-NLS-1$
            throw new IllegalArgumentException("The local shared-portfolio association is invalid."); //$NON-NLS-1$
    }
}
