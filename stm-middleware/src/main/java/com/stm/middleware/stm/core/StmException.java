package com.stm.middleware.stm.core;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Gateway error, rendered as the spec's error body:
 * {"error":{"code","message","messageSw","retryable","requestId","details"}}.
 * Use the factories in {@link Errors} so every code carries its English and Kiswahili text.
 */
public class StmException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String messageSw;
    private final boolean retryable;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public StmException(HttpStatus status, String code, String message, String messageSw, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.messageSw = messageSw;
        this.retryable = retryable;
    }

    public StmException detail(String key, Object value) {
        if (value != null) details.put(key, value);
        return this;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String messageSw() { return messageSw; }
    public boolean retryable() { return retryable; }
    public Map<String, Object> details() { return details; }
}
