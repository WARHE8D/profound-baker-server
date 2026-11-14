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
        storage = new LocalDiskImageStorageService(root.toString(), "/uploads/");
    }

    @Test
    void storeWritesTheFileUnderARandomNameAndReturnsItsPublicUrl() throws IOException {
        String url = storage.store(png(), "items");

        assertThat(url).matches("/uploads/items/[0-9a-f-]{36}\\.png");
        assertThat(Files.readAllBytes(fileFor(url))).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    void storeRejectsTypesOutsideTheAllowlist() {
        MockMultipartFile svg = new MockMultipartFile("file", "x.svg", "image/svg+xml", new byte[]{1});

        assertThatThrownBy(() -> storage.store(svg, "items")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storeCannotEscapeTheRootThroughTheSubfolder() {
        String url = storage.store(png(), "../../etc");

        assertThat(url).startsWith("/uploads/etc/");
        assertThat(fileFor(url).normalize()).startsWith(root.toAbsolutePath());
    }

    @Test
    void deleteRemovesAStoredFile() {
        String url = storage.store(png(), "items");

        storage.delete(url);

        assertThat(fileFor(url)).doesNotExist();
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

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "cake.png", "image/png", new byte[]{1, 2, 3});
    }
}
