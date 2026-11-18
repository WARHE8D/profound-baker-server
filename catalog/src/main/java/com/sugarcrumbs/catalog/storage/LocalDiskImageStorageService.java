package com.sugarcrumbs.catalog.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Writes uploaded files to a local directory served as static content
 * (by {@link UploadsResourceConfig} in the app, by Caddy in production).
 * Fine for a single-owner deployment on one machine or one persistent
 * volume; if this ever moves to multiple app instances or ephemeral
 * containers, swap this for an S3-backed {@link ImageStorageService}
 * implementation — nothing outside this package needs to change.
 */
@Service
public class LocalDiskImageStorageService implements ImageStorageService {

    /** Marks the thumbnail next to an image: {@code abc.jpg} has {@code abc.thumb.jpg}. */
    private static final String THUMBNAIL_MARKER = ".thumb";

    private final ImageProcessor imageProcessor;
    private final Path rootDirectory;
    private final String publicBaseUrl;

    public LocalDiskImageStorageService(
            ImageProcessor imageProcessor,
            @Value("${app.storage.local-path:./uploads}") String localPath,
            @Value("${app.storage.public-base-url:/uploads}") String publicBaseUrl
    ) {
        this.imageProcessor = imageProcessor;
        this.rootDirectory = Path.of(localPath).toAbsolutePath().normalize();
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
        // The declared Content-Type and file name are ignored: the processor decides what the bytes are.
        ImageProcessor.ProcessedImage processed;
        try {
            processed = imageProcessor.process(file.getBytes());
        } catch (IOException e) {
            throw new ImageStorageException("failed to read uploaded file", e);
        }

        String name = UUID.randomUUID().toString();
        Path targetDir = rootDirectory.resolve(sanitize(subfolder));
        Path image = targetDir.resolve(name + processed.extension());
        Path thumbnail = targetDir.resolve(name + THUMBNAIL_MARKER + processed.extension());
        try {
            Files.createDirectories(targetDir);
            // The thumbnail first: an image on disk always has its thumbnail.
            Files.write(thumbnail, processed.thumbnail(), StandardOpenOption.CREATE_NEW);
            Files.write(image, processed.image(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            deleteQuietly(image);
            deleteQuietly(thumbnail);
            throw new ImageStorageException("failed to store uploaded file", e);
        }

        return publicBaseUrl + "/" + sanitize(subfolder) + "/" + image.getFileName();
    }

    @Override
    public void delete(String url) {
        Path file = resolveOwnedFile(url);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(thumbnailOf(file));
        } catch (IOException e) {
            throw new ImageStorageException("failed to delete stored file", e);
        }
    }

    @Override
    public String thumbnailUrl(String url) {
        Path file = resolveOwnedFile(url);
        if (file == null || !Files.exists(thumbnailOf(file))) {
            return url;
        }
        return url.substring(0, url.length() - file.getFileName().toString().length())
                + thumbnailOf(file).getFileName();
    }

    /** {@code abc.jpg} -> {@code abc.thumb.jpg}, in the same directory. */
    private static Path thumbnailOf(Path image) {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String thumbnail = dot < 0 ? name + THUMBNAIL_MARKER
                : name.substring(0, dot) + THUMBNAIL_MARKER + name.substring(dot);
        return image.resolveSibling(thumbnail);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Already failing; the original error is the one worth reporting.
        }
    }

    /** Absolute directory the files are written to, for serving them as static resources. */
    public Path rootDirectory() {
        return rootDirectory;
    }

    /** URL path prefix of every URL {@link #store} returns, without a trailing slash. */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * Maps a URL back to a file under the root directory, or {@code null}
     * when the URL isn't one this storage issued. The URL reaches us from
     * the owner's request, so anything that would resolve outside the
     * root (e.g. {@code /uploads/../application.properties}) is refused
     * rather than deleted.
     */
    private Path resolveOwnedFile(String url) {
        String prefix = publicBaseUrl + "/";
        if (url == null || !url.startsWith(prefix)) {
            return null;
        }
        try {
            Path file = rootDirectory.resolve(url.substring(prefix.length())).normalize();
            boolean insideRoot = file.startsWith(rootDirectory) && !file.equals(rootDirectory);
            return insideRoot && !Files.isDirectory(file) ? file : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Strips path separators so a caller-supplied subfolder can't escape the root directory (path traversal). */
    private String sanitize(String subfolder) {
        String cleaned = StringUtils.hasText(subfolder) ? subfolder : "misc";
        return cleaned.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
