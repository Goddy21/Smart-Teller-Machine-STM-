-- V5: KYC status and SIM-swap checks for the kiosk, and Idempotency-Key on every write.

-- Every /stm/v1 write may now carry an Idempotency-Key; the operation is the method and path.
ALTER TABLE idempotency_records ALTER COLUMN operation TYPE VARCHAR(200);

-- Simulated core banking: the KYC record of each customer.
ALTER TABLE sim_customers
    ADD COLUMN kyc_reviewed_at TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    ADD COLUMN risk_rating     VARCHAR(10)    NOT NULL DEFAULT 'LOW',   -- LOW, MEDIUM, HIGH
    ADD COLUMN pep             BOOLEAN        NOT NULL DEFAULT false,
    ADD COLUMN kyc_flags       TEXT           NOT NULL DEFAULT '[]';    -- JSON array of codes

-- Achieng: reviewed ten months ago, low risk, everything current.
UPDATE sim_customers SET kyc_reviewed_at = now() - interval '10 months' WHERE id = 'CIF00482913';
-- Brian: last reviewed over three years ago, medium risk, no KRA PIN on file, address not verified.
UPDATE sim_customers SET kyc_reviewed_at = now() - interval '3 years 2 months', risk_rating = 'MEDIUM', kra_pin = NULL,
       kyc_flags = '["ADDRESS_UNVERIFIED"]' WHERE id = 'CIF00391144';

-- Simulated mobile operator: SIM swaps, stored as "hours ago" so a recent swap stays recent in a long-running demo.
CREATE TABLE sim_sim_swaps (
    msisdn     VARCHAR(20) PRIMARY KEY,
    hours_ago  INT         NOT NULL
);
INSERT INTO sim_sim_swaps (msisdn, hours_ago) VALUES
 ('+254712345678', 9600),   -- Achieng: about 400 days ago, low risk
 ('+254722987654', 20);     -- Brian: 20 hours ago, codes are blocked (SIM_SWAP_RISK)
