-- Test customer for every card journey on the kiosk (simulated bank only; the prod profile has no sim_* data).
-- Patrick Musonye Tendwa, ID 33445566, account 0190000105 for look-up. Fingerprints are on file with the bank.
--   0190000101 SAVINGS  ACTIVE card crd_T001        → lost / stolen / damaged
--   0190000102 CURRENT  ACTIVE card crd_T002, expiring within 2 months (kept so at every start) → renew
--   0190000103 SAVINGS  card crd_T003 blocked for FRAUD → fraud-blocked (replacement allowed at the kiosk)
--   0190000104 SAVINGS  card crd_T004 not yet activated (INACTIVE) → activate a card
--   0190000105 SAVINGS  no card → new card
-- Card PINs (for C-8/S-2 tests): 2468.
INSERT INTO sim_customers (id, id_number, full_name, first_name, mobile, kra_pin, address, customer_since, kyc_reviewed_at)
VALUES ('CIF00900001', '33445566', 'Patrick Musonye Tendwa', 'Patrick', '+254700900001', 'A033445566T',
        '{"line1":"Ngong Road","town":"Nairobi","county":"Nairobi"}', DATE '2019-02-11', now() - interval '6 months');

INSERT INTO sim_registry (document_number, document_type, serial_number, surname, given_names, date_of_birth, gender,
                          place_of_birth, expiry, kra_pin, kra_status, scenario, notes)
VALUES ('33445566', 'NATIONAL_ID', '240033445', 'TENDWA', 'PATRICK MUSONYE', '1988-07-19', 'M', 'Kakamega', NULL,
        'A033445566T', 'ACTIVE', 'EXISTING_CUSTOMER', 'Card-journeys test customer CIF00900001 (every card state)');

INSERT INTO sim_accounts (id, account_number, customer_id, type, product_code, ledger, available, card_eligible, status,
                          status_reason, last_activity_at) VALUES
 ('acc_T1SV', '0190000101', 'CIF00900001', 'SAVINGS', 'SAV_PERSONAL', 25400.00, 25400.00, true, 'ACTIVE', NULL, now() - interval '3 days'),
 ('acc_T2CU', '0190000102', 'CIF00900001', 'CURRENT', 'CUR_PERSONAL', 61800.00, 61800.00, true, 'ACTIVE', NULL, now() - interval '1 day'),
 ('acc_T3SV', '0190000103', 'CIF00900001', 'SAVINGS', 'SAV_PERSONAL',  8900.00,  8900.00, true, 'ACTIVE', NULL, now() - interval '9 days'),
 ('acc_T4SV', '0190000104', 'CIF00900001', 'SAVINGS', 'SAV_PERSONAL',  3200.00,  3200.00, true, 'ACTIVE', NULL, now() - interval '2 days'),
 ('acc_T5SV', '0190000105', 'CIF00900001', 'SAVINGS', 'SAV_PERSONAL', 15000.00, 15000.00, true, 'ACTIVE', NULL, now() - interval '4 days');

INSERT INTO sim_cards (id, customer_id, account_id, pan_token, masked_pan, product, status, expiry, pin_hash, pin_tries_left,
                       blocked_reason, blocked_at) VALUES
 ('crd_T001', 'CIF00900001', 'acc_T1SV', 'tok_4512xxxx9001', '4512 •••• •••• 9001', 'CLASSIC', 'ACTIVE', '2029-05',
  encode(sha256(convert_to('crd_T001:2468', 'UTF8')), 'hex'), 3, NULL, NULL),
 ('crd_T002', 'CIF00900001', 'acc_T2CU', 'tok_4512xxxx9002', '4512 •••• •••• 9002', 'GOLD', 'ACTIVE',
  to_char(now() + interval '1 month', 'YYYY-MM'),
  encode(sha256(convert_to('crd_T002:2468', 'UTF8')), 'hex'), 3, NULL, NULL),
 ('crd_T003', 'CIF00900001', 'acc_T3SV', 'tok_4512xxxx9003', '4512 •••• •••• 9003', 'CLASSIC', 'BLOCKED', '2028-09',
  encode(sha256(convert_to('crd_T003:2468', 'UTF8')), 'hex'), 3, 'FRAUD', now() - interval '2 days'),
 ('crd_T004', 'CIF00900001', 'acc_T4SV', 'tok_4512xxxx9004', '4512 •••• •••• 9004', 'CLASSIC', 'INACTIVE_PIN_REQUIRED', '2030-10',
  NULL, 3, NULL, NULL);

INSERT INTO sim_biometrics (customer_id, finger, template_sha256, quality, source) VALUES
 ('CIF00900001', 'RIGHT_THUMB', repeat('7', 64), 82, 'BRANCH'),
 ('CIF00900001', 'LEFT_THUMB',  repeat('8', 64), 80, 'BRANCH');
