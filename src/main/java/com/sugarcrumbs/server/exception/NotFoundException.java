package com.sugarcrumbs.server.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException forEntity(String entityName, Object id) {
        return new NotFoundException(entityName + " not found: " + id);
    }
}