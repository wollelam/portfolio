package name.abuchen.portfolio.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Filesystem protocol for a portfolio shared through a folder synchronizer.
 * <p>
 * The folder is a mailbox, not a live portfolio file. Contributors publish
 * immutable complete-file submissions. Only the owner can advance the master,
 * and only when a submission is based on the current revision.
 */
public final class SharedPortfolioWorkspace
{
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PORTFOLIO_SIZE = 50 * 1024 * 1024;

    private static final String WORKSPACE_PROPERTIES = "workspace.properties"; //$NON-NLS-1$
    private static final String HEAD = "head"; //$NON-NLS-1$
    private static final String MASTER = "master.portfolio"; //$NON-NLS-1$
    private static final String REVISIONS = "revisions"; //$NON-NLS-1$
    private static final String SUBMISSIONS = "submissions"; //$NON-NLS-1$
    private static final String ACCEPTED = "accepted"; //$NON-NLS-1$
    private static final String LOCK = ".owner.lock"; //$NON-NLS-1$
    private static final String CHANGE_SUFFIX = ".ppchange"; //$NON-NLS-1$

    public record Submission(String id, String workspaceId, String parentRevision, String contentHash,
                    String actorId, Instant createdAt, byte[] content)
    {
        public Submission
        {
            Objects.requireNonNull(id);
            Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(parentRevision);
            Objects.requireNonNull(contentHash);
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(createdAt);
            content = content.clone();
        }

        @Override
        public byte[] content()
        {
            return content.clone();
        }
    }

