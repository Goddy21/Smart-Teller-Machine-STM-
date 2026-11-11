package com.stm.middleware.stm.receipts;

import com.stm.middleware.backend.Messaging;
import com.stm.middleware.stm.core.Errors;
import com.stm.middleware.stm.core.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Customer SMS in English or Kiswahili from fixed templates (R-2, OTPs, account opened). */
@Service
public class SmsService {
    /** template -> {en, sw}. {name} placeholders come from params. */
    static final Map<String, String[]> TEMPLATES = Map.ofEntries(
            Map.entry("OTP", new String[]{
                    "Your Smart Teller code is {code}. It expires in 5 minutes. Never share it, not even with bank staff.",
                    "Nambari yako ya Smart Teller ni {code}. Itaisha baada ya dakika 5. Usimpe mtu yeyote, hata mfanyakazi wa benki."}),
            Map.entry("ACCOUNT_OPENED", new String[]{
                    "Welcome {name}. Your {product} {account} is open at {branch}. Ref {ref}.",
                    "Karibu {name}. {product} yako {account} imefunguliwa {branch}. Kumb. {ref}."}),
            Map.entry("CASH_DEPOSIT_RECEIPT", new String[]{
                    "{amount} deposited to {account}. Ref {ref}. Thank you for banking with us.",
                    "{amount} imewekwa kwenye {account}. Kumb. {ref}. Asante kwa kutumia huduma zetu."}),
            Map.entry("CASH_WITHDRAWAL_RECEIPT", new String[]{
                    "{amount} withdrawn from {account}. Ref {ref}.",
                    "{amount} imetolewa kutoka {account}. Kumb. {ref}."}),
            Map.entry("CASH_REVERSAL", new String[]{
                    "Your cash was not taken and was retracted. {amount} will be returned to {account}. Ref {ref}.",
                    "Pesa zako hazikuchukuliwa na zimerudishwa ndani. {amount} itarudishwa kwenye {account}. Kumb. {ref}."}),
            Map.entry("FX_RECEIPT", new String[]{
                    "Exchange {foreign} at {rate}: {amount}. Ref {ref}.",
                    "Ubadilishaji {foreign} kwa {rate}: {amount}. Kumb. {ref}."}),
            Map.entry("CARD_ACTIVATED", new String[]{
                    "Your card {card} is active and linked to {account}. Ref {ref}.",
                    "Kadi yako {card} iko tayari na imeunganishwa na {account}. Kumb. {ref}."}),
            Map.entry("CARD_BLOCKED", new String[]{
                    "Your card {card} has been blocked ({reason}). If this was not you, call us now.",
                    "Kadi yako {card} imezuiwa ({reason}). Ikiwa si wewe, tupigie sasa."}),
            Map.entry("CARD_PIN_CHANGED", new String[]{
                    "The PIN of your card {card} was changed at a Smart Teller. If this was not you, call us now.",
                    "PIN ya kadi yako {card} imebadilishwa kwenye Smart Teller. Ikiwa si wewe, tupigie sasa."}),
            Map.entry("CARD_TEMP_BLOCKED", new String[]{
                    "Your card {card} is temporarily blocked. You can unblock it at any Smart Teller.",
                    "Kadi yako {card} imezuiwa kwa muda. Unaweza kuifungua kwenye Smart Teller yoyote."}),
            Map.entry("CARD_UNBLOCKED", new String[]{
                    "Your card {card} is unblocked and ready to use. If this was not you, call us now.",
                    "Kadi yako {card} imefunguliwa na iko tayari kutumika. Ikiwa si wewe, tupigie sasa."}),
            Map.entry("ACCOUNT_REACTIVATED", new String[]{
                    "Your account {account} is active again. Ref {ref}.",
                    "Akaunti yako {account} imefufuliwa. Kumb. {ref}."}),
            Map.entry("MOBILE_CHANGED_OLD", new String[]{
                    "The phone number on your bank account was changed to {mobile} at a Smart Teller. If this was not you, call us now.",
                    "Nambari ya simu ya akaunti yako ya benki imebadilishwa kuwa {mobile} kwenye Smart Teller. Ikiwa si wewe, tupigie sasa."}),
            Map.entry("MOBILE_CHANGED_NEW", new String[]{
                    "This number now receives your bank codes and alerts. Ref {ref}.",
                    "Nambari hii sasa itapokea misimbo na arifa za benki yako. Kumb. {ref}."}),
            Map.entry("BIOMETRICS_ENROLLED", new String[]{
                    "Your fingerprints were registered with the bank at a Smart Teller. If this was not you, call us now.",
                    "Alama zako za vidole zimesajiliwa na benki kwenye Smart Teller. Ikiwa si wewe, tupigie sasa."}),
            Map.entry("SIGNATURE_UPDATED", new String[]{
                    "Your specimen signature was updated at a Smart Teller. If this was not you, call us now.",
                    "Sahihi yako ya kielelezo imesasishwa kwenye Smart Teller. Ikiwa si wewe, tupigie sasa."}),
            Map.entry("STATEMENT_FEE", new String[]{
                    "{amount} charged for your {type} statement on {account}. Ref {ref}.",
                    "{amount} imetozwa kwa taarifa ya {type} kwenye {account}. Kumb. {ref}."}),
            Map.entry("GENERIC_RECEIPT", new String[]{
                    "Smart Teller receipt {ref}: {summary}", "Risiti ya Smart Teller {ref}: {summary}"}));

