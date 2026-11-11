package com.stm.middleware.stm.core;

import com.stm.middleware.common.Hashing;
import com.stm.middleware.telemetry.DeviceContact;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Short-lived device tokens (D-1). Opaque, 15 minutes, only the SHA-256 is stored. */
@Service
public class DeviceTokenService {
    public static final int LIFETIME_SEC = 900;

    private final JdbcClient jdbc;
    private final DeviceContact contact;

    public DeviceTokenService(JdbcClient jdbc, DeviceContact contact) {
        this.jdbc = jdbc;
        this.contact = contact;
    }

    public record Issued(String accessToken, int expiresIn) {}

    public Issued issue(long terminalId) {
        String token = Ids.secret("dvt_");
        jdbc.sql("INSERT INTO device_tokens (token_hash, terminal_id, expires_at) VALUES (:h, :t, :e)")
                .param("h", Hashing.sha256Hex(token)).param("t", terminalId)
                .param("e", OffsetDateTime.now().plusSeconds(LIFETIME_SEC)).update();
        jdbc.sql("SELECT terminal_code FROM terminals WHERE id = :i").param("i", terminalId).query(String.class).optional()
                .ifPresent(code -> contact.seen(terminalId, code));
        return new Issued(token, LIFETIME_SEC);
    }

    public Optional<DevicePrincipal> authenticate(String token) {
        return jdbc.sql("""
                SELECT t.id, t.terminal_code, t.branch_code FROM device_tokens d JOIN terminals t ON t.id = d.terminal_id
                WHERE d.token_hash = :h AND d.expires_at > now() AND t.status = 'ACTIVE'""")
                .param("h", Hashing.sha256Hex(token))
                .query((rs, n) -> new DevicePrincipal(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional()
                .map(p -> { contact.seen(p.terminalId(), p.deviceId()); return p; });
    }

    /** The provisioning credential from POST /api/v1/terminals/register (dev sign-in when mTLS is off). */
    public Optional<DevicePrincipal> byRegistrationToken(String deviceId, String registrationToken) {
        return jdbc.sql("""
                SELECT id, terminal_code, branch_code FROM terminals
                WHERE terminal_code = :c AND token_hash = :h AND status = 'ACTIVE'""")
                .param("c", deviceId).param("h", Hashing.sha256Hex(registrationToken))
                .query((rs, n) -> new DevicePrincipal(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    public Optional<DevicePrincipal> byDeviceId(String deviceId) {
        return jdbc.sql("SELECT id, terminal_code, branch_code FROM terminals WHERE terminal_code = :c AND status = 'ACTIVE'")
                .param("c", deviceId)
                .query((rs, n) -> new DevicePrincipal(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    @Scheduled(fixedDelay = 600_000)
    void purgeExpired() {
        jdbc.sql("DELETE FROM device_tokens WHERE expires_at < now() - interval '1 hour'").update();
    }
}
