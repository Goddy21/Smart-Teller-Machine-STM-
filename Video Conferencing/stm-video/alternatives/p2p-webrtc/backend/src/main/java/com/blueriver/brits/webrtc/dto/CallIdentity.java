package com.blueriver.brits.webrtc.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Who is calling. The gateway sets X-Terminal-Id (after mTLS) or X-Teller-Id (after portal login) and must strip both
 *  from client traffic. Replace resolve() with BRITS authentication when merging. */
public record CallIdentity(String role, String id) {
  public String key() { return role + ":" + id; }
  public boolean isKiosk() { return "kiosk".equals(role); }

  public static CallIdentity resolve(String terminalHeader, String tellerHeader) {
    boolean t = terminalHeader != null && !terminalHeader.isBlank();
    boolean l = tellerHeader != null && !tellerHeader.isBlank();
    if (t == l) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "exactly one identity header required");
    return t ? new CallIdentity("kiosk", terminalHeader.trim()) : new CallIdentity("teller", tellerHeader.trim());
  }
}
