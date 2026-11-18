package com.sugarcrumbs.catalog.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskImageStorageServiceTests {

    @TempDir
    Path tempDir;

    private Path root;
    private LocalDiskImageStorageService storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("uploads");
        storage = new LocalDiskImageStorageService(new ImageProcessor(), root.toString(), "/uploads/");
    }

    @Test
    void storeWritesTheImageAndItsThumbnailUnderARandomNameAndReturnsItsPublicUrl() {
        String url = storage.store(png(), "items");

        assertThat(url).matches("/uploads/items/[0-9a-f-]{36}\\.png");
        assertThat(TestImages.read(readBytes(fileFor(url))).getWidth()).isEqualTo(60);
        String thumbnailUrl = storage.thumbnailUrl(url);
        assertThat(thumbnailUrl).isEqualTo(url.replace(".png", ".thumb.png"));
        assertThat(fileFor(thumbnailUrl)).exists();
    }

    /** The bytes decide: a JPEG sent as "image/png" named cake.png is stored as the JPEG it is. */
    @Test
    void theDeclaredTypeAndNameAreIgnored() {
        MockMultipartFile disguised = new MockMultipartFile("file", "cake.png", "image/png", TestImages.jpeg(40, 40));

        assertThat(storage.store(disguised, "items")).endsWith(".jpg");
    }

    @Test
    void storeRejectsAnythingThatIsNotARealImage() {
        MockMultipartFile svg = new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes());
        MockMultipartFile fakePng = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> storage.store(svg, "items")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(fakePng, "items")).isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("items")).doesNotExist();
    }

    @Test
    void imagesFromElsewhereOrWithoutAThumbnailAreTheirOwnThumbnail() throws IOException {
        Files.createDirectories(root.resolve("items"));
        Files.write(root.resolve("items/old.png"), TestImages.png(10, 10, false));

        assertThat(storage.thumbnailUrl("/uploads/items/old.png")).isEqualTo("/uploads/items/old.png");
        assertThat(storage.thumbnailUrl("https://cdn.example.com/b.png")).isEqualTo("https://cdn.example.com/b.png");
    }

    @Test
    void storeCannotEscapeTheRootThroughTheSubfolder() {
        String url = storage.store(png(), "../../etc");

        assertThat(url).startsWith("/uploads/etc/");
        assertThat(fileFor(url).normalize()).startsWith(root.toAbsolutePath());
    }

    @Test
    void deleteRemovesAStoredFileAndItsThumbnail() {
        String url = storage.store(png(), "items");
        Path thumbnail = fileFor(storage.thumbnailUrl(url));

        storage.delete(url);

        assertThat(fileFor(url)).doesNotExist();
        assertThat(thumbnail).doesNotExist();
    }

    @Test
    void deleteIgnoresAFileThatIsAlreadyGone() {
        String url = storage.store(png(), "items");
        storage.delete(url);

        storage.delete(url);

        assertThat(fileFor(url)).doesNotExist();
    }

    @Test
    void deleteIgnoresUrlsThisStorageDidNotIssue() throws IOException {
        Path lookalike = Files.writeString(root.resolve("photo.png"), "keep");

        storage.delete("https://cdn.example.com/uploads/photo.png");
        storage.delete("/elsewhere/photo.png");
        storage.delete(null);

        assertThat(lookalike).exists();
    }

    @Test
    void deleteNeverTouchesFilesOutsideTheRoot() throws IOException {
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "keep");

        storage.delete("/uploads/../secret.txt");
        storage.delete("/uploads/items/../../secret.txt");

        assertThat(secret).exists();
    }

    @Test
    void deleteNeverRemovesADirectory() throws IOException {
        Path items = Files.createDirectories(root.resolve("items"));

        storage.delete("/uploads/items");
        storage.delete("/uploads/");

        assertThat(items).isDirectory();
        assertThat(root).isDirectory();
    }

    private Path fileFor(String url) {
        return root.resolve(url.substring("/uploads/".length()));
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "cake.png", "image/png", TestImages.png(60, 40, false));
    }
}
