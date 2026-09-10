package com.skyfleet.logistics;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class DemoExceptionHandler {
    @ExceptionHandler(DemoException.class)
    ResponseEntity<Map<String, Object>> demo(DemoException error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage(), "status", error.status.value()));
    }
}

