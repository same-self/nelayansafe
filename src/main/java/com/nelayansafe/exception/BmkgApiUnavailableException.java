package com.nelayansafe.exception;

public class BmkgApiUnavailableException extends Exception {
    public BmkgApiUnavailableException(String message) {
        super(message);
    }

    public BmkgApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
