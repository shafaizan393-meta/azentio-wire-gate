package com.azentio.wiregate.kafka;

public class NonRetryableWireException extends RuntimeException {
    public NonRetryableWireException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonRetryableWireException(String message) {
        super(message);
    }
}
