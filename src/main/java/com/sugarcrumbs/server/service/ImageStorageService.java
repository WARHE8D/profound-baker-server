package com.sugarcrumbs.server.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over "where uploaded files actually live". The service
 * layer depends only on this interface, never on a concrete storage
 * technology — swapping {@link LocalDiskImageStorageService} for an S3
 * or Cloudinary implementation later is then a one-class change plus a
 * Spring profile, with zero changes to {@code ItemService} or any
 * controller.
 */
public interface ImageStorageService {

    /**
     * Stores the given file under the given logical subfolder (e.g.
     * "items") and returns a URL the frontend can load the image from
     * directly.
     *
     * @throws com.sugarcrumbs.server.exception.ImageStorageException if the file can't be read or saved
     */
    String store(MultipartFile file, String subfolder);
}
