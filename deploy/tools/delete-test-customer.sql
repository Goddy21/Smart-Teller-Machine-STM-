-- Deletes TEST customers opened at the kiosk whose name contains :'name', with their accounts, cards, fingerprints,
-- face photo, signature and applications (and the images captured for them). The built-in test customers are kept.
-- Kept on purpose: customer sessions and the audit journal (the history of what happened), and money transactions.
-- Run on the server, in /opt/deployments/clients/stm/deploy:
--   preview:  docker compose exec -T postgres psql -U stm -d stm -v name=tendwa -v doit=0 < tools/delete-test-customer.sql
--   delete:   docker compose exec -T postgres psql -U stm -d stm -v name=tendwa -v doit=1 < tools/delete-test-customer.sql
\set ON_ERROR_STOP 1
BEGIN;
CREATE TEMP TABLE x_cust ON COMMIT DROP AS
  SELECT id FROM sim_customers
   WHERE full_name ILIKE '%' || :'name' || '%'
     AND id NOT IN ('CIF00204417', 'CIF00391144', 'CIF00482913', 'CIF00900001');   -- built-in test customers
-- applications that became accounts, and the ones that never got that far (in progress, cancelled, referred)
CREATE TEMP TABLE x_app ON COMMIT DROP AS
  SELECT id FROM applications
   WHERE customer_id IN (SELECT id FROM x_cust)
      OR (customer_id IS NULL AND (:'name' = '' OR full_name ILIKE '%' || :'name' || '%'));

\echo '== Applications that will be deleted (by status)'
SELECT status, count(*) FROM applications WHERE id IN (SELECT id FROM x_app) GROUP BY status ORDER BY 2 DESC;
\echo '== Kept: applications of the built-in test customers (not deleted)'
SELECT count(*) FROM applications WHERE customer_id IN ('CIF00204417', 'CIF00391144', 'CIF00482913', 'CIF00900001');
\echo '== Customers that will be deleted'
SELECT c.id, c.full_name, c.id_number, string_agg(a.account_number, ', ' ORDER BY a.account_number) AS accounts,
       (SELECT count(*) FROM sim_cards k WHERE k.customer_id = c.id) AS cards
  FROM sim_customers c LEFT JOIN sim_accounts a ON a.customer_id = c.id
 WHERE c.id IN (SELECT id FROM x_cust) GROUP BY c.id, c.full_name, c.id_number ORDER BY c.id;

-- images: application signature, ID scans, registry photo, face and fingerprint images, lookup scans
DELETE FROM captures WHERE owner_id IN (SELECT id FROM x_app)
   OR owner_id IN (SELECT id FROM identity_verifications WHERE application_id IN (SELECT id FROM x_app))
   OR owner_id IN (SELECT id FROM biometric_checks WHERE application_id IN (SELECT id FROM x_app)
                                                     OR customer_id IN (SELECT id FROM x_cust))
   OR owner_id IN (SELECT ref FROM customer_lookups WHERE customer_id IN (SELECT id FROM x_cust));
DELETE FROM biometric_checks WHERE application_id IN (SELECT id FROM x_app) OR customer_id IN (SELECT id FROM x_cust);
DELETE FROM identity_verifications WHERE application_id IN (SELECT id FROM x_app);
DELETE FROM card_orders WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM customer_tokens WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM customer_lookups WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM applications WHERE id IN (SELECT id FROM x_app);
-- the bank side (simulated core banking and card system)
DELETE FROM sim_postings WHERE account_id IN (SELECT id FROM sim_accounts WHERE customer_id IN (SELECT id FROM x_cust));
DELETE FROM sim_cards WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_accounts WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_biometrics WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_face_references WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_signatures WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_mobile_changes WHERE customer_id IN (SELECT id FROM x_cust);
DELETE FROM sim_customers WHERE id IN (SELECT id FROM x_cust);

\if :doit
  COMMIT;
  \echo '== Deleted.'
\else
  ROLLBACK;
  \echo '== Preview only: nothing was deleted. Run again with -v doit=1 to delete.'
\endif
