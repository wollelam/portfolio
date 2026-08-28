package name.abuchen.portfolio.ui.jobs.priceupdate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Collections;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;

@SuppressWarnings("nls")
public class PriceUpdateRequestTest
{
    @Test
    public void modificationStateIsStickyWhileNotificationStateCanBeReset()
    {
        var request = new PriceUpdateRequest(new Client(), Collections.emptyList(), false, false);

        assertThat(request.isModified(), is(false));
        assertThat(request.getAndResetUnannouncedModification(), is(false));

        request.markModified();

        assertThat(request.isModified(), is(true));
        assertThat(request.getAndResetUnannouncedModification(), is(true));
        assertThat(request.isModified(), is(true));
        assertThat(request.getAndResetUnannouncedModification(), is(false));

        request.markModified();

        assertThat(request.isModified(), is(true));
        assertThat(request.getAndResetUnannouncedModification(), is(true));
    }
}
