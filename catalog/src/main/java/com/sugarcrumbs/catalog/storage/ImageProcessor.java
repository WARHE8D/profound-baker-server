package com.sugarcrumbs.catalog.storage;

import com.twelvemonkeys.contrib.exif.EXIFUtilities;
import com.twelvemonkeys.contrib.exif.Orientation;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Turns an uploaded file into images that are safe to publish (#25). Nothing the client sends is
 * trusted: the format comes from the file's first bytes, not its Content-Type or name; the
 * dimensions are read from the header and checked before a single pixel is decoded, so a small file
 * claiming to be 50 000 px wide (a decompression bomb) is refused cheaply; and the output is a new
 * file encoded from the decoded pixels, so EXIF (GPS position, camera serial), comments and anything
 * else carried in the original never reach the disk. A photo's EXIF orientation is applied first,
 * so phone pictures still stand the right way up once it is gone.
 *
 * <p>Output: the image scaled down to at most {@value #MAX_SIDE} px on its longer side, and a
 * thumbnail of at most {@value #THUMBNAIL_SIDE} px. A PNG stays PNG (it may be transparent, or a
 * drawing JPEG would smudge); JPEG and WebP become JPEG (ImageIO reads WebP but cannot write it).
 */
@Component
public class ImageProcessor {

    /** Longest side kept. A storefront never shows more; the rest would only cost bandwidth. */
    static final int MAX_SIDE = 2000;
    static final int THUMBNAIL_SIDE = 400;

    /** Refused before decoding: larger than any real photo, and decoding would take gigabytes. */
    static final int MAX_INPUT_SIDE = 10_000;
    static final long MAX_INPUT_PIXELS = 50_000_000L;

    private static final float JPEG_QUALITY = 0.85f;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** The formats accepted, told apart by their magic bytes. */
    enum Format {
        PNG("png"), JPEG("jpeg"), WEBP("webp");

        final String readerName;

        Format(String readerName) {
            this.readerName = readerName;
        }
    }

    /**
     * @param image     the full image, re-encoded
     * @param thumbnail the same, small
     * @param extension of both, with its dot: what the bytes really are
     */
    public record ProcessedImage(byte[] image, byte[] thumbnail, String extension) {
    }

    public ImageProcessor() {
        // ImageIO registers plugins once, with whatever classloader touches it first; in the executable
        // jar that may not see the nested TwelveMonkeys jars, and WebP uploads would then fail.
        ImageIO.scanForPlugins();
    }

    /**
     * @throws IllegalArgumentException (400) if the bytes are not a PNG, JPEG or WebP image that can
     *                                  be read, or it is larger than {@value #MAX_INPUT_SIDE} px a side
     */
    public ProcessedImage process(byte[] bytes) {
        Format format = detect(bytes);
        BufferedImage decoded = decode(bytes, format);
        boolean png = format == Format.PNG;
        String writer = png ? "png" : "jpeg";
        // JPEG has no transparency: anything see-through is laid on white rather than on black.
        boolean opaque = !png || !decoded.getColorModel().hasAlpha();
        return new ProcessedImage(
                encode(scaleDown(decoded, MAX_SIDE, opaque), writer),
                encode(scaleDown(decoded, THUMBNAIL_SIDE, opaque), writer),
                png ? ".png" : ".jpg");
    }

    static Format detect(byte[] bytes) {
        if (startsWith(bytes, PNG_SIGNATURE)) {
            return Format.PNG;
        }
        if (startsWith(bytes, JPEG_SIGNATURE)) {
            return Format.JPEG;
        }
        if (bytes.length >= 12 && ascii(bytes, 0).equals("RIFF") && ascii(bytes, 8).equals("WEBP")) {
            return Format.WEBP;
        }
        throw new IllegalArgumentException("not a PNG, JPEG or WebP image");
    }

    private static BufferedImage decode(byte[] bytes, Format format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.readerName);
        if (!readers.hasNext()) {
            throw new IllegalStateException("no ImageIO reader for " + format);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, false);
            int width;
            int height;
            try {
                // From the header only: nothing is decoded yet.
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } catch (IOException | RuntimeException e) {
                throw unreadable(e);
            }
            if (width > MAX_INPUT_SIDE || height > MAX_INPUT_SIDE || (long) width * height > MAX_INPUT_PIXELS) {
                throw new IllegalArgumentException("image is too large (%d x %d px); at most %d px a side"
                        .formatted(width, height, MAX_INPUT_SIDE));
            }
            try {
                IIOMetadata metadata = format == Format.JPEG ? reader.getImageMetadata(0) : null;
                BufferedImage image = reader.read(0);
                Orientation orientation = metadata != null ? EXIFUtilities.findImageOrientation(metadata) : null;
                return orientation != null ? orient(image, orientation.value()) : image;
            } catch (IOException | RuntimeException e) {
                throw unreadable(e);
            }
        } catch (IOException e) {
            throw unreadable(e);
        } finally {
            reader.dispose();
        }
    }

    /**
     * Turns the stored pixels the way EXIF says the camera was held (TIFF orientation 1-8), so the
     * picture still stands upright once the EXIF is gone. 5-8 swap width and height.
     */
    static BufferedImage orient(BufferedImage image, int orientation) {
        double w = image.getWidth();
        double h = image.getHeight();
        // (m00, m10, m01, m11, m02, m12): where each source pixel lands.
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirrored upside down
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transposed
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // turned 90 degrees clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transversed
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // turned 90 degrees counter-clockwise
            default -> null;                                     // 1, or a value no camera writes
        };
        if (transform == null) {
            return image;
        }
        boolean swap = orientation >= 5;
        BufferedImage target = new BufferedImage(swap ? image.getHeight() : image.getWidth(),
                swap ? image.getWidth() : image.getHeight(),
                image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(image, transform, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** Valid magic bytes in front of corrupt or truncated data: the client's mistake, a 400, not a 500. */
    private static IllegalArgumentException unreadable(Exception cause) {
        return new IllegalArgumentException("the image could not be read", cause);
    }

    /**
     * Scales so the longer side is at most {@code maxSide}, never up. Always draws at least once, which
     * also turns any colour model the reader produced (CMYK, palette, 16-bit) into plain RGB(A).
     */
    static BufferedImage scaleDown(BufferedImage image, int maxSide, boolean opaque) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(image.getWidth(), image.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));

        BufferedImage current = image;
        // Halving in steps keeps detail that one big bilinear jump would alias away.
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2, opaque);
        }
        return draw(current, targetWidth, targetHeight, opaque);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, boolean opaque) {
        BufferedImage target = new BufferedImage(width, height,
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            if (opaque) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** A new file from the pixels alone: no metadata is passed to the writer, so none survives. */
    private static byte[] encode(BufferedImage image, String format) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format.equals("jpeg")) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("could not encode image", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }
}
