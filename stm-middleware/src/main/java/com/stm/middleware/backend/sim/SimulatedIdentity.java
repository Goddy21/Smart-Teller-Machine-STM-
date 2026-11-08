package com.stm.middleware.backend.sim;

import com.stm.middleware.backend.IdentityServices;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * SIMULATED IPRS / immigration records, biometric matcher, KRA iTax PIN checker and sanctions/PEP screening,
 * driven by sim_registry. Each record has a scenario (HAPPY_PATH, BIOMETRIC_NO_MATCH, SCREENING_REFERRED,
 * KRA_PIN_INVALID, DOCUMENT_TAMPERED, EXISTING_CUSTOMER, VISITOR) so testers can walk every branch of the journey.
 */
@Service
@Profile("!prod")
public class SimulatedIdentity implements IdentityServices {
    private final JdbcClient jdbc;
    private final SimControl control;

    public SimulatedIdentity(JdbcClient jdbc, SimControl control) {
        this.jdbc = jdbc;
        this.control = control;
    }

    @Override
    public Optional<RegistryRecord> lookup(String documentType, String country, String documentNumber) {
        control.core();
        return jdbc.sql("""
                SELECT * FROM sim_registry WHERE document_number = :n AND document_type = :t""")
                .param("n", documentNumber == null ? "" : documentNumber.replace(" ", "").toUpperCase())
                .param("t", documentType)
                .query((rs, n) -> new RegistryRecord(
                        "PASSPORT".equals(rs.getString("document_type"))
                                ? ("KEN".equals(rs.getString("country")) ? "IMMIGRATION" : "IMMIGRATION_VISITOR") : "IPRS",
                        rs.getString("document_type"), rs.getString("document_number"), rs.getString("country"),
                        rs.getString("serial_number"), rs.getString("surname"), rs.getString("given_names"),
                        rs.getObject("date_of_birth", LocalDate.class), rs.getString("gender"),
                        rs.getString("place_of_birth"), rs.getString("nationality"),
                        rs.getObject("expiry", LocalDate.class), rs.getString("scenario")))
                .optional();
    }

    @Override
    public DocumentChecks checkDocument(RegistryRecord r, LocalDate documentExpiry, byte[] front, byte[] uv) {
        LocalDate today = LocalDate.now();
        boolean expired = (documentExpiry != null && documentExpiry.isBefore(today))
                || (r.expiry() != null && r.expiry().isBefore(today));
        return new DocumentChecks(expired ? "FAIL" : "PASS", "DOCUMENT_TAMPERED".equals(r.scenario()) ? "FAIL" : "PASS");
    }

    /** A placeholder portrait with the person's initials (real IPRS returns their photo). */
    @Override
    public byte[] registryPhoto(RegistryRecord r) {
        BufferedImage img = new BufferedImage(240, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xE2E4F8));
        g.fillRect(0, 0, 240, 300);
        g.setColor(new Color(0x1C24AA));
        g.fillOval(70, 50, 100, 100);
        g.fillRoundRect(35, 165, 170, 160, 90, 90);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
        String initials = ("" + r.givenNames().charAt(0) + r.surname().charAt(0)).toUpperCase();
        FontMetrics fm = g.getFontMetrics();
        g.drawString(initials, 120 - fm.stringWidth(initials) / 2, 113);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Override
    public MatchResult matchFace(RegistryRecord r, byte[] faceImage, double livenessScore) {
        control.core();
        if (livenessScore < 0.5) return new MatchResult("NO_MATCH", 0.0);
        if ("BIOMETRIC_NO_MATCH".equals(r.scenario())) return new MatchResult("NO_MATCH", 0.412);
        return new MatchResult("MATCH", 0.992);
    }

    @Override
    public MatchResult matchFingerprint(RegistryRecord r, String finger, byte[] template, int quality) {
        control.core();
        if (quality < 40) return new MatchResult("NO_MATCH", 0.21);
        return new MatchResult("MATCH", 0.95);
    }

    @Override
    public KraResult validateKraPin(String kraPin, String idNumber) {
        control.core();
        String pin = kraPin == null ? "" : kraPin.trim().toUpperCase();
        if (!pin.matches("[AP]\\d{9}[A-Z]")) return new KraResult(false, null, false, "INVALID_FORMAT");
        record Row(String surname, String given, String pin, String status) {}
        Optional<Row> byPin = jdbc.sql("SELECT surname, given_names, kra_pin, kra_status FROM sim_registry WHERE kra_pin = :p")
                .param("p", pin).query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
        Optional<String> pinOfId = jdbc.sql("SELECT kra_pin FROM sim_registry WHERE document_number = :n")
                .param("n", idNumber).query(String.class).optional();
        if (byPin.isEmpty()) {
            // Unknown to the simulator: a well-formed PIN for an ID we know nothing else about is accepted.
            boolean free = pinOfId.isEmpty() || pinOfId.get() == null;
            return new KraResult(free, free ? null : null, free, free ? "ACTIVE" : "NOT_FOUND");
        }
        Row r = byPin.get();
        boolean matches = pinOfId.map(pin::equals).orElse(false);
        return new KraResult("ACTIVE".equals(r.status()), r.given() + " " + r.surname(), matches, r.status());
    }

    @Override
    public Screening screen(RegistryRecord r) {
        control.core();
        return "SCREENING_REFERRED".equals(r.scenario()) ? new Screening("CLEAR", "HIT") : new Screening("CLEAR", "CLEAR");
    }
}
