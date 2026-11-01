package com.example.sporty.match.crud;

public class MatchCrudProblem extends RuntimeException {
    private final int status;
    private final String code;

    public MatchCrudProblem(int status, String code, String message) {
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
