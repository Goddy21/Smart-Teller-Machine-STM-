package com.stm.middleware.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("invalid request");
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_ERROR", msg));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> header(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest().body(ApiError.of("MISSING_HEADER", e.getHeaderName() + " header is required"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST", "Request body is missing or not valid JSON"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_PARAMETER", "Invalid value for " + e.getName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiError.of("FILE_TOO_LARGE", "Upload exceeds the size limit"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e, jakarta.servlet.http.HttpServletResponse response) {
        // The browser went away (a portal tab closed while the live kiosk updates were streaming, a kiosk lost its
        // network): nothing to answer and nothing wrong with the server, so no error and no stack trace.
        if (clientGone(e)) {
            log.debug("Client disconnected: {}", e.toString());
            return null;
        }
        String type = response.getContentType();
        if (type != null && type.startsWith("text/event-stream")) {   // an error body cannot be written into a stream
            log.warn("Live update stream ended: {}", e.toString());
            return null;
        }
        // Spring MVC's own client errors (405, 404, 415, missing parameter/part...) carry their status.
        if (e instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).body(ApiError.of("REQUEST_REJECTED", er.getBody().getDetail()));
        }
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Unexpected error"));
    }

    /** Broken pipe, connection reset, or the async request already completed because the client left. */
    static boolean clientGone(Throwable e) {
        for (Throwable x = e; x != null; x = x.getCause() == x ? null : x.getCause()) {
            String n = x.getClass().getSimpleName();
            if (n.equals("ClientAbortException") || n.equals("AsyncRequestNotUsableException")) return true;
            if (x instanceof java.io.IOException) {
                String m = String.valueOf(x.getMessage()).toLowerCase();
                if (m.contains("broken pipe") || m.contains("connection reset") || m.contains("connection was aborted")) return true;
            }
        }
        return false;
    }
}
