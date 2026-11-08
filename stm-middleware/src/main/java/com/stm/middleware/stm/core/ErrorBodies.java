package com.stm.middleware.stm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the spec error body. Used by the controller advice and by the security entry points. */
public final class ErrorBodies {
    private ErrorBodies() {}

    public static Map<String, Object> body(StmException e, String requestId) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code());
        err.put("message", e.getMessage());
        err.put("messageSw", e.messageSw());
        err.put("retryable", e.retryable());
        err.put("requestId", requestId);
        err.put("details", e.details());
        return Map.of("error", err);
    }

    public static void write(HttpServletRequest req, HttpServletResponse res, StmException e, ObjectMapper mapper)
            throws IOException {
        res.setStatus(e.status().value());
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), body(e, RequestIds.of(req)));
    }
}
