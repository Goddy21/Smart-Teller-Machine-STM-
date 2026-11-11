package com.stm.middleware.common;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A small SMTP client (RFC 5321) for plain-text mail: STARTTLS, implicit TLS or none, and AUTH PLAIN / LOGIN.
 * The server certificate is checked against the JVM trust store and the host name.
 * Kept dependency-free on purpose: the middleware sends only short notification mails.
 */
public final class SmtpClient {
    public enum Security { STARTTLS, SSL, NONE }

    public record Config(String host, int port, Security security, String username, String password, int timeoutSeconds) {}

    private final Config cfg;
    private Socket socket;
    private BufferedReader in;
    private OutputStream out;

    public SmtpClient(Config cfg) {
        this.cfg = cfg;
    }

    /** Sends one plain-text message. {@code from} may be "Name <address>" or a bare address. */
    public void send(String from, String to, String subject, String text) throws IOException {
        send(from, to, subject, text, null);
    }

    /** Sends one message; with {@code html} it goes as text + HTML (multipart/alternative), so every mail program can show it. */
    public void send(String from, String to, String subject, String text, String html) throws IOException {
        int timeout = Math.max(1, cfg.timeoutSeconds()) * 1000;
        try {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(cfg.host(), cfg.port()), timeout);
            plain.setSoTimeout(timeout);
            socket = cfg.security() == Security.SSL ? tls(plain) : plain;
            streams();
            expect(220);
            List<String> ext = ehlo();
            if (cfg.security() == Security.STARTTLS) {
                if (ext.stream().noneMatch(l -> l.toUpperCase(Locale.ROOT).startsWith("STARTTLS"))) {
                    throw new IOException("The mail server does not offer STARTTLS (set SMTP_SECURITY to SSL or NONE)");
                }
                command("STARTTLS", 220);
                socket = tls(socket);
                streams();
                ext = ehlo();
            }
            if (cfg.username() != null && !cfg.username().isBlank()) auth(ext);
            command("MAIL FROM:<" + address(from) + ">", 250);
            command("RCPT TO:<" + address(to) + ">", 250, 251);
            command("DATA", 354);
            write(message(from, to, subject, text, html) + "\r\n.");
            expect(250);
            try { command("QUIT", 221); } catch (IOException ignored) { /* already delivered */ }
        } finally {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void auth(List<String> ext) throws IOException {
        String mechs = ext.stream().filter(l -> l.toUpperCase(Locale.ROOT).startsWith("AUTH")).findFirst().orElse("").toUpperCase(Locale.ROOT);
        if (mechs.contains("PLAIN") || !mechs.contains("LOGIN")) {
            String token = b64("\0" + cfg.username() + "\0" + cfg.password());
            command("AUTH PLAIN " + token, 235);
        } else {
            command("AUTH LOGIN", 334);
            command(b64(cfg.username()), 334);
            command(b64(cfg.password()), 235);
        }
    }

    private SSLSocket tls(Socket under) throws IOException {
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(under, cfg.host(), cfg.port(), true);
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");   // the certificate must match the host name
        s.setSSLParameters(p);
        s.startHandshake();
        return s;
    }

    private void streams() throws IOException {
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        out = socket.getOutputStream();
    }

    private List<String> ehlo() throws IOException {
        write("EHLO stm-middleware");
        return expect(250);
    }

    private List<String> command(String line, int... ok) throws IOException {
        write(line);
        return expect(ok);
    }

    private void write(String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads one (possibly multi-line) reply; returns its text lines without the code. */
    private List<String> expect(int... ok) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        int code;
        do {
            line = in.readLine();
            if (line == null || line.length() < 3) throw new IOException("The mail server closed the connection");
            code = Integer.parseInt(line.substring(0, 3));
            lines.add(line.length() > 4 ? line.substring(4) : "");
        } while (line.length() > 3 && line.charAt(3) == '-');
        for (int c : ok) if (c == code) return lines;
        throw new IOException("Mail server answered " + line);
    }

    static String message(String from, String to, String subject, String text) {
        return message(from, to, subject, text, null);
    }

    private static String b64Body(String s) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(s.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    static String message(String from, String to, String subject, String text, String html) {
        String body = b64Body(text);
        String domain = address(from).contains("@") ? address(from).substring(address(from).indexOf('@') + 1) : "stm.local";
        return "From: " + header(from) + "\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + encodeWord(subject) + "\r\n"
                + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@" + domain + ">\r\n"
                + "MIME-Version: 1.0\r\n"
                + (html == null
                ? "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n" + body
                : alternative(body, b64Body(html)));
    }

    /** Plain text first, HTML last: mail programs show the last part they understand. */
    private static String alternative(String textB64, String htmlB64) {
        String boundary = "stm-" + UUID.randomUUID().toString().replace("-", "");
        return "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"\r\n"
                + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n" + textB64 + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n" + htmlB64 + "\r\n"
                + "--" + boundary + "--";
    }

    /** "Name <a@b>" -> a@b; "a@b" -> a@b. */
    static String address(String s) {
        int lt = s.indexOf('<'), gt = s.indexOf('>');
        return (lt >= 0 && gt > lt ? s.substring(lt + 1, gt) : s).trim();
    }

    /** Keeps the display name readable when it is not plain ASCII. */
    private static String header(String from) {
        int lt = from.indexOf('<');
        if (lt <= 0) return from.trim();
        return encodeWord(from.substring(0, lt).trim()) + " <" + address(from) + ">";
    }

    private static String encodeWord(String s) {
        return s.chars().allMatch(c -> c >= 32 && c < 127) ? s
                : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
