-- Deletes ONE test account (by account number) with its card(s), its application and the kiosk sessions where it was
-- opened or used for a card. The customer and their other accounts stay; if this was their only account, the
-- customer's bank records go too. The audit journal is kept (append-only by design).
--   preview:  docker compose exec -T postgres psql -U stm -d stm -v acct=012345671044 -v doit=0 < delete-test-account.sql
--   delete:   docker compose exec -T postgres psql -U stm -d stm -v acct=012345671044 -v doit=1 < delete-test-account.sql
\set ON_ERROR_STOP 1
BEGIN;
CREATE TEMP TABLE x_acc ON COMMIT DROP AS
  SELECT id, customer_id FROM sim_accounts
   WHERE account_number = :'acct'
     AND customer_id NOT IN ('CIF00204417', 'CIF00391144', 'CIF00482913', 'CIF00900001');   -- built-in test customers
CREATE TEMP TABLE x_ses ON COMMIT DROP AS
  SELECT session_id AS id FROM applications WHERE account_number = :'acct' AND customer_id IN (SELECT customer_id FROM x_acc)
  UNION SELECT session_id FROM card_orders WHERE account_id IN (SELECT id FROM x_acc)
  UNION SELECT session_id FROM fee_quotes WHERE debit_account_id IN (SELECT id FROM x_acc);
CREATE TEMP TABLE x_cust ON COMMIT DROP AS     -- the customer goes too only when this was their last account
  SELECT DISTINCT customer_id AS id FROM x_acc a
   WHERE NOT EXISTS (SELECT 1 FROM sim_accounts o WHERE o.customer_id = a.customer_id AND o.id NOT IN (SELECT id FROM x_acc));

\echo '== Account that will be deleted'
SELECT a.account_number, c.id AS customer, c.full_name, (SELECT count(*) FROM sim_cards k WHERE k.account_id = a.id) AS cards
  FROM sim_accounts a JOIN sim_customers c ON c.id = a.customer_id WHERE a.id IN (SELECT id FROM x_acc);
\echo '== Kiosk sessions that will be deleted'
SELECT s.id, s.started_at, s.flow, s.customer_name FROM stm_sessions s WHERE s.id IN (SELECT id FROM x_ses) ORDER BY s.started_at;
\echo '== The customer''s other accounts (kept)'
SELECT o.account_number FROM sim_accounts o
 WHERE o.customer_id IN (SELECT customer_id FROM x_acc) AND o.id NOT IN (SELECT id FROM x_acc);
\echo '== Customer deleted too (no accounts left)?'
SELECT count(*) AS customer_deleted FROM x_cust;

DELETE FROM captures WHERE owner_id IN (SELECT id FROM applications WHERE session_id IN (SELECT id FROM x_ses))
   OR owner_id IN (SELECT id FROM identity_verifications WHERE session_id IN (SELECT id FROM x_ses))
   OR owner_id IN (SELECT id FROM biometric_checks WHERE session_id IN (SELECT id FROM x_ses))
   OR owner_id IN (SELECT ref FROM customer_lookups WHERE session_id IN (SELECT id FROM x_ses))
   OR owner_id IN (SELECT id FROM card_orders WHERE session_id IN (SELECT id FROM x_ses));
DELETE FROM sms_messages WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM customer_tokens WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM otps WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM identity_verifications WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM biometric_checks WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM customer_lookups WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM fee_quotes WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM card_orders WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM card_auths WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM statements WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM deposit_targets WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM cash_deposits WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM fx_quotes WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM cash_withdrawals WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM fx_exchanges WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM receipts WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM teller_calls WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM service_events WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM mobile_changes WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM applications WHERE session_id IN (SELECT id FROM x_ses);
DELETE FROM stm_sessions WHERE id IN (SELECT id FROM x_ses);
-- the bank side
DELETE FROM card_auths WHERE card_id IN (SELECT id FROM sim_cards WHERE account_id IN (SELECT id FROM x_acc));
DELETE FROM sim_postings WHERE account_id IN (SELECT id FROM x_acc);
DELETE FROM sim_cards WHERE account_id IN (SELECT id FROM x_acc);
DELETE FROM sim_accounts WHERE id IN (SELECT id FROM x_acc);
DELETE FROM sim_biometrics WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_face_references WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_signatures WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_mobile_changes WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_cards WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_customers WHERE id IN (SELECT id FROM x_cust);

\if :doit
  COMMIT;
  \echo '== Deleted.'
\else
  ROLLBACK;
  \echo '== Preview only: nothing was deleted. Run again with -v doit=1 to delete.'
\endif
