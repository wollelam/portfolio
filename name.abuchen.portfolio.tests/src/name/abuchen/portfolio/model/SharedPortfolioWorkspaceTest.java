package name.abuchen.portfolio.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SharedPortfolioWorkspaceTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void ownerAndContributorRoundTripThroughWorkspace() throws Exception
    {
        Path ownerFile = temporaryFolder.newFile("owner.portfolio").toPath(); //$NON-NLS-1$
        byte[] initial = "initial portfolio".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.write(ownerFile, initial);
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$

        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        Path contributorFile = ownerFile.resolveSibling("contributor.portfolio"); //$NON-NLS-1$
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory, contributorFile);

        assertTrue(owner.isOwner());
        assertFalse(contributor.isOwner());
        assertEquals(owner.getParentRevision(), contributor.getParentRevision());
        assertArrayEquals(initial, Files.readAllBytes(contributorFile));

        byte[] change = "contributor change".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.write(contributorFile, change);
        String submission = contributor.submit();
        assertNotNull(submission);
        assertEquals(1, owner.pending().size());
        assertEquals(submission, owner.pending().get(0).id());

        String revision = owner.accept(submission);
        assertEquals(revision, owner.getParentRevision());
        assertArrayEquals(change, Files.readAllBytes(ownerFile));
        assertTrue(owner.pending().isEmpty());
        assertEquals(revision, owner.accept(submission));
    }

    @Test
    public void staleContributionsAreKeptForReview() throws Exception
    {
        Path ownerFile = write("owner.portfolio", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        SharedPortfolioSession first = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("first.portfolio")); //$NON-NLS-1$
        SharedPortfolioSession second = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("second.portfolio")); //$NON-NLS-1$

        Files.writeString(first.getLocalFile(), "first"); //$NON-NLS-1$
        Files.writeString(second.getLocalFile(), "second"); //$NON-NLS-1$
        String firstSubmission = first.submit();
        String secondSubmission = second.submit();
        owner.accept(firstSubmission);

        assertThrows(SharedPortfolioWorkspace.ConflictException.class, () -> owner.accept(secondSubmission));
        assertEquals(1, owner.pending().size());
        assertEquals(secondSubmission, owner.pending().get(0).id());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), owner.getWorkspace().readMaster()); //$NON-NLS-1$
    }

    @Test
    public void contributorChainsSubmissionsAfterItsFirstProposal() throws Exception
    {
        Path ownerFile = write("owner.portfolio", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("contributor.portfolio")); //$NON-NLS-1$

        Files.writeString(contributor.getLocalFile(), "first"); //$NON-NLS-1$
        String first = contributor.submit();
        Files.writeString(contributor.getLocalFile(), "second"); //$NON-NLS-1$
        String second = contributor.submit();
        assertEquals(2, owner.pending().size());

        owner.accept(first);
        owner.accept(second);
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), owner.getWorkspace().readMaster()); //$NON-NLS-1$
        assertEquals(owner.getParentRevision(), SharedPortfolioWorkspace.contentHash("second".getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
    }

    @Test
    public void refreshRequiresCleanWorkingCopyAndUpdatesSidecar() throws Exception
    {
        Path ownerFile = write("owner.portfolio", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("contributor.portfolio")); //$NON-NLS-1$
        Files.writeString(ownerFile, "owner update"); //$NON-NLS-1$
        String ownerSubmission = owner.submit();
        Files.writeString(contributor.getLocalFile(), "local edit"); //$NON-NLS-1$
        assertThrows(SharedPortfolioWorkspace.DirtyException.class, contributor::refresh);
        Files.writeString(contributor.getLocalFile(), "base"); //$NON-NLS-1$
        assertFalse(contributor.refresh());
        owner.accept(ownerSubmission);
        assertTrue(contributor.refresh());
        assertArrayEquals("owner update".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(contributor.getLocalFile())); //$NON-NLS-1$

        SharedPortfolioSession reopened = SharedPortfolioSession.open(contributor.getLocalFile());
        assertEquals(contributor.getParentRevision(), reopened.getParentRevision());
        assertEquals(contributor.getWorkspaceDirectory(), reopened.getWorkspaceDirectory());
    }

    @Test
    public void refreshDoesNotDiscardAnUnacceptedProposal() throws Exception
    {
        Path ownerFile = write("owner.portfolio", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        SharedPortfolioSession contributor = SharedPortfolioSession.join(workspaceDirectory,
                        ownerFile.resolveSibling("contributor.portfolio")); //$NON-NLS-1$

        Files.writeString(contributor.getLocalFile(), "contributor proposal"); //$NON-NLS-1$
        contributor.submit();
        Files.writeString(ownerFile, "owner proposal"); //$NON-NLS-1$
        String ownerSubmission = owner.submit();
        owner.accept(ownerSubmission);

        assertThrows(SharedPortfolioWorkspace.ConflictException.class, contributor::refresh);
        assertEquals("contributor proposal", Files.readString(contributor.getLocalFile())); //$NON-NLS-1$
    }

    @Test
    public void openingAWorkspaceRejectsACorruptHeadSnapshot() throws Exception
    {
        Path ownerFile = write("owner.portfolio", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        SharedPortfolioSession owner = SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        String revision = owner.getWorkspace().currentRevision();
        Files.writeString(workspaceDirectory.resolve("revisions").resolve(revision + ".portfolio"), "corrupt"); //$NON-NLS-1$

        assertThrows(java.io.IOException.class, () -> SharedPortfolioWorkspace.open(workspaceDirectory,
                        java.util.UUID.randomUUID().toString()));
    }

    @Test
    public void malformedOrMissingWorkspaceIsRejected() throws Exception
    {
        Path directory = temporaryFolder.newFolder("empty").toPath(); //$NON-NLS-1$
        assertThrows(java.io.IOException.class, () -> SharedPortfolioWorkspace.open(directory,
                        java.util.UUID.randomUUID().toString()));
        Path local = write("local.portfolio", "data"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SharedPortfolioSession.tryOpen(local));
    }

    @Test
    public void duplicateWorkspaceAndLocalFilesAreNotOverwritten() throws Exception
    {
        Path ownerFile = write("owner.portfolio", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        Path workspaceDirectory = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile);
        assertThrows(java.io.IOException.class, () -> SharedPortfolioSession.createOwner(workspaceDirectory, ownerFile));
        Path local = write("existing.portfolio", "keep"); //$NON-NLS-1$ //$NON-NLS-2$
        assertThrows(java.io.IOException.class, () -> SharedPortfolioSession.join(workspaceDirectory, local));
        assertEquals("keep", Files.readString(local)); //$NON-NLS-1$
    }

    private Path write(String name, String content) throws Exception
    {
        Path file = temporaryFolder.newFile(name).toPath();
        Files.writeString(file, content);
        return file;
    }
}
