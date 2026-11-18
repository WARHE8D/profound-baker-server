package com.sugarcrumbs.catalog.storage;

import com.sugarcrumbs.catalog.application.ItemService;
import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over "where uploaded files actually live". The service
 * layer depends only on this interface, never on a concrete storage
 * technology — swapping {@link LocalDiskImageStorageService} for an S3
 * or Cloudinary implementation later is then a one-class change plus a
 * Spring profile, with zero changes to {@link ItemService} or any
 * controller.
 */
public interface ImageStorageService {

    /**
     * Stores the given image under the given logical subfolder (e.g.
     * "items"), with a thumbnail, and returns a URL the frontend can load
     * the image from directly. The file is processed first
     * ({@link ImageProcessor}): only real PNG, JPEG or WebP images are
     * accepted, and what is stored is re-encoded without their metadata.
     *
     * @throws IllegalArgumentException (400) if the file isn't an image that can be stored
     * @throws com.sugarcrumbs.catalog.storage.ImageStorageException if the file can't be read or saved
     */
    String store(MultipartFile file, String subfolder);

    /**
     * The URL of the thumbnail stored with the image at {@code url}, or
     * {@code url} itself when there is none: images hosted elsewhere, and
     * uploads from before thumbnails existed.
     */
    String thumbnailUrl(String url);

    /**
     * Deletes the stored file behind a URL previously returned by
     * {@link #store}, and its thumbnail. URLs this storage didn't issue (images hosted
     * elsewhere) and files that are already gone are ignored, so callers
     * can pass any image URL an item holds.
     *
     * @throws com.sugarcrumbs.catalog.storage.ImageStorageException if the file exists but can't be deleted
     */
    void delete(String url);
}
