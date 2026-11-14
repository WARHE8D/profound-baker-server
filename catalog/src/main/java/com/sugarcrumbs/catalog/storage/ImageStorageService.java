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
     * Stores the given file under the given logical subfolder (e.g.
     * "items") and returns a URL the frontend can load the image from
     * directly.
     *
     * @throws com.sugarcrumbs.catalog.storage.ImageStorageException if the file can't be read or saved
     */
    String store(MultipartFile file, String subfolder);

    /**
     * Deletes the stored file behind a URL previously returned by
     * {@link #store}. URLs this storage didn't issue (images hosted
     * elsewhere) and files that are already gone are ignored, so callers
     * can pass any image URL an item holds.
     *
     * @throws com.sugarcrumbs.catalog.storage.ImageStorageException if the file exists but can't be deleted
     */
    void delete(String url);
}
