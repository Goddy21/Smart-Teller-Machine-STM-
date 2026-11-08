package com.stm.callcontrol;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/** DEV PROFILE ONLY. Stands in for the Terminal API Gateway on a test LAN: when a request has no X-Terminal-Id /
 *  X-Teller-Id / X-Auditor-Id header, it takes the identity from a cookie (stm-terminal, stm-teller, stm-auditor) or a
 *  query parameter (terminalId, tellerId, auditorId). Browsers' EventSource cannot send headers, cookies it can.
 *  Never enable this profile in production: anyone could claim any identity. */
@Component
@Profile("dev")
public class DevGateway extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(DevGateway.class);
  private static final String[][] MAP = {
      {"X-Terminal-Id", "stm-terminal", "terminalId"},
      {"X-Teller-Id", "stm-teller", "tellerId"},
      {"X-Auditor-Id", "stm-auditor", "auditorId"}};

  public DevGateway() { log.warn("DEV GATEWAY ACTIVE: identities are taken from cookies/query params. Never use in production."); }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    Map<String, String> extra = new HashMap<>();
    for (String[] m : MAP) {
      if (req.getHeader(m[0]) != null) continue;
      String v = req.getParameter(m[2]);
      if (v == null && req.getCookies() != null)
        for (Cookie c : req.getCookies()) if (c.getName().equals(m[1])) v = c.getValue();
      if (v != null && !v.isBlank()) extra.put(m[0].toLowerCase(Locale.ROOT), v.trim());
    }
    chain.doFilter(extra.isEmpty() ? req : new Wrapped(req, extra), res);
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() { return false; }

  private static final class Wrapped extends HttpServletRequestWrapper {
    private final Map<String, String> extra;   // keys lower-case
    Wrapped(HttpServletRequest r, Map<String, String> extra) { super(r); this.extra = extra; }
    @Override public String getHeader(String name) {
      String v = extra.get(name.toLowerCase(Locale.ROOT));
      return v != null ? v : super.getHeader(name);
    }
    @Override public Enumeration<String> getHeaders(String name) {
      String v = extra.get(name.toLowerCase(Locale.ROOT));
      return v != null ? Collections.enumeration(List.of(v)) : super.getHeaders(name);
    }
    @Override public Enumeration<String> getHeaderNames() {
      Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
      names.addAll(extra.keySet());
      return Collections.enumeration(names);
    }
  }
}
