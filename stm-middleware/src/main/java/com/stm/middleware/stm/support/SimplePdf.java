package com.stm.middleware.stm.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal text-only PDF writer (A4, Courier), enough for an account statement without a PDF library.
 * Lines starting with "#" are printed bold. 58 lines per page; the footer goes on every page.
 */
public final class SimplePdf {
    private static final int LINES_PER_PAGE = 58;

    private SimplePdf() {}

    public static int pages(int lineCount) {
        return Math.max(1, (lineCount + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
    }

    public static byte[] render(List<String> lines, String footer) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < Math.max(1, lines.size()); i += LINES_PER_PAGE) {
            pages.add(lines.subList(i, Math.min(lines.size(), i + LINES_PER_PAGE)));
        }
        int n = pages.size();
        // objects: 1 catalog, 2 pages, 3 font, 4 bold font, then (page, content) pairs
        List<String> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < n; p++) kids.append(5 + p * 2).append(" 0 R ");
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>");
        for (int p = 0; p < n; p++) {
            StringBuilder s = new StringBuilder("BT\n");
            int y = 800;
            for (String line : pages.get(p)) {
                boolean bold = line.startsWith("#");
                String text = bold ? line.substring(1) : line;
                s.append(bold ? "/F2 9.5 Tf\n" : "/F1 9 Tf\n");
                s.append("1 0 0 1 40 ").append(y).append(" Tm (").append(escape(text)).append(") Tj\n");
                y -= 13;
            }
            s.append("/F1 7.5 Tf\n1 0 0 1 40 28 Tm (").append(escape(footer + "   Page " + (p + 1) + " of " + n)).append(") Tj\nET");
            String content = s.toString();
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + (6 + p * 2) + " 0 R >>");
            objects.add("<< /Length " + content.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + content + "\nendstream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) x.append(String.format("%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append(c);
            else if (c == '•') b.append('*');
            else if (c < 32 || c > 255) b.append('?');
            else b.append(c);
        }
        return b.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
