package com.stm.middleware.journey;

import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Localised receipt / emergency-voucher / SMS content (en, sw, fr). The kiosk prints it; the bank's SMS gateway sends it. */
@Service
public class ReceiptService {
    public record Line(String label, String value) {}

    public record Receipt(String language, String title, List<Line> lines, String footer) {}

    private static final Map<String, Map<String, String>> TEXT = Map.of(
            "en", Map.of("title", "Account & Card Receipt", "account", "Account number", "type", "Account type",
                    "branch", "Branch", "card", "Debit card", "ref", "Reference", "error", "Error reference",
                    "thanks", "Thank you for banking with us.", "collect", "To be collected at the branch",
                    "date", "Date"),
            "sw", Map.of("title", "Risiti ya Akaunti na Kadi", "account", "Nambari ya akaunti",
                    "type", "Aina ya akaunti", "branch", "Tawi", "card", "Kadi ya malipo", "ref", "Rejea",
                    "error", "Rejea ya hitilafu", "thanks", "Asante kwa kuweka benki nasi.",
                    "collect", "Itachukuliwa tawini", "date", "Tarehe"),
            "fr", Map.of("title", "Reçu compte et carte", "account", "Numéro de compte", "type", "Type de compte",
                    "branch", "Agence", "card", "Carte de débit", "ref", "Référence", "error", "Référence d’erreur",
                    "thanks", "Merci de votre confiance.", "collect", "À retirer en agence", "date", "Date"));

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    public Receipt build(KioskSession s, String terminalCode, String branchName, String helpline) {
        Map<String, String> t = TEXT.getOrDefault(s.language(), TEXT.get("en"));
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(t.get("date"), ZonedDateTime.now(ZONE).format(FMT)));
        lines.add(new Line(t.get("ref"), s.id().toString().substring(0, 8).toUpperCase() + " / " + terminalCode));
        lines.add(new Line(t.get("branch"), branchName));
        if (s.accountNo() != null) {
            lines.add(new Line(t.get("account"), s.accountNo()));
            if (s.productCode() != null) lines.add(new Line(t.get("type"), s.productCode()));
        }
        if ("ISSUED".equals(s.cardStatus()) || "ACTIVATION_PENDING".equals(s.cardStatus())) {
            lines.add(new Line(t.get("card"), last4(s.cardMaskedPan())));
        } else if ("DISPENSE_FAILED".equals(s.cardStatus())) {
            lines.add(new Line(t.get("card"), t.get("collect")));
        }
        if (s.errorRef() != null) lines.add(new Line(t.get("error"), s.errorRef()));
        if (helpline != null && !helpline.isBlank()) lines.add(new Line("Helpline", helpline));
        return new Receipt(s.language(), t.get("title"), lines, t.get("thanks"));
    }

    public String asText(Receipt r) {
        StringBuilder sb = new StringBuilder(r.title()).append('\n');
        for (Line l : r.lines()) sb.append(l.label()).append(": ").append(l.value()).append('\n');
        return sb.append(r.footer()).toString();
    }

    private static String last4(String maskedPan) {
        return maskedPan == null || maskedPan.length() < 4 ? "****" : "**** " + maskedPan.substring(maskedPan.length() - 4);
    }
}
