package paymenttransferservice.rest;

import java.time.Instant;

public record ErrorDetails(String code, String message, Instant timestamp) {

    public static ErrorDetails of(String code, String message) {
        return new ErrorDetails(code, message, Instant.now());
    }
}
