package com.stm.middleware.journey;

import com.stm.middleware.journey.ProductRepository.Product;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Renders the A4 account agreement as printable HTML (the kiosk prints it from Chromium).
 * PLACEHOLDER TEXT: replace {@link #BODY} with the bank's legal-approved wording (and translations).
 */
@Service
public class AgreementService {
    /** Bump whenever the legal text changes: the signed version is recorded against the account. */
    public static final String VERSION = "ACC-TC-v0.1-DEMO";

    private static final String BODY = """
            <h2>1. Account</h2>
            <p>The Bank will open the {{product}} account in the name shown above, subject to successful identity verification.</p>
            <h2>2. Terms of use</h2>
            <p>DEMO TEMPLATE: replace with the bank-approved Terms &amp; Conditions, fees schedule and privacy notice.</p>
            <h2>3. Consent</h2>
            <p>The customer consents to the verification of identity against national registries and the processing of
            personal and biometric data for the purpose of opening and operating this account.</p>
            """;

    public String render(KioskSession s, Product product, String branchName) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8"><title>Account Agreement</title>
                <style>
                  @page { size: A4; margin: 20mm; }
                  body { font-family: Arial, sans-serif; font-size: 11pt; color: #111; }
                  h1 { font-size: 18pt; } h2 { font-size: 12pt; margin-top: 14pt; }
                  table { border-collapse: collapse; } td { padding: 3pt 12pt 3pt 0; vertical-align: top; }
                  .sig { margin-top: 40pt; border-top: 1px solid #000; width: 260pt; padding-top: 4pt; }
                </style></head><body>
                <h1>Account Agreement</h1>
                <table>
                  <tr><td>Customer</td><td><b>{{name}}</b></td></tr>
                  <tr><td>Product</td><td>{{product}} ({{currency}})</td></tr>
                  <tr><td>Branch</td><td>{{branch}}</td></tr>
                  <tr><td>Date</td><td>{{date}}</td></tr>
                  <tr><td>Agreement version</td><td>{{version}}</td></tr>
                </table>
                {{body}}
                <div class="sig">Customer signature</div>
                </body></html>"""
                .replace("{{body}}", BODY.replace("{{product}}", esc(product.name())))
                .replace("{{name}}", esc(s.fullName()))
                .replace("{{product}}", esc(product.name()))
                .replace("{{currency}}", esc(product.currency()))
                .replace("{{branch}}", esc(branchName))
                .replace("{{date}}", LocalDate.now().toString())
                .replace("{{version}}", VERSION);
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
