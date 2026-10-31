package com.example.sporty.match.integration;

/** Stable REST error returned by dependency checks; never exposes upstream diagnostics. */
public class MatchDependencyException extends RuntimeException {
    private final int status;
    private final String code;

    public MatchDependencyException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
