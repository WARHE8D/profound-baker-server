package com.sugarcrumbs.server.service;


import com.sugarcrumbs.server.exception.ImageStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * Writes uploaded files to a local directory served as static content.
 * Fine for a single-owner deployment on one machine or one persistent
 * volume; if this ever moves to multiple app instances or ephemeral
 * containers, swap this for an S3-backed {@link ImageStorageService}
 * implementation — nothing outside this class needs to change.
 */
@Service
public class LocalDiskImageStorageService implements ImageStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final Path rootDirectory;
    private final String publicBaseUrl;

    public LocalDiskImageStorageService(
            @Value("${app.storage.local-path:./uploads}") String localPath,
            @Value("${app.storage.public-base-url:/uploads}") String publicBaseUrl
    ) {
        this.rootDirectory = Path.of(localPath);
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new ImageStorageException("could not initialize storage directory: " + rootDirectory, e);
        }
    }

    @Override
    public String store(MultipartFile file, String subfolder) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported image type: " + contentType);
        }

        String extension = extensionFor(contentType);
        String filename = UUID.randomUUID() + extension;

        Path targetDir = rootDirectory.resolve(sanitize(subfolder));
        Path targetFile = targetDir.resolve(filename);

        try {
            Files.createDirectories(targetDir);
            // REPLACE_EXISTING is defensive only — a random UUID filename should never collide.
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ImageStorageException("failed to store uploaded file", e);
        }

        return publicBaseUrl + "/" + sanitize(subfolder) + "/" + filename;
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    /** Strips path separators so a caller-supplied subfolder can't escape the root directory (path traversal). */
    private String sanitize(String subfolder) {
        String cleaned = StringUtils.hasText(subfolder) ? subfolder : "misc";
        return cleaned.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
