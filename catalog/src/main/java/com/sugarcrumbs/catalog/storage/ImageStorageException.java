package com.sugarcrumbs.catalog.storage;

public class ImageStorageException extends RuntimeException {

    public ImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ImageStorageException(String message) {
        super(message);
    }
}
