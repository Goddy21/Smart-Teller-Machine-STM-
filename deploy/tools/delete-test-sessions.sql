-- Deletes ALL customer sessions and everything that happened in them: applications, identity checks, card orders,
-- cash, forex, statements, receipts, teller calls, text messages and the captured images.
-- Kept: customers and accounts (run delete-test-customer.sql for those first), kiosks, staff accounts, products,
-- the audit journal and the escrow ledger (both append-only by design), and kiosk health history.
-- Run it when no customer is using a kiosk.
--   preview:  docker compose exec -T postgres psql -U stm -d stm -v doit=0 < delete-test-sessions.sql
--   delete:   docker compose exec -T postgres psql -U stm -d stm -v doit=1 < delete-test-sessions.sql
\set ON_ERROR_STOP 1
BEGIN;
\echo '== What will be deleted'
SELECT 'customer sessions' AS what, count(*) FROM stm_sessions
UNION ALL SELECT 'applications', count(*) FROM applications
UNION ALL SELECT 'identity and face/finger checks', (SELECT count(*) FROM identity_verifications) + (SELECT count(*) FROM biometric_checks)
UNION ALL SELECT 'card orders', count(*) FROM card_orders
UNION ALL SELECT 'card and account services', count(*) FROM service_events
UNION ALL SELECT 'cash deposits', count(*) FROM cash_deposits
UNION ALL SELECT 'cash withdrawals', count(*) FROM cash_withdrawals
UNION ALL SELECT 'forex exchanges', count(*) FROM fx_exchanges
UNION ALL SELECT 'statements', count(*) FROM statements
UNION ALL SELECT 'teller calls', count(*) FROM teller_calls
UNION ALL SELECT 'text messages', count(*) FROM sms_messages
UNION ALL SELECT 'captured images', count(*) FROM captures;

TRUNCATE stm_sessions CASCADE;      -- and every table that belongs to a session (applications, cash, cards, ...)
TRUNCATE captures;                  -- ID scans, photos, fingerprints, signatures
TRUNCATE sms_messages;
TRUNCATE idempotency_records;       -- remembered kiosk requests ("don't do it twice")

\if :doit
  COMMIT;
  \echo '== Deleted.'
\else
  ROLLBACK;
  \echo '== Preview only: nothing was deleted. Run again with -v doit=1 to delete.'
\endif
