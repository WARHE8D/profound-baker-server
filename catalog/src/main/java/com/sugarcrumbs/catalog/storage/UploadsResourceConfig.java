package com.sugarcrumbs.catalog.storage;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Serves the upload directory at the public base URL, so the image URLs
 * the API returns actually load. In production Caddy serves the same
 * volume directly and these requests never reach the app; this handler is
 * what makes them work in dev, and keeps the app usable without Caddy.
 * Lives next to {@link LocalDiskImageStorageService} because it only makes
 * sense for files on local disk.
 */
@Configuration
class UploadsResourceConfig implements WebMvcConfigurer {

    private final LocalDiskImageStorageService storage;

    UploadsResourceConfig(LocalDiskImageStorageService storage) {
        this.storage = storage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Spring's resource resolver rejects paths that escape the location, so "../" can't reach other files.
        String location = storage.rootDirectory().toUri().toString();
        registry.addResourceHandler(storage.publicBaseUrl() + "/**")
                // A location without a trailing slash would be treated as a file, not a directory.
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                // Filenames are random UUIDs and files are never rewritten in place, so they can be cached forever.
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
