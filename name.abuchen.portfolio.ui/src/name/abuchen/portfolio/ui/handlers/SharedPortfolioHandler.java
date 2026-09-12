package name.abuchen.portfolio.ui.handlers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.EPartService.PartState;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.model.SharedPortfolioSession;
import name.abuchen.portfolio.model.SharedPortfolioWorkspace;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.editor.ClientInput;

/** Entry point for the first desktop shared-workspace workflow. */
public class SharedPortfolioHandler
{
    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell shell,
                    @Optional @Named(IServiceConstants.ACTIVE_PART) MPart activePart, MApplication app,
                    EPartService partService, EModelService modelService)
    {
        ClientInput active = MenuHelper.getActiveClientInput(activePart, false).orElse(null);
        String[] actions = active == null
                        ? new String[] { "Join workspace", "Cancel" } //$NON-NLS-1$ //$NON-NLS-2$
                        : new String[] { "Create workspace", "Join workspace", "Submit changes", "Refresh working copy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                        "Review submissions", "Show status", "Cancel" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int selected = new MessageDialog(shell, "Shared portfolio", null, //$NON-NLS-1$
                        "Use a synchronized folder as a submission mailbox. One owner applies changes to the master.", //$NON-NLS-1$
                        MessageDialog.QUESTION, actions, actions.length - 1).open();
        if (selected < 0 || "Cancel".equals(actions[selected])) //$NON-NLS-1$
            return;

        try
        {
            switch (actions[selected])
            {
                case "Create workspace": //$NON-NLS-1$
                    create(shell, active);
                    break;
                case "Join workspace": //$NON-NLS-1$
                    join(shell, activePart, app, partService, modelService);
                    break;
                case "Submit changes": //$NON-NLS-1$
                    submit(shell, active);
                    break;
                case "Refresh working copy": //$NON-NLS-1$
                    refresh(shell, active);
                    break;
                case "Review submissions": //$NON-NLS-1$
                    review(shell, active);
                    break;
                case "Show status": //$NON-NLS-1$
                    status(shell, active);
                    break;
                default:
                    break;
            }
        }
        catch (IOException | IllegalArgumentException | IllegalStateException e)
        {
            MessageDialog.openError(shell, "Shared portfolio", e.getMessage()); //$NON-NLS-1$
        }
    }

    private void create(Shell shell, ClientInput input) throws IOException
    {
        if (input == null || input.getFile() == null)
            throw new IOException("Save the portfolio locally before creating a shared workspace."); //$NON-NLS-1$
        if (input.isDirty())
            input.save(shell);
        if (input.isDirty())
            return;
        DirectoryDialog dialog = new DirectoryDialog(shell, SWT.OPEN);
        dialog.setText("Select an empty synchronized folder for the shared workspace"); //$NON-NLS-1$
        String selected = dialog.open();
        if (selected == null)
            return;
        input.createSharedWorkspace(Path.of(selected));
        MessageDialog.openInformation(shell, "Shared portfolio", //$NON-NLS-1$
                        "Workspace created. This machine is the owner; use Review submissions to apply changes."); //$NON-NLS-1$
    }

    private void join(Shell shell, MPart activePart, MApplication app, EPartService partService,
                    EModelService modelService) throws IOException
    {
        DirectoryDialog directory = new DirectoryDialog(shell, SWT.OPEN);
        directory.setText("Select the synchronized shared-workspace folder"); //$NON-NLS-1$
        String selectedDirectory = directory.open();
        if (selectedDirectory == null)
            return;

        FileDialog file = new FileDialog(shell, SWT.SAVE);
        file.setText("Choose a new local working file"); //$NON-NLS-1$
        file.setFilterExtensions(new String[] { "*.portfolio", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        String selectedFile = file.open();
        if (selectedFile == null)
            return;

        SharedPortfolioSession.join(Path.of(selectedDirectory), Path.of(selectedFile));
        MPart part = partService.createPart(UIConstants.Part.PORTFOLIO);
        part.setLabel(Path.of(selectedFile).getFileName().toString());
        part.setTooltip(selectedFile);
        part.getPersistedState().put(UIConstants.PersistedState.FILENAME, Path.of(selectedFile).toAbsolutePath().toString());
        if (activePart != null)
            activePart.getParent().getChildren().add(part);
        else
            ((MPartStack) modelService.find(UIConstants.PartStack.MAIN, app)).getChildren().add(part);
        part.setVisible(true);
        part.getParent().setVisible(true);
        partService.showPart(part, PartState.ACTIVATE);
    }

    private void submit(Shell shell, ClientInput input) throws IOException
    {
        requireInput(input);
        if (input.isDirty())
            input.save(shell);
        if (input.isDirty())
            return;
        String id = input.submitSharedChanges();
        MessageDialog.openInformation(shell, "Shared portfolio", //$NON-NLS-1$
                        id == null ? "There are no local changes to submit." : "Submitted changes as " + id + "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void refresh(Shell shell, ClientInput input) throws IOException
    {
        requireInput(input);
        boolean refreshed = input.refreshSharedChanges();
        MessageDialog.openInformation(shell, "Shared portfolio", //$NON-NLS-1$
                        refreshed ? "The working file was refreshed. Close and reopen the editor to load it." //$NON-NLS-1$
                                        : "The working file is already current."); //$NON-NLS-1$
    }

    private void review(Shell shell, ClientInput input) throws IOException
    {
        requireInput(input);
        if (!input.getSharedPortfolioSession().isOwner())
            throw new IOException("Only the workspace owner can review submissions."); //$NON-NLS-1$
        List<SharedPortfolioWorkspace.Submission> pending = input.getSharedPortfolioSession().pending();
        if (pending.isEmpty())
        {
            MessageDialog.openInformation(shell, "Shared portfolio", "There are no pending submissions."); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String choices = pending.stream().map(item -> item.id() + " (" + item.actorId() + ")") //$NON-NLS-1$ //$NON-NLS-2$
                        .collect(Collectors.joining("\n")); //$NON-NLS-1$
        InputDialog dialog = new InputDialog(shell, "Accept submission", //$NON-NLS-1$
                        "Enter a submission ID from the pending list:\n\n" + choices, pending.get(0).id(), null);
        if (dialog.open() != Window.OK)
            return;
        input.acceptSharedChanges(dialog.getValue().trim());
        MessageDialog.openInformation(shell, "Shared portfolio", //$NON-NLS-1$
                        "Submission accepted. Close and reopen the editor to load the updated master."); //$NON-NLS-1$
    }

    private void status(Shell shell, ClientInput input) throws IOException
    {
        requireInput(input);
        SharedPortfolioSession session = input.getSharedPortfolioSession();
        String text = "Role: " + (session.isOwner() ? "owner" : "contributor") //$NON-NLS-1$ //$NON-NLS-2$
                        + "\nWorkspace: " + session.getWorkspaceDirectory() //$NON-NLS-1$
                        + "\nLocal parent: " + session.getParentRevision() //$NON-NLS-1$
                        + "\nMaster head: " + session.getWorkspace().currentRevision() //$NON-NLS-1$
                        + "\nPending submissions: " + session.pending().size(); //$NON-NLS-1$ //$NON-NLS-2$
        MessageDialog.openInformation(shell, "Shared portfolio status", text); //$NON-NLS-1$
    }

    private void requireInput(ClientInput input) throws IOException
    {
        if (input == null || input.getSharedPortfolioSession() == null)
            throw new IOException("This portfolio is not connected to a shared workspace."); //$NON-NLS-1$
    }
}
