package name.abuchen.portfolio.ui.handlers;

import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.swt.widgets.Shell;

public class ReloadFileHandler
{
    @CanExecute
    public boolean canExecute(@Named(IServiceConstants.ACTIVE_PART) MPart part)
    {
        return MenuHelper.getActiveClientInput(part, false).filter(input -> input.getFile() != null).isPresent();
    }

    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_PART) MPart part,
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {
        MenuHelper.getActiveClientInput(part).ifPresent(input -> input.reload(shell));
    }
}
