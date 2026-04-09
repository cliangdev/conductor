package com.conductor.exception;

public class StorageUploadException extends RuntimeException {

    public StorageUploadException(String message) {
        super(message);
    }

    public StorageUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
