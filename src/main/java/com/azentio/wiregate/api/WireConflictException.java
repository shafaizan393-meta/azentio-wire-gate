package com.azentio.wiregate.api;

public class WireConflictException extends RuntimeException {
    public WireConflictException(String message) {
        super(message);
    }
}
