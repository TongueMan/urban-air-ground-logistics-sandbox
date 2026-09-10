package com.skyfleet.logistics;

import org.springframework.http.HttpStatus;

public class DemoException extends RuntimeException {
    public final HttpStatus status;
    public DemoException(HttpStatus status, String message) { super(message); this.status = status; }
}

