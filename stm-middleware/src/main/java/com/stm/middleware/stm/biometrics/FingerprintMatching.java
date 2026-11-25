package com.stm.middleware.stm.biometrics;

import com.machinezoo.sourceafis.FingerprintCompatibility;
import com.machinezoo.sourceafis.FingerprintImage;
import com.machinezoo.sourceafis.FingerprintImageOptions;
import com.machinezoo.sourceafis.FingerprintMatcher;
import com.machinezoo.sourceafis.FingerprintTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.Optional;

/**
 * Compares fingerprints (SourceAFIS: minutiae extraction and matching). The kiosk sends the print as an image
 * (fingerprint.image: PNG, JPEG or BMP, 500 dpi by default) or as an ISO 19794-2 / ANSI 378 template; either is
 * turned into a matcher template, which is what the bank stores at enrolment and what later prints are compared with.
 * Similarity scores: 40 is about 1 false match in 10,000 different fingers; the same finger usually scores 100+.
 */
@Component
public class FingerprintMatching {
    private static final Logger log = LoggerFactory.getLogger(FingerprintMatching.class);
    private static final int MIN_SIDE_PX = 100;   // smaller is not a fingerprint image (placeholders, previews)

    private final double dpi;
    private final double threshold;

    public FingerprintMatching(@Value("${stm.biometrics.fingerprint-dpi:500}") double dpi,
                               @Value("${stm.biometrics.fingerprint-threshold:40}") double threshold) {
        this.dpi = dpi;
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    /** A matcher template from the print image, else from an ISO/ANSI template. Empty when neither is usable. */
    public Optional<byte[]> template(byte[] image, byte[] isoTemplate) {
        if (image != null && image.length > 0 && bigEnough(image)) {
            try {
                return Optional.of(new FingerprintTemplate(new FingerprintImage(image, new FingerprintImageOptions().dpi(dpi)))
                        .toByteArray());
            } catch (RuntimeException e) {
                log.info("Fingerprint image could not be read ({} bytes): {}", image.length, e.getMessage());
            }
        }
        if (isoTemplate != null && isoTemplate.length > 16) {
            try {
                return Optional.of(FingerprintCompatibility.importTemplate(isoTemplate).toByteArray());
            } catch (RuntimeException e) {
                // not an ISO/ANSI template (many scanners send their own format): nothing to compare
            }
        }
        return Optional.empty();
    }

    /** True when these bytes are a matcher template made by {@link #template}. */
    public boolean isTemplate(byte[] bytes) {
        return parse(bytes).isPresent();
    }

    /** The best similarity of the live print to any of the stored ones; empty when nothing can be compared. */
    public Optional<Double> bestScore(byte[] probe, Collection<byte[]> stored) {
        Optional<FingerprintTemplate> p = parse(probe);
        if (p.isEmpty()) return Optional.empty();
        FingerprintMatcher matcher = new FingerprintMatcher(p.get());
        double best = -1;
        for (byte[] s : stored) {
            Optional<FingerprintTemplate> c = parse(s);
            if (c.isPresent()) best = Math.max(best, matcher.match(c.get()));
        }
        return best < 0 ? Optional.empty() : Optional.of(best);
    }

    /** 0..1 for display, 0.5 at the threshold. */
    public double normalised(double raw) {
        return Math.round(raw / (raw + threshold) * 1000) / 1000.0;
    }

    private static Optional<FingerprintTemplate> parse(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return Optional.empty();
        try {
            return Optional.of(new FingerprintTemplate(bytes));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean bigEnough(byte[] image) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            return img == null || (img.getWidth() >= MIN_SIDE_PX && img.getHeight() >= MIN_SIDE_PX);
        } catch (Exception e) {
            return true;   // let the matcher decide (WSQ and other formats ImageIO does not read)
        }
    }
}
