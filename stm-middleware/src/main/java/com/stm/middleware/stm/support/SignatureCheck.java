package com.stm.middleware.stm.support;

import com.stm.middleware.stm.core.Errors;
import com.stm.middleware.stm.core.StmException;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Checks that a captured signature is a real one before it becomes the specimen on the account: a PNG or JPEG of a
 * usable size, not an empty pad and not a filled-in box. The ink is what differs from the background (a transparent
 * canvas or a plain colour), so dark-on-light and light-on-dark pads both work.
 */
public final class SignatureCheck {
    public static final int MIN_WIDTH = 120, MIN_HEIGHT = 40;
    public static final double MIN_INK = 0.002, MAX_INK = 0.60;

    public record Quality(int width, int height, double inkRatio) {}

    private SignatureCheck() {}

    public static Quality validate(String field, byte[] image) {
        if (image == null || image.length < 32) throw unreadable("The signature is empty.", "EMPTY");
        String mime = Captures.mimeOf(image);
        if (!mime.startsWith("image/")) throw Errors.validation(field, field + " must be a PNG or JPEG image");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            img = null;
        }
        if (img == null) throw Errors.validation(field, field + " could not be read as an image");
        int w = img.getWidth(), h = img.getHeight();
        if (w < MIN_WIDTH || h < MIN_HEIGHT) throw unreadable("The signature image is too small.", "TOO_SMALL");
        long total = (long) w * h, transparent = 0;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int p : px) if ((p >>> 24) < 40) transparent++;
        long ink = 0;
        if (transparent > total / 2) {
            ink = total - transparent;                      // strokes on a transparent pad
        } else {
            int bg = luminance(px[0]);                      // plain background: what differs from the corner colour
            for (int p : px) if ((p >>> 24) >= 40 && Math.abs(luminance(p) - bg) > 60) ink++;
        }
        double ratio = (double) ink / total;
        if (ratio < MIN_INK) throw unreadable("We could not see a signature. Please sign inside the box.", "BLANK");
        if (ratio > MAX_INK) throw unreadable("The signature covers the whole box. Please clear it and sign again.", "FILLED");
        return new Quality(w, h, Math.round(ratio * 10000) / 10000.0);
    }

    private static int luminance(int argb) {
        return (int) (0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF));
    }

    private static StmException unreadable(String en, String reason) {
        return Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATURE_UNREADABLE", en,
                "Hatukuweza kusoma sahihi yako. Tafadhali futa na usaini tena ndani ya kisanduku.", true).detail("reason", reason);
    }
}
