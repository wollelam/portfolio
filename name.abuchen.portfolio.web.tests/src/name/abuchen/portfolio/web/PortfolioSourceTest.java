package name.abuchen.portfolio.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@SuppressWarnings("nls")
public class PortfolioSourceTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void loadsRealXmlWithoutChangingTheFileAndVersionsItsContents() throws Exception
    {
        var path = fixture("/scenarios/currency_sample.xml");
        var before = Files.readAllBytes(path);
        var source = PortfolioSource.file(path, null);
        var first = source.load();
        assertFalse(first.demo());
        assertFalse(first.client().getSecurities().isEmpty());
        assertEquals(first.revision(), source.load().revision());
        org.junit.Assert.assertArrayEquals(before, Files.readAllBytes(path));
        assertEquals(64, first.revision().length());
    }

    @Test
    public void supportsEncryptedBinaryFilesAndRejectsMissingPassword() throws Exception
    {
        var path = fixture("/fileversions/client52.binary+pwd.portfolio");
        assertThrows(IOException.class, () -> PortfolioSource.file(path, null).load());
        char[] password = "123456".toCharArray();
        try
        {
            var loaded = PortfolioSource.file(path, password).load();
            assertFalse(loaded.client().getSecurities().isEmpty());
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
    }

    private Path fixture(String name) throws IOException
    {
        var path = temporary.newFile().toPath();
        try (var input = getClass().getResourceAsStream(name))
        {
            if (input == null)
                throw new IOException("Fixture missing: " + name);
            Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return path;
    }
}
