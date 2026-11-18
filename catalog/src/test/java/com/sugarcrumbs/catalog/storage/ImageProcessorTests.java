package com.sugarcrumbs.catalog.storage;

import com.sugarcrumbs.catalog.storage.ImageProcessor.ProcessedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The upload pipeline (#25): magic bytes decide, metadata goes, size is capped, a thumbnail is made. */
class ImageProcessorTests {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void aTransparentPngStaysAPngWithItsTransparency() {
        ProcessedImage out = processor.process(TestImages.png(300, 200, true));

        assertThat(out.extension()).isEqualTo(".png");
        BufferedImage image = TestImages.read(out.image());
        assertThat(image.getWidth()).isEqualTo(300);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(new Color(image.getRGB(10, 10), true).getAlpha()).isBetween(120, 136);
    }

    @Test
    void aJpegStaysAJpeg() {
        ProcessedImage out = processor.process(TestImages.jpeg(300, 200));

        assertThat(out.extension()).isEqualTo(".jpg");
        assertThat(TestImages.read(out.image()).getWidth()).isEqualTo(300);
    }

    @Test
    void aWebpIsReadAndStoredAsJpeg() {
        ProcessedImage out = processor.process(TestImages.WEBP);

        assertThat(out.extension()).isEqualTo(".jpg");
        assertThat(TestImages.read(out.image()).getWidth()).isEqualTo(1);
    }

    /** What the bytes are decides, not what the client called them: the storage ignores Content-Type. */
    @ParameterizedTest
    @ValueSource(strings = {"", "hello", "<svg xmlns='http://www.w3.org/2000/svg'/>", "GIF89a......"})
    void anythingButPngJpegOrWebpIsRefused(String content) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> processor.process(content.getBytes(StandardCharsets.US_ASCII)))
                .withMessage("not a PNG, JPEG or WebP image");
    }

    @Test
    void validMagicBytesInFrontOfGarbageAreA400NotA500() {
        byte[] jpeg = TestImages.jpeg(50, 50);
        byte[] truncated = Arrays.copyOf(jpeg, 40);

        assertThatIllegalArgumentException().isThrownBy(() -> processor.process(truncated))
                .withMessage("the image could not be read");
    }

    /** A few dozen bytes claiming 30 000 x 30 000 px: refused from the header, without decoding. */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDecompressionBombIsRefusedBeforeDecoding() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> processor.process(TestImages.pngHeaderClaiming(30_000, 30_000)))
                .withMessageContaining("too large");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> processor.process(TestImages.pngHeaderClaiming(9_000, 9_000)))
                .as("under the side limit but over the pixel budget").withMessageContaining("too large");
    }

    @Test
    void exifIsGoneButItsOrientationIsApplied() {
        // Orientation 6: the camera was turned 90 degrees clockwise, so the stored pixels lie on their side.
        byte[] photo = TestImages.jpegWithExif(400, 200, 6);
        assertThat(new String(photo, StandardCharsets.ISO_8859_1)).contains("Exif", TestImages.SECRET);

        ProcessedImage out = processor.process(photo);

        for (byte[] file : new byte[][] {out.image(), out.thumbnail()}) {
            String text = new String(file, StandardCharsets.ISO_8859_1);
            assertThat(text).doesNotContain("Exif").doesNotContain(TestImages.SECRET);
        }
        BufferedImage upright = TestImages.read(out.image());
        assertThat(upright.getWidth()).isEqualTo(200);
        assertThat(upright.getHeight()).isEqualTo(400);
        // The red top-left quarter of the stored pixels ends up top-right once turned clockwise.
        assertThat(isRed(upright.getRGB(180, 20))).isTrue();
        assertThat(isRed(upright.getRGB(20, 20))).isFalse();
    }

    @Test
    void largeImagesAreCappedAndTheThumbnailIsSmallKeepingTheShape() {
        ProcessedImage out = processor.process(TestImages.jpeg(3000, 1500));

        BufferedImage image = TestImages.read(out.image());
        BufferedImage thumbnail = TestImages.read(out.thumbnail());
        assertThat(image.getWidth()).isEqualTo(ImageProcessor.MAX_SIDE);
        assertThat(image.getHeight()).isEqualTo(1000);
        assertThat(thumbnail.getWidth()).isEqualTo(ImageProcessor.THUMBNAIL_SIDE);
        assertThat(thumbnail.getHeight()).isEqualTo(200);
    }

    @Test
    void smallImagesAreNeverScaledUp() {
        ProcessedImage out = processor.process(TestImages.png(120, 80, false));

        assertThat(TestImages.read(out.image()).getWidth()).isEqualTo(120);
        assertThat(TestImages.read(out.thumbnail()).getWidth()).isEqualTo(120);
    }

    @Test
    void everyOrientationTurnsTheImageTheRightWay() {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());   // stored: [R W]
        source.setRGB(1, 0, Color.WHITE.getRGB());

        assertThat(isRed(ImageProcessor.orient(source, 1).getRGB(0, 0))).isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 2).getRGB(1, 0))).as("mirrored").isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 3).getRGB(1, 0))).as("180").isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 4).getRGB(0, 0))).as("flipped vertically").isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 5).getRGB(0, 0))).as("transposed").isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 6).getRGB(0, 0))).as("90 clockwise: R on top").isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 7).getRGB(0, 1))).as("transversed").isTrue();
        assertThat(isRed(ImageProcessor.orient(source, 8).getRGB(0, 1))).as("90 counter-clockwise: R below").isTrue();
        assertThat(ImageProcessor.orient(source, 6).getWidth()).isEqualTo(1);
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() < 60 && c.getBlue() < 60;
    }
}
