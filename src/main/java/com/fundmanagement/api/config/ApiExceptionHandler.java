package com.fundmanagement.api.config;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "message", ex.getMessage())); }
  @ExceptionHandler(IllegalStateException.class) ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "CONFLICT", "message", ex.getMessage())); }
  @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<Map<String, String>> duplicate() { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "CONFLICT", "message", "Resource conflicts with an existing record")); }
  @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Map<String, String>> validation() { return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "message", "Request validation failed")); }
  @ExceptionHandler(UnsupportedOperationException.class) ResponseEntity<Map<String, String>> notImplemented() { return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("code", "NOT_IMPLEMENTED", "message", "This endpoint is planned for Phase 2")); }
}
