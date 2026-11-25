-- The bank's own reference photo: the face that passed the check at account opening (or a later kiosk enrolment).
-- Card services and other "is this really the customer" checks compare the live face with it first, and only use
-- the national registry (IPRS) photo when the bank holds none. The image itself stays in captures; core banking
-- (simulated here) keeps its fingerprint.
CREATE TABLE sim_face_references (
    customer_id   VARCHAR(30)    PRIMARY KEY REFERENCES sim_customers (id),
    image_sha256  CHAR(64)       NOT NULL,
    size_bytes    INT            NOT NULL,
    source        VARCHAR(20)    NOT NULL,      -- STM_ONBOARDING, STM_ENROLMENT, BRANCH
    enrolled_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- Which photo the face was compared with: BANK_PHOTO, IPRS_PHOTO or TEST_MODE (finger: matched_against).
ALTER TABLE biometric_checks ADD COLUMN face_against VARCHAR(20);
UPDATE biometric_checks SET face_against = 'IPRS_PHOTO' WHERE face_result IS NOT NULL;

-- The card-journeys test customer has a photo on file, so card services shows "face against BANK_PHOTO".
INSERT INTO sim_face_references (customer_id, image_sha256, size_bytes, source)
SELECT 'CIF00900001', repeat('9', 64), 0, 'BRANCH' WHERE EXISTS (SELECT 1 FROM sim_customers WHERE id = 'CIF00900001');
