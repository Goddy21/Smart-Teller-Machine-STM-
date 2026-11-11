-- When the kiosk last called the gateway at all (signing in, a customer session, anything), not only its health report.
-- The fleet page counts a kiosk as online when it was heard from in the last 3 minutes by either.
ALTER TABLE terminals ADD COLUMN last_contact_at TIMESTAMPTZ(3);
