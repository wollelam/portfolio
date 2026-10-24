package name.abuchen.portfolio.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import name.abuchen.portfolio.model.Attributable;
import name.abuchen.portfolio.model.Client;

/** Validated, embedded logos belonging to one accepted portfolio snapshot. */
@SuppressWarnings("nls")
final class PortfolioIcons
{
    private static final String PREFIX = "data:image/png;base64,";
    private static final int MAX_BYTES = 128 * 1024;
    private final Map<String, byte[]> images = new HashMap<>();
    private final Map<String, String> urls = new HashMap<>();

    PortfolioIcons(Client client)
    {
        client.getSecurities().forEach(item -> add(client, item, item.getUUID()));
        client.getAccounts().forEach(item -> add(client, item, item.getUUID()));
    }

    private void add(Client client, Attributable item, String uuid)
    {
        client.getSettings().getOptionalLogoAttributeType(item.getClass()).ifPresent(attribute -> {
            var image = decode(item.getAttributes().get(attribute));
            if (image != null)
            {
                images.put(uuid, image);
                urls.put(uuid, "/api/icon?id=" + uuid + "&v=" + version(image));
            }
        });
    }

    byte[] image(String uuid)
    {
        var image = images.get(uuid);
        return image == null ? null : image.clone();
    }

    String url(String uuid)
    {
        return urls.get(uuid);
    }

    Map<String, String> urls()
    {
        return Map.copyOf(urls);
    }

    private static String version(byte[] image)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        }
        catch (NoSuchAlgorithmException failure)
        {
            throw new IllegalStateException(failure);
        }
    }

    static byte[] decode(Object value)
    {
        if (!(value instanceof String text) || !text.startsWith(PREFIX)
                        || text.length() > PREFIX.length() + ((MAX_BYTES + 2) / 3) * 4)
            return null;
        try
        {
            var bytes = Base64.getDecoder().decode(text.substring(PREFIX.length()));
            if (bytes.length < 33 || bytes.length > MAX_BYTES)
                return null;
            var header = ByteBuffer.wrap(bytes);
            if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13 || header.getInt() != 0x49484452)
                return null;
            int width = header.getInt();
            int height = header.getInt();
            // Bound dimensions before decoding, including malformed compressed input.
            if (width < 1 || height < 1 || width > 256 || height > 256)
                return null;
            BufferedImage input;
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
            {
                var readers = ImageIO.getImageReadersByFormatName("png");
                if (!readers.hasNext())
                    return null;
                var reader = readers.next();
                try
                {
                    reader.setInput(stream, true, true);
                    input = reader.read(0);
                }
                finally
                {
                    reader.dispose();
                }
            }
            if (input == null || input.getWidth() != width || input.getHeight() != height)
                return null;
            var clean = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = clean.createGraphics();
            try
            {
                graphics.drawImage(input, 0, 0, null);
            }
            finally
            {
                graphics.dispose();
            }
            var output = new ByteArrayOutputStream();
            try (var stream = new MemoryCacheImageOutputStream(output))
            {
                ImageIO.write(clean, "png", stream);
            }
            return output.toByteArray();
        }
        catch (IOException | RuntimeException failure)
        {
            return null;
        }
    }
}
