package com.sugarcrumbs.catalog.storage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/** Real image files built in code, so tests need no binary fixtures. */
public final class TestImages {

    /** A 1x1 lossless WebP. */
    public static final byte[] WEBP = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

    /** Text an EXIF block can carry (GPS, camera serial...) that must not survive processing. */
    public static final String SECRET = "GPS 52.5200N 13.4050E";

    private TestImages() {
    }

    /** {@code width x height}, left half red, right half blue; half transparent when {@code alpha}. */
    public static byte[] png(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(alpha ? new Color(255, 0, 0, 128) : Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return write(image, "png");
    }

    /** A JPEG whose top-left quarter is red and the rest white. */
    public static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height / 2);
        g.dispose();
        return write(image, "jpeg");
    }

    /**
     * {@link #jpeg} with an EXIF (APP1) segment right after SOI: TIFF orientation {@code orientation}
     * and an ImageDescription holding {@link #SECRET}.
     */
    public static byte[] jpegWithExif(int width, int height, int orientation) {
        byte[] jpeg = jpeg(width, height);
        byte[] description = (SECRET + "\0").getBytes(StandardCharsets.US_ASCII);
        // Big-endian TIFF: header, one IFD with two entries, then the description's bytes.
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + description.length);
        tiff.put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(description.length).putInt(8 + 2 + 2 * 12 + 4);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(description);
        byte[] exif = ByteBuffer.allocate(6 + tiff.capacity())
                .put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(tiff.array()).array();

        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 4 + exif.length);
        out.put(jpeg, 0, 2);                                                   // SOI
        out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (exif.length + 2)).put(exif);
        out.put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }

    /**
     * Just a PNG header claiming {@code width x height}: a few dozen bytes that would decode into
     * gigabytes, if anything tried.
     */
    public static byte[] pngHeaderClaiming(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(4 + 13)
                .put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + ihdr.capacity() + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue())
                .array();
    }

    public static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
