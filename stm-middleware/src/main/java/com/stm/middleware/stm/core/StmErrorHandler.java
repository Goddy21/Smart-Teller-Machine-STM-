package com.stm.middleware.stm.core;

import com.stm.middleware.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Gateway (/stm/v1) errors in the spec format. The portal API keeps its own format (common.GlobalExceptionHandler). */
@RestControllerAdvice(basePackages = "com.stm.middleware.stm")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StmErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(StmErrorHandler.class);

    @ExceptionHandler(StmException.class)
    ResponseEntity<Map<String, Object>> stm(StmException e, HttpServletRequest req) {
        if (e.status().is5xxServerError()) log.warn("{} {}: {}", req.getMethod(), req.getRequestURI(), e.code());
        return ResponseEntity.status(e.status()).body(ErrorBodies.body(e, RequestIds.of(req)));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest req) {
        return stm(Errors.of(e.status(), e.code(), e.getMessage(), e.getMessage(), false), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        var f = e.getBindingResult().getFieldErrors().stream().findFirst();
        String field = f.map(x -> x.getField()).orElse(null);
        String msg = f.map(x -> x.getField() + " " + x.getDefaultMessage()).orElse("invalid request");
        return stm(Errors.validation(field, msg), req);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> header(MissingRequestHeaderException e, HttpServletRequest req) {
        return stm(Errors.missingHeader(e.getHeaderName()), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return stm(Errors.validation(null, "Request body is missing or not valid JSON"), req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return stm(Errors.validation(e.getName(), "Invalid value for " + e.getName()), req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse er) {
            HttpStatus s = HttpStatus.valueOf(er.getStatusCode().value());
            return stm(Errors.of(s, "REQUEST_REJECTED", String.valueOf(er.getBody().getDetail()),
                    "Ombi limekataliwa.", false), req);
        }
        log.error("Unhandled gateway error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return stm(Errors.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again.",
                "Hitilafu imetokea. Tafadhali jaribu tena.", true), req);
    }
}