    public static final class ConflictException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public ConflictException(String message)
        {
            super(message);
        }
    }

    public static final class DirtyException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public DirtyException(String message)
        {
            super(message);
        }
    }

    public static final class NotOwnerException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public NotOwnerException()
        {
            super("Only the workspace owner can accept submissions."); //$NON-NLS-1$
        }
    }

    private final Path directory;
    private final String workspaceId;
    private final String ownerId;
    private final String actorId;

    private SharedPortfolioWorkspace(Path directory, String workspaceId, String ownerId, String actorId)
    {
        this.directory = directory;
        this.workspaceId = workspaceId;
        this.ownerId = ownerId;
        this.actorId = actorId;
    }

    /** Creates a new workspace and publishes the initial local file as revision zero. */
    public static SharedPortfolioWorkspace create(Path directory, byte[] initialContent, String ownerId)
                    throws IOException
    {
        Objects.requireNonNull(initialContent);
        validateActor(ownerId);
        validateContent(initialContent);
        Path root = directory.toAbsolutePath().normalize();
        if (Files.exists(root))
        {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root))
            {
                if (entries.iterator().hasNext())
                    throw new IOException("Shared workspace directory is not empty: " + root); //$NON-NLS-1$
            }
        }
        else
        {
            Files.createDirectories(root);
        }

        Files.createDirectories(root.resolve(REVISIONS));
        Files.createDirectories(root.resolve(SUBMISSIONS));
        Files.createDirectories(root.resolve(ACCEPTED));
        String workspaceId = UUID.randomUUID().toString();
        String revision = contentHash(initialContent);
        writeBytes(root.resolve(REVISIONS).resolve(revision + ".portfolio"), initialContent, false); //$NON-NLS-1$
        writeBytes(root.resolve(MASTER), initialContent, false);
        writeText(root.resolve(HEAD), revision, false);

        Properties properties = new Properties();
        properties.setProperty("protocolVersion", Integer.toString(PROTOCOL_VERSION)); //$NON-NLS-1$
        properties.setProperty("workspaceId", workspaceId); //$NON-NLS-1$
        properties.setProperty("ownerId", ownerId); //$NON-NLS-1$
        writeProperties(root.resolve(WORKSPACE_PROPERTIES), properties, false);
        return open(root, ownerId);
    }

    /** Opens a workspace as the supplied device identity. */
    public static SharedPortfolioWorkspace open(Path directory, String actorId) throws IOException
    {
        validateActor(actorId);
        Path root = directory.toAbsolutePath().normalize();
        Properties properties = readProperties(root.resolve(WORKSPACE_PROPERTIES));
        if (!Integer.toString(PROTOCOL_VERSION).equals(properties.getProperty("protocolVersion"))) //$NON-NLS-1$
            throw new IOException("Unsupported shared portfolio protocol version."); //$NON-NLS-1$
        String workspaceId = properties.getProperty("workspaceId"); //$NON-NLS-1$
        String ownerId = properties.getProperty("ownerId"); //$NON-NLS-1$
        validateWorkspaceId(workspaceId);
        validateActor(ownerId);
        if (!Files.isDirectory(root.resolve(REVISIONS)) || !Files.isDirectory(root.resolve(SUBMISSIONS))
                        || !Files.isDirectory(root.resolve(ACCEPTED)))
            throw new IOException("Shared workspace is incomplete; wait for folder synchronization."); //$NON-NLS-1$
        SharedPortfolioWorkspace workspace = new SharedPortfolioWorkspace(root, workspaceId, ownerId, actorId);
        workspace.verifyHead();
        return workspace;
    }

    public Path getDirectory()
    {
        return directory;
    }

    public String getWorkspaceId()
    {
        return workspaceId;
    }

    public String getOwnerId()
    {
        return ownerId;
    }

    public String getActorId()
    {
        return actorId;
    }

    public boolean isOwner()
    {
        return ownerId.equals(actorId);
    }

    public String currentRevision() throws IOException
    {
        return verifyHead();
    }

    public byte[] readMaster() throws IOException
    {
        String revision = verifyHead();
        byte[] content = Files.readAllBytes(directory.resolve(REVISIONS).resolve(revision + ".portfolio")); //$NON-NLS-1$
        return content;
    }

    /** Returns whether a complete, checksum-valid immutable snapshot is present. */
    public boolean hasRevision(String revision) throws IOException
    {
        validateRevision(revision);
        Path snapshot = directory.resolve(REVISIONS).resolve(revision + ".portfolio"); //$NON-NLS-1$
        if (!Files.isRegularFile(snapshot) || Files.size(snapshot) > MAX_PORTFOLIO_SIZE)
            return false;
        byte[] content = Files.readAllBytes(snapshot);
        validateContent(content);
        return revision.equals(contentHash(content));
    }

    /** Publishes a complete local file. The local file is never modified. */
    public String submit(Path localFile, String parentRevision) throws IOException
    {
        Objects.requireNonNull(localFile);
        validateRevision(parentRevision);
        byte[] content = Files.readAllBytes(localFile);
        validateContent(content);
        // A contributor may publish a chain before the preceding proposal has
        // arrived or been accepted. The owner will apply it only after its
        // parent becomes the current head.
        currentRevision();

        String contentHash = contentHash(content);
        String id = UUID.randomUUID().toString();
        Path packageFile = directory.resolve(SUBMISSIONS).resolve(id + CHANGE_SUFFIX);
        writeSubmission(packageFile, id, parentRevision, contentHash, content);
        return id;
    }

    /** Returns valid, not-yet-accepted submissions in stable creation order. */
    public List<Submission> pending() throws IOException
    {
        List<Submission> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory.resolve(SUBMISSIONS), "*" + CHANGE_SUFFIX)) //$NON-NLS-1$
        {
            for (Path file : files)
            {
                String id = file.getFileName().toString().substring(0,
                                file.getFileName().toString().length() - CHANGE_SUFFIX.length());
                validateUuid(id);
                if (!Files.exists(directory.resolve(ACCEPTED).resolve(id)))
                    result.add(readSubmission(file));
            }
        }
        result.sort(Comparator.comparing(Submission::createdAt).thenComparing(Submission::id));
        return result;
    }

    /**
     * Accepts one submission and advances the master. The operation is
     * serialized on the owner machine and is idempotent after a receipt exists.
     */
    public String accept(String submissionId) throws IOException
    {
        if (!isOwner())
            throw new NotOwnerException();
        validateUuid(submissionId);

        try (FileChannel channel = FileChannel.open(directory.resolve(LOCK), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE); FileLock lock = acquireLock(channel))
        {
            Path receipt = directory.resolve(ACCEPTED).resolve(submissionId);
            if (Files.isRegularFile(receipt))
                return readReceipt(receipt);

            Submission submission = readSubmission(directory.resolve(SUBMISSIONS).resolve(submissionId + CHANGE_SUFFIX));
            String head = currentRevision();
            if (!head.equals(submission.parentRevision()))
                throw new ConflictException("Submission " + submissionId + " is based on " + submission.parentRevision() //$NON-NLS-1$ //$NON-NLS-2$
                                + "; current master is " + head + "."); //$NON-NLS-1$

            String revision = submission.contentHash();
            if (!head.equals(revision))
            {
                writeBytes(directory.resolve(REVISIONS).resolve(revision + ".portfolio"), submission.content(), false); //$NON-NLS-1$
                writeBytes(directory.resolve(MASTER), submission.content(), true);
                writeText(directory.resolve(HEAD), revision, true);
            }
            writeText(receipt, revision, true);
            return revision;
        }
    }

    private String verifyHead() throws IOException
    {
        String revision = Files.readString(directory.resolve(HEAD), StandardCharsets.UTF_8).strip();
        validateRevision(revision);
        Path snapshot = directory.resolve(REVISIONS).resolve(revision + ".portfolio"); //$NON-NLS-1$
        if (!Files.isRegularFile(snapshot))
            throw new IOException("Shared portfolio revision has not arrived yet; wait for folder synchronization."); //$NON-NLS-1$
        byte[] content = Files.readAllBytes(snapshot);
        validateContent(content);
        if (!revision.equals(contentHash(content)))
            throw new IOException("Shared portfolio revision checksum does not match."); //$NON-NLS-1$
        return revision;
    }

    private Submission readSubmission(Path packageFile) throws IOException
    {
        if (!Files.isRegularFile(packageFile) || Files.size(packageFile) > MAX_PORTFOLIO_SIZE + 8192L)
            throw new IOException("Shared submission is incomplete or too large: " + packageFile.getFileName()); //$NON-NLS-1$
        Properties metadata = new Properties();
        byte[] content = null;
        boolean metadataSeen = false;
        boolean contentSeen = false;
        try (InputStream input = Files.newInputStream(packageFile); ZipInputStream zip = new ZipInputStream(input,
                        StandardCharsets.UTF_8))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                if (entry.isDirectory())
                    continue;
                if ("metadata.properties".equals(entry.getName())) //$NON-NLS-1$
                {
                    if (metadataSeen)
                        throw new IOException("Shared submission contains duplicate metadata."); //$NON-NLS-1$
                    metadataSeen = true;
                    String text = new String(readLimited(zip, 8192), StandardCharsets.UTF_8);
                    try (Reader reader = new StringReader(text))
                    {
                        metadata.load(reader);
                    }
                }
                else if ("portfolio".equals(entry.getName())) //$NON-NLS-1$
                {
                    if (contentSeen)
                        throw new IOException("Shared submission contains duplicate portfolio data."); //$NON-NLS-1$
                    contentSeen = true;
                    content = readLimited(zip, MAX_PORTFOLIO_SIZE);
                }
                else
                {
                    throw new IOException("Shared submission contains an unexpected entry."); //$NON-NLS-1$
                }
            }
        }
        catch (RuntimeException e)
        {
            throw new IOException("Shared submission metadata is invalid.", e); //$NON-NLS-1$
        }
        if (!metadataSeen || !contentSeen)
            throw new IOException("Shared submission is missing metadata or portfolio data."); //$NON-NLS-1$
        String id = metadata.getProperty("id"); //$NON-NLS-1$
        String submissionWorkspace = metadata.getProperty("workspaceId"); //$NON-NLS-1$
        String parent = metadata.getProperty("parentRevision"); //$NON-NLS-1$
        String hash = metadata.getProperty("contentHash"); //$NON-NLS-1$
        String actor = metadata.getProperty("actorId"); //$NON-NLS-1$
        String created = metadata.getProperty("createdAt"); //$NON-NLS-1$
        if (!Integer.toString(PROTOCOL_VERSION).equals(metadata.getProperty("protocolVersion"))) //$NON-NLS-1$
            throw new IOException("Shared submission uses an unsupported protocol version."); //$NON-NLS-1$
        validateUuid(id);
        validateWorkspaceId(submissionWorkspace);
        validateRevision(parent);
        validateRevision(hash);
        validateActor(actor);
        if (!workspaceId.equals(submissionWorkspace) || !id.equals(packageFile.getFileName().toString()
                        .substring(0, packageFile.getFileName().toString().length() - CHANGE_SUFFIX.length())))
            throw new IOException("Shared submission belongs to another workspace or has a mismatched name."); //$NON-NLS-1$
        if (content == null || !hash.equals(contentHash(content)))
            throw new IOException("Shared submission checksum does not match."); //$NON-NLS-1$
        try
        {
            return new Submission(id, submissionWorkspace, parent, hash, actor, Instant.parse(created), content);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Shared submission timestamp is invalid.", e); //$NON-NLS-1$
        }
    }

    private void writeSubmission(Path target, String id, String parent, String hash, byte[] content) throws IOException
    {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID()); //$NON-NLS-1$
        Properties metadata = new Properties();
        metadata.setProperty("protocolVersion", Integer.toString(PROTOCOL_VERSION)); //$NON-NLS-1$
        metadata.setProperty("id", id); //$NON-NLS-1$
        metadata.setProperty("workspaceId", workspaceId); //$NON-NLS-1$
        metadata.setProperty("parentRevision", parent); //$NON-NLS-1$
        metadata.setProperty("contentHash", hash); //$NON-NLS-1$
        metadata.setProperty("actorId", actorId); //$NON-NLS-1$
        metadata.setProperty("createdAt", Instant.now().toString()); //$NON-NLS-1$
        try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW);
                        ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8))
        {
            ZipEntry metadataEntry = new ZipEntry("metadata.properties"); //$NON-NLS-1$
            zip.putNextEntry(metadataEntry);
            ByteArrayOutputStream metadataBytes = new ByteArrayOutputStream();
            metadata.store(metadataBytes, "Portfolio Performance shared portfolio"); //$NON-NLS-1$
            zip.write(metadataBytes.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("portfolio")); //$NON-NLS-1$
            zip.write(content);
            zip.closeEntry();
        }
        moveIntoPlace(temporary, target, false);
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true)
        {
            try
            {
                FileLock lock = channel.tryLock();
                if (lock != null)
                    return lock;
            }
            catch (OverlappingFileLockException e)
            {
                // Another local owner process is finishing an acceptance.
            }
            if (System.nanoTime() >= deadline)
                throw new IOException("Another owner process is updating the shared portfolio."); //$NON-NLS-1$
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the owner lock.", e); //$NON-NLS-1$
            }
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0)
        {
            if (count > limit - total)
                throw new IOException("Shared portfolio data exceeds its size limit."); //$NON-NLS-1$
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static Properties readProperties(Path path) throws IOException
    {
        if (!Files.isRegularFile(path))
            throw new IOException("Shared workspace is incomplete; wait for folder synchronization."); //$NON-NLS-1$
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeProperties(Path path, Properties properties, boolean replace) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "Portfolio Performance shared portfolio"); //$NON-NLS-1$
        writeBytes(path, output.toByteArray(), replace);
    }

    private static void writeText(Path path, String value, boolean replace) throws IOException
    {
        writeBytes(path, value.getBytes(StandardCharsets.UTF_8), replace);
    }

    private static void writeBytes(Path path, byte[] bytes, boolean replace) throws IOException
    {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID()); //$NON-NLS-1$
        try
        {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveIntoPlace(temporary, path, replace);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveIntoPlace(Path source, Path target, boolean replace) throws IOException
    {
        try
        {
            if (replace)
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            if (replace)
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else
                Files.move(source, target);
        }
    }

    private static String readReceipt(Path path) throws IOException
    {
        String revision = Files.readString(path, StandardCharsets.UTF_8).strip();
        validateRevision(revision);
        return revision;
    }

    public static String contentHash(byte[] content)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content); //$NON-NLS-1$
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest)
                result.append(String.format("%02x", value)); //$NON-NLS-1$
            return result.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new AssertionError(e);
        }
    }

    static void validateContent(byte[] content) throws IOException
    {
        if (content.length > MAX_PORTFOLIO_SIZE)
            throw new IOException("Portfolio exceeds the 50 MiB shared-workspace limit."); //$NON-NLS-1$
    }

    private static void validateRevision(String revision)
    {
        if (revision == null || !revision.matches("[0-9a-f]{64}")) //$NON-NLS-1$
            throw new IllegalArgumentException("Invalid shared portfolio revision."); //$NON-NLS-1$
    }

    private static void validateUuid(String value)
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

    private static void validateWorkspaceId(String value)
    {
        validateUuid(value);
    }

    private static void validateActor(String value)
    {
        validateUuid(value);
    }

    /** Best-effort cleanup for tests and explicit workspace removal tooling. */
    static void deleteRecursively(Path root) throws IOException
    {
        if (!Files.exists(root))
            return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException
            {
                if (exception != null)
                    throw exception;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
