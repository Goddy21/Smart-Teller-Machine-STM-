package com.stm.middleware.stm.onboarding;

import com.stm.middleware.stm.core.Ctx;
import com.stm.middleware.stm.core.Errors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

@Repository
public class Applications {
    static final RowMapper<Application> ROW = (rs, n) -> new Application(rs.getString("id"), rs.getString("session_id"),
            rs.getLong("terminal_id"), rs.getString("product_code"), rs.getString("terms_id"), rs.getString("status"),
            rs.getString("verification_id"), rs.getInt("identity_attempts"), rs.getBoolean("biometrics_passed"),
            rs.getInt("biometric_attempts"), rs.getString("full_name"), rs.getString("id_number"),
            rs.getString("kra_pin"), rs.getString("mobile"), rs.getString("address"), rs.getString("occupation"),
            rs.getString("source_of_funds"), rs.getString("screening"), rs.getBoolean("otp_verified"),
            rs.getString("signature_id"), rs.getString("customer_id"), rs.getString("account_id"),
            rs.getString("account_number"), rs.getString("card_product"), rs.getString("name_on_card"),
            rs.getString("reference"), rs.getString("referral_status"), rs.getString("teller_override"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public Applications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Application> find(String id) {
        return jdbc.sql("SELECT * FROM applications WHERE id = :id").param("id", id).query(ROW).optional();
    }

    /** The application, if it was started in this session. */
    public Application mine(Ctx ctx, String id) {
        Application a = find(id).filter(x -> x.sessionId().equals(ctx.sessionId()))
                .orElseThrow(() -> Errors.notFound("APPLICATION_NOT_FOUND", "Application not found", "Ombi halikupatikana"));
        if (a.expired() && !a.closed()) {
            jdbc.sql("UPDATE applications SET status = 'EXPIRED', updated_at = now() WHERE id = :id").param("id", id).update();
            throw Errors.applicationExpired();
        }
        return a;
    }

    /** Must be in one of these states, else WRONG_STATE (with the current state and the next step in details). */
    public Application expect(Application a, String... states) {
        if (a.closed()) throw Errors.wrongState("This application is " + a.status().toLowerCase() + ".")
                .detail("status", a.status());
        if (Arrays.asList(states).contains(a.status())) return a;
        throw Errors.wrongState("This step is not available now.").detail("status", a.status()).detail("nextStep", a.nextStep());
    }

    public void update(String id, Map<String, Object> columns) {
        StringBuilder sql = new StringBuilder("UPDATE applications SET updated_at = now()");
        columns.keySet().forEach(k -> sql.append(", ").append(k).append(" = :").append(k));
        sql.append(" WHERE id = :__id");
        var spec = jdbc.sql(sql.toString()).param("__id", id);
        for (var e : columns.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        spec.update();
    }
}