    private final Messaging messaging;
    private final JdbcClient jdbc;

    public SmsService(Messaging messaging, JdbcClient jdbc) {
        this.messaging = messaging;
        this.jdbc = jdbc;
    }

    public record Sent(String messageId, String status) {}

    public Sent send(String sessionId, String template, String mobile, String language, Map<String, String> params) {
        String[] t = TEMPLATES.get(template);
        if (t == null) throw Errors.validation("template", "Unknown SMS template " + template);
        String text = "sw".equals(language) ? t[1] : t[0];
        for (var e : params.entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        String id = Ids.next("sms");
        String status;
        try {
            messaging.sendSms(mobile, text);
            status = "QUEUED";
        } catch (RuntimeException e) {
            status = "FAILED";
        }
        // OTP codes are never stored in the outbox in clear.
        String stored = "OTP".equals(template) ? text.replace(params.getOrDefault("code", "######"), "••••••") : text;
        jdbc.sql("INSERT INTO sms_messages (id, session_id, template, mobile, language, body, status) VALUES (:id, :s, :t, :m, :l, :b, :st)")
                .param("id", id).param("s", sessionId).param("t", template).param("m", mobile)
                .param("l", "sw".equals(language) ? "sw" : "en").param("b", stored).param("st", status).update();
        return new Sent(id, status);
    }

    /** +2547•• ••• 678 */
    public static String mask(String mobile) {
        if (mobile == null || mobile.length() < 7) return mobile;
        String local = mobile.startsWith("+254") ? mobile.substring(4) : mobile;
        return "+254 " + local.charAt(0) + "•• ••• " + local.substring(local.length() - 3);
    }

    /** 0712345678 / 712345678 / +254712345678 -> +254712345678, else VALIDATION_ERROR. */
    public static String normalise(String field, String mobile) {
        String m = mobile == null ? "" : mobile.replaceAll("[\\s-]", "");
        if (m.matches("0[17]\\d{8}")) return "+254" + m.substring(1);
        if (m.matches("[17]\\d{8}")) return "+254" + m;
        if (m.matches("\\+254[17]\\d{8}")) return m;
        if (m.matches("254[17]\\d{8}")) return "+" + m;
        throw Errors.validation(field, "Enter a Kenyan mobile number, e.g. 0712 345 678");
    }
}
