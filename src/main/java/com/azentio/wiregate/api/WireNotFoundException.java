package com.azentio.wiregate.api;

public class WireNotFoundException extends RuntimeException {
    public WireNotFoundException(String message) {
        super(message);
    }
}
