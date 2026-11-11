package com.stm.middleware.common;

import java.util.List;

/**
 * The HTML version of the portal's emails, in the Blue River colours (navy band, red accent line). Built with tables
 * and inline styles only, because mail programs ignore style sheets, and without images, because they block them.
 * Every email is also sent as plain text; mail programs that cannot show HTML use that.
 */
public final class EmailTemplate {
    private static final String NAVY = "#0D1270", BLUE = "#1C24AA", RED = "#EE1C25", INK = "#14173A", MUTED = "#5A5F80",
            SOFT = "#E2E4F8", PAGE = "#EEF0FA", LINE = "#D9DCF0";
    private static final String FONT = "font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";

    private EmailTemplate() {}

    /** One label and value shown in the highlighted box (a code, or username and password). */
    public record Fact(String label, String value, boolean large) {}

    /**
     * @param preheader the short line mail programs show next to the subject
     * @param intro     paragraphs above the box
     * @param facts     what the email is about, shown in the box
     * @param notes     paragraphs below the box
     * @param warning   the security note in the red-edged panel, or null
     */
    public static String html(String title, String preheader, String greeting, List<String> intro, List<Fact> facts,
                              List<String> notes, String warning) {
        StringBuilder b = new StringBuilder(4096);
        b.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"color-scheme\" content=\"light only\"><title>").append(esc(title)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:").append(PAGE).append(";\">")
                .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:").append(PAGE).append(";\">")
                .append(esc(preheader)).append("</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:").append(PAGE)
                .append(";\"><tr><td align=\"center\" style=\"padding:28px 12px;\">")
                .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;max-width:560px;")
                .append("background:#FFFFFF;border-radius:16px;overflow:hidden;border:1px solid ").append(LINE).append(";\">");

        // Brand band: navy to blue, with the red line of the logo underneath.
        b.append("<tr><td bgcolor=\"").append(NAVY).append("\" style=\"background:").append(NAVY).append(";background:linear-gradient(135deg,")
                .append(NAVY).append(' ').append("0%,").append(BLUE).append(" 100%);padding:26px 32px;\">")
                .append("<div style=\"").append(FONT).append("font-size:22px;font-weight:800;letter-spacing:1.5px;color:#FFFFFF;\">BLUE ")
                .append("<span style=\"color:#FF5A61;\">RIVER</span></div>")
                .append("<div style=\"").append(FONT).append("font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#C9CDF5;padding-top:2px;\">")
                .append("Technology Solutions</div>")
                .append("<div style=\"").append(FONT).append("font-size:14px;color:#FFFFFF;padding-top:14px;\">Smart Teller Portal</div>")
                .append("</td></tr>")
                .append("<tr><td bgcolor=\"").append(RED).append("\" style=\"background:").append(RED)
                .append(";height:4px;line-height:4px;font-size:0;\">&nbsp;</td></tr>");

        b.append("<tr><td style=\"padding:30px 32px 8px 32px;").append(FONT).append("color:").append(INK).append(";\">")
                .append("<h1 style=\"margin:0 0 14px 0;font-size:20px;line-height:1.3;color:").append(NAVY).append(";\">").append(esc(title)).append("</h1>")
                .append("<p style=\"margin:0 0 10px 0;font-size:15px;line-height:1.55;\">").append(esc(greeting)).append("</p>");
        for (String p : intro) b.append(para(p, INK));
        b.append("</td></tr>");

        b.append("<tr><td style=\"padding:8px 32px 8px 32px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:").append(SOFT)
                .append(";border-radius:12px;border:1px solid #C9CDF5;\">");
        for (int i = 0; i < facts.size(); i++) {
            Fact f = facts.get(i);
            b.append("<tr><td align=\"").append(f.large() ? "center" : "left").append("\" style=\"padding:")
                    .append(i == 0 ? "18px" : "0").append(" 22px 18px 22px;").append(FONT).append("\">")
                    .append("<div style=\"font-size:11px;letter-spacing:1.5px;text-transform:uppercase;color:").append(MUTED).append(";\">")
                    .append(esc(f.label())).append("</div>")
                    .append("<div style=\"font-family:Consolas,'Courier New',monospace;font-weight:700;color:").append(NAVY).append(';')
                    .append(f.large() ? "font-size:34px;letter-spacing:10px;padding:6px 0 0 10px;" : "font-size:18px;padding-top:4px;")
                    .append("\">").append(esc(f.value())).append("</div></td></tr>");
        }
        b.append("</table></td></tr>");

        b.append("<tr><td style=\"padding:14px 32px 6px 32px;").append(FONT).append("\">");
        for (String p : notes) b.append(para(p, INK));
        b.append("</td></tr>");

        if (warning != null) {
            b.append("<tr><td style=\"padding:6px 32px 26px 32px;\">")
                    .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                    .append("<td width=\"4\" bgcolor=\"").append(RED).append("\" style=\"background:").append(RED).append(";border-radius:4px;font-size:0;\">&nbsp;</td>")
                    .append("<td style=\"padding:10px 14px;background:#FDF1F1;").append(FONT).append("font-size:13px;line-height:1.55;color:#5B1A1D;\">")
                    .append(esc(warning)).append("</td></tr></table></td></tr>");
        }

        b.append("<tr><td style=\"padding:18px 32px 22px 32px;border-top:1px solid ").append(LINE).append(';').append(FONT)
                .append("font-size:12px;line-height:1.6;color:").append(MUTED).append(";\">")
                .append("<b style=\"color:").append(NAVY).append(";\">Blue River Smart Teller</b><br>")
                .append("This is an automatic message from the STM portal. Please do not reply to it.")
                .append("</td></tr></table></td></tr></table></body></html>");
        return b.toString();
    }

    private static String para(String text, String colour) {
        return "<p style=\"margin:0 0 10px 0;font-size:15px;line-height:1.55;color:" + colour + ";\">" + esc(text) + "</p>";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
