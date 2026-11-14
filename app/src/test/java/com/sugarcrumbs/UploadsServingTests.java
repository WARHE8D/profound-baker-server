package com.sugarcrumbs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An uploaded image is served at the URL the API returns, and removing it
 * from its item deletes the file. Production serves the same files through
 * Caddy (checked in {@link DeploymentFilesTests}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class UploadsServingTests {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-path", () -> tempDir.resolve("uploads").toString());
    }

    @LocalServerPort
    int port;

    private RestClient http;

    @BeforeEach
    void setUp() {
        // Never throw on 4xx/5xx: the tests assert on the status themselves.
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    void anUploadedImageIsServedAtItsUrlAndDeletedWithIt() throws Exception {
        String itemId = createItem();

        String url = uploadImage(itemId);
        assertThat(url).matches("/uploads/items/[0-9a-f-]{36}\\.png");

        ResponseEntity<byte[]> image = http.get().uri(url).retrieve().toEntity(byte[].class);
        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(image.getHeaders().getCacheControl()).contains("max-age=31536000", "public", "immutable");
        assertThat(image.getBody()).isEqualTo(PNG);

        Path file = tempDir.resolve("uploads").resolve(url.substring("/uploads/".length()));
        assertThat(file).exists();

        ResponseEntity<Map> removed = http.delete()
                .uri(uriBuilder -> uriBuilder.path("/api/items/{id}/images").queryParam("url", url).build(itemId))
                .retrieve().toEntity(Map.class);
        assertThat(removed.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) removed.getBody().get("imageUrls")).isEmpty();

        assertThat(file).doesNotExist();
        assertThat(http.get().uri(url).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deletingAnItemDeletesItsUploadedImages() {
        String itemId = createItem();
        String url = uploadImage(itemId);
        Path file = tempDir.resolve("uploads").resolve(url.substring("/uploads/".length()));
        assertThat(file).exists();

        int status = http.delete().uri("/api/items/{id}", itemId).retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(204);
        assertThat(file).doesNotExist();
    }

    @Test
    void anUnknownImageIsAJson404() {
        ResponseEntity<String> response = http.get().uri("/uploads/items/missing.png").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("\"status\":404");
    }

    @Test
    void filesOutsideTheUploadDirectoryAreNotReachable() throws Exception {
        Files.writeString(tempDir.resolve("secret.txt"), "do not serve");

        for (String path : List.of("/uploads/../secret.txt", "/uploads/%2e%2e/secret.txt", "/uploads/..%2fsecret.txt")) {
            ResponseEntity<String> response = http.get().uri(URI.create("http://localhost:" + port + path))
                    .retrieve().toEntity(String.class);

            assertThat(response.getStatusCode().value()).as(path).isNotEqualTo(200);
            assertThat(response.getBody()).as(path).doesNotContain("do not serve");
        }
    }

    private String createItem() {
        Map<?, ?> category = http.post().uri("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Breads " + System.nanoTime()))
                .retrieve().body(Map.class);
        Map<?, ?> item = http.post().uri("/api/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Sourdough", "price", "6.50", "categoryId", category.get("id"), "leadTimeHours", 0))
                .retrieve().body(Map.class);
        return (String) item.get("id");
    }

    private String uploadImage(String itemId) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.IMAGE_PNG);
        partHeaders.setContentDispositionFormData("file", "cake.png");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(PNG), partHeaders));

        Map<?, ?> item = http.post().uri("/api/items/{id}/images/upload", itemId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve().body(Map.class);
        return (String) ((List<?>) item.get("imageUrls")).getFirst();
    }
}
