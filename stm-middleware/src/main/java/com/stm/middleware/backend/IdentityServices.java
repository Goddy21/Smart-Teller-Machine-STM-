package com.stm.middleware.backend;

import java.time.LocalDate;
import java.util.Optional;

/** IPRS (citizens), immigration (passports), biometric matching, KRA iTax PIN checker, sanctions/PEP screening. */
public interface IdentityServices {

    record RegistryRecord(String source, String documentType, String documentNumber, String country, String serialNumber,
                          String surname, String givenNames, LocalDate dateOfBirth, String gender, String placeOfBirth,
                          String nationality, LocalDate expiry, String scenario) {
        public String fullName() {
            return titleCase(givenNames) + " " + titleCase(surname);
        }

        public String firstName() {
            return titleCase(givenNames.split(" ")[0]);
        }

        static String titleCase(String s) {
            StringBuilder b = new StringBuilder();
            for (String w : s.toLowerCase().split(" ")) {
                if (w.isEmpty()) continue;
                if (!b.isEmpty()) b.append(' ');
                b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return b.toString();
        }
    }

    record DocumentChecks(String expiry, String tamper) {
        public boolean pass() {
            return "PASS".equals(expiry) && "PASS".equals(tamper);
        }
    }

    record MatchResult(String result, double score) {
        public boolean match() {
            return "MATCH".equals(result);
        }
    }

    record KraResult(boolean valid, String taxpayerName, boolean matchesId, String status) {}

    record Screening(String sanctions, String pep) {
        public boolean clear() {
            return "CLEAR".equals(sanctions) && "CLEAR".equals(pep);
        }
    }

    Optional<RegistryRecord> lookup(String documentType, String country, String documentNumber);

    /** Expiry and tamper checks on the scanned document (images from the device agent). */
    DocumentChecks checkDocument(RegistryRecord record, LocalDate documentExpiry, byte[] front, byte[] uv);

    /** The registry photo (JPEG) for the review screen. */
    byte[] registryPhoto(RegistryRecord record);

    MatchResult matchFace(RegistryRecord record, byte[] faceImage, double livenessScore);

    MatchResult matchFingerprint(RegistryRecord record, String finger, byte[] template, int quality);

    KraResult validateKraPin(String kraPin, String idNumber);

    Screening screen(RegistryRecord record);
}
