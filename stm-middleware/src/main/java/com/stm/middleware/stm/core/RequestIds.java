package com.stm.middleware.stm.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger access = LoggerFactory.getLogger("stm.access");
    public static final String ATTR = "stm.requestId";
    static final String ERROR_ATTR = "stm.errorCode", DETAIL_ATTR = "stm.errorDetail";

    /** Called where a gateway error answer is written, so the one-line call log can say why. */
    public static void noteError(HttpServletRequest req, StmException e) {
        if (req == null) return;
        req.setAttribute(ERROR_ATTR, e.code());
        StringBuilder d = new StringBuilder();
        e.details().forEach((k, v) -> {
            String s = String.valueOf(v);
            if (s.length() <= 60) d.append(d.length() == 0 ? "" : " ").append(k).append('=').append(s);
        });
        if (d.length() > 0) req.setAttribute(DETAIL_ATTR, d.toString());
    }
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
        long t0 = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            logCall(req, res, id, (System.nanoTime() - t0) / 1_000_000);
            MDC.remove("requestId");
        }
    }

    /**
     * One line per kiosk call: "STM POST /stm/v1/identity/biometrics/verify -> 409 WRONG_STATE status=CONSENTED
     * kiosk=STM-NBO-MOI-03 42ms req=…". Successful GETs (the kiosk polls some of them every second) are left out
     * unless logging.level.stm.access=DEBUG.
     */
    private static void logCall(HttpServletRequest req, HttpServletResponse res, String id, long ms) {
        try {
            int status = res.getStatus();
            boolean quiet = "GET".equals(req.getMethod()) && status < 400;
            if (quiet && !access.isDebugEnabled()) return;
            Object code = req.getAttribute(ERROR_ATTR), detail = req.getAttribute(DETAIL_ATTR);
            String replay = res.getHeader("Idempotent-Replayed") != null ? " (replayed)" : "";
            String line = String.format("%s %s -> %d%s%s%s kiosk=%s %dms req=%s", req.getMethod(), req.getRequestURI(), status,
                    code == null ? "" : " " + code, detail == null ? "" : " " + detail, replay,
                    String.valueOf(req.getHeader("X-Device-Id")), ms, id);
            if (status >= 500) access.warn(line);
            else if (quiet) access.debug(line);
            else access.info(line);
        } catch (RuntimeException ignored) {
            // logging must never break a kiosk call
        }
    }

    public static String of(HttpServletRequest req) {
        Object v = req == null ? null : req.getAttribute(ATTR);
        return v != null ? v.toString() : UUID.randomUUID().toString();
    }
}
