package com.tactical.core.api;

/** Carries the HTTP status the caller should see, plus a readable reason. */
public class ApiException extends RuntimeException {

    private final int status;

    /** Carries the HTTP status the caller should see together with the reason. */
    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** The HTTP status the handler should answer with. */
    public int getStatus() {
        return status;
    }
}
