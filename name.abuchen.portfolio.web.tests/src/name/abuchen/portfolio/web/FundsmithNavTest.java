package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;

@SuppressWarnings("nls")
public class FundsmithNavTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void acceptsOnlyDatedMatchingPublishedNavAndRetainsLastGoodPrice() throws Exception
    {
        var clock = Clock.fixed(LocalDate.of(2026, 11, 4).atTime(16, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var file = temporary.newFile().toPath();
        var reader = new FundsmithNav(file, clock);
        assertNull(reader.price());
        var current = new FundsmithNav.Price("2026-11-04", FundsmithNav.ISIN, "GBX", "668.27",
                        FundsmithNav.SOURCE, clock.instant().toString());
        Files.writeString(file, new Gson().toJson(current));
        assertEquals(current, reader.price());
        Files.writeString(file, new Gson().toJson(new FundsmithNav.Price("2026-11-05", FundsmithNav.ISIN, "GBX",
                        "700", FundsmithNav.SOURCE, clock.instant().toString())));
        assertEquals(current, reader.price());
        Files.writeString(file, new Gson().toJson(new FundsmithNav.Price("2026-11-04", "wrong", "GBX",
                        "700", FundsmithNav.SOURCE, clock.instant().toString())));
        assertEquals(current, reader.price());
        Files.writeString(file, new Gson().toJson(new FundsmithNav.Price("2026-11-03", FundsmithNav.ISIN, "GBX",
                        "650", FundsmithNav.SOURCE, clock.instant().toString())));
        assertEquals(current, reader.price());
    }

    @Test
    public void parsesTheDatedTIncRowAndRejectsAmbiguousRows() throws Exception
    {
        var html = "<h2>Price Data 4<sup>th</sup> November 2026</h2><table>"
                        + "<tr><th>Share Class</th><th>OCF*</th><th>Minimum</th><th>Price p</th></tr>"
                        + "<tr><th>T Class Acc</th><td>1%</td><td>£1,000</td><td>740.41</td></tr>"
                        + "<tr><th>T Class Inc</th><td>1%</td><td>£1,000</td><td>668.27</td></tr></table>";
        var at = Clock.fixed(LocalDate.of(2026, 11, 4).atTime(16, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC).instant();
        assertEquals("668.27", FundsmithNav.parse(html, at).price());
        assertEquals("2026-11-04", FundsmithNav.parse(html, at).date());
        var duplicate = "<tr><th>T Class Inc</th><td>1%</td><td>£1,000</td><td>668.27</td></tr>";
        assertThrows(IOException.class, () -> FundsmithNav.parse(html.replace("</table>", duplicate + "</table>"), at));
    }
}
