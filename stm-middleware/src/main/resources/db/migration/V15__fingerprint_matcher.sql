-- Real fingerprint comparison (SourceAFIS). The bank keeps a matcher template per enrolled finger; card services and
-- other identity checks compare the live print with it (any enrolled finger), instead of trusting the finger label.
ALTER TABLE sim_biometrics ADD COLUMN matcher_template BYTEA;

-- How each check was decided: MATCHER (prints compared; finger_raw_score = similarity, pass at 40+) or null.
ALTER TABLE biometric_checks ADD COLUMN finger_method VARCHAR(20);
ALTER TABLE biometric_checks ADD COLUMN finger_raw_score NUMERIC(8,1);
