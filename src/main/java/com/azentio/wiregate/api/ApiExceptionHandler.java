package com.azentio.wiregate.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validationError(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_FAILED", "fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadableRequest(HttpMessageNotReadableException exception) {
        JsonMappingException mappingException = findMappingException(exception);
        String field = mappingException == null ? "request" : fieldPath(mappingException);
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_FAILED",
                "fields", Map.of(field, "Invalid or unreadable value.")));
    }

    @ExceptionHandler(WireConflictException.class)
    ResponseEntity<Map<String, String>> conflict(WireConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "UETR_CONFLICT", "message", exception.getMessage()));
    }

    @ExceptionHandler(WireNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(WireNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "WIRE_NOT_FOUND", "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    private static JsonMappingException findMappingException(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof JsonMappingException mappingException) {
                return mappingException;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static String fieldPath(JsonMappingException exception) {
        String path = exception.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? reference.getFieldName()
                        : reference.getIndex() >= 0 ? "[" + reference.getIndex() + "]" : "")
                .filter(segment -> !segment.isBlank())
                .collect(Collectors.joining("."))
                .replace(".[", "[");
        return path.isBlank() ? "request" : path;
    }
}
