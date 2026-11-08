package com.stm.middleware.stm.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** X-Request-Id: taken from the kiosk (a UUID per call) or generated, echoed back and logged end to end. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {
    public static final String ATTR = "stm.requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/stm/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");
        if (id == null || !SAFE.matcher(id).matches()) id = UUID.randomUUID().toString();
        req.setAttribute(ATTR, id);
        res.setHeader("X-Request-Id", id);
        MDC.put("requestId", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
        }
    }

    public static String of(HttpServletRequest req) {
        Object v = req == null ? null : req.getAttribute(ATTR);
        return v != null ? v.toString() : UUID.randomUUID().toString();
    }
}
