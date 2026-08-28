package name.abuchen.portfolio.ui.jobs.priceupdate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.Test;

@SuppressWarnings("nls")
public class PriceUpdateModeTest
{
    @Test
    public void modeCodesRoundTrip()
    {
        for (var mode : PriceUpdateMode.values())
            assertThat(PriceUpdateMode.fromCode(mode.getCode()), is(mode));
    }

    @Test
    public void missingOrUnknownModeFallsBackToLive()
    {
        assertThat(PriceUpdateMode.fromCode(null), is(PriceUpdateMode.LIVE));
        assertThat(PriceUpdateMode.fromCode(""), is(PriceUpdateMode.LIVE));
        assertThat(PriceUpdateMode.fromCode("unknown"), is(PriceUpdateMode.LIVE));
    }
}
