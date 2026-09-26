package com.sugarcrumbs.server.exception;

/** A request that's individually valid but conflicts with current server state (duplicate name, referenced entity, etc.). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
