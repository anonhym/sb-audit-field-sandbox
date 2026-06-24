package com.example.versionsandbox.web;

import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the exceptions this sandbox is designed to provoke into readable JSON responses instead of
 * raw 500s, so the {@code @Version} failure modes are obvious from the HTTP status alone.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(ex, "OPTIMISTIC_LOCK_FAILURE"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicateKey(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(ex, "DUPLICATE_KEY"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(ex, "NOT_FOUND"));
    }

    private static Map<String, Object> body(Exception ex, String code) {
        return Map.of(
                "code", code,
                "exception", ex.getClass().getName(),
                "message", String.valueOf(ex.getMessage()));
    }
}
