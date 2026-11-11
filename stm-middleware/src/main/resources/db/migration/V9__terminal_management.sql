-- Kiosks are created and looked after in the portal (Kiosk fleet): a display name, where the machine stands, notes,
-- when its token was last replaced, and RETIRED for a kiosk that was removed but has history to keep.
ALTER TABLE terminals ADD COLUMN name             VARCHAR(80);
ALTER TABLE terminals ADD COLUMN location         VARCHAR(160);
ALTER TABLE terminals ADD COLUMN notes            VARCHAR(500);
ALTER TABLE terminals ADD COLUMN token_rotated_at TIMESTAMPTZ(3);
ALTER TABLE terminals ADD COLUMN retired_at       TIMESTAMPTZ(3);
ALTER TABLE terminals ADD COLUMN updated_at       TIMESTAMPTZ(3);

ALTER TABLE terminals DROP CONSTRAINT ck_terminals_status;
ALTER TABLE terminals ADD CONSTRAINT ck_terminals_status CHECK (status IN ('ACTIVE', 'REVOKED', 'RETIRED'));
