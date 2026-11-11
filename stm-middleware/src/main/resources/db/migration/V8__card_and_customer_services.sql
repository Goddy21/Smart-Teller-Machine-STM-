-- V8: card services (status, PIN change and reset, temporary block), customers from before the STM (dormant accounts,
-- bank biometric enrolment, specimen signatures), change of phone number, and the test customers for each case.

-- ------------------------------------------------------------------ simulated core banking

-- Accounts: ACTIVE, DORMANT, FROZEN, CLOSED. An ACTIVE account with no customer activity for
-- stm.accounts.dormant-after-months is treated as DORMANT (the simulator derives it from last_activity_at).
ALTER TABLE sim_accounts
    ADD COLUMN last_activity_at TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    ADD COLUMN status_reason    VARCHAR(40)    NULL,
    ADD COLUMN reactivated_at   TIMESTAMPTZ(3) NULL;
UPDATE sim_accounts a SET last_activity_at = coalesce(
    (SELECT max(p.posted_at) FROM sim_postings p WHERE p.account_id = a.id), a.created_at);

ALTER TABLE sim_customers
    ADD COLUMN customer_since    DATE           NOT NULL DEFAULT CURRENT_DATE,
    ADD COLUMN mobile_changed_at TIMESTAMPTZ(3) NULL;
UPDATE sim_customers SET customer_since = DATE '2019-06-03' WHERE id = 'CIF00482913';
UPDATE sim_customers SET customer_since = DATE '2016-02-17' WHERE id = 'CIF00391144';

-- Every change of a customer's phone number, as core banking records it.
CREATE TABLE sim_mobile_changes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id VARCHAR(30)    NOT NULL REFERENCES sim_customers (id),
    old_mobile  VARCHAR(20)    NOT NULL,
    new_mobile  VARCHAR(20)    NOT NULL,
    channel     VARCHAR(20)    NOT NULL,          -- STM, BRANCH
    reference   VARCHAR(40)    NULL,
    changed_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- The bank's own fingerprint templates. Customers from before the STM have none until they enrol at a kiosk.
CREATE TABLE sim_biometrics (
    customer_id     VARCHAR(30)    NOT NULL REFERENCES sim_customers (id),
    finger          VARCHAR(20)    NOT NULL,      -- RIGHT_THUMB, LEFT_THUMB, RIGHT_INDEX, LEFT_INDEX
    template_sha256 CHAR(64)       NOT NULL,
    quality         INT            NOT NULL,
    source          VARCHAR(20)    NOT NULL,      -- STM_ONBOARDING, STM_ENROLMENT, BRANCH
    enrolled_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, finger)
);

-- Specimen signatures (the account mandate). One ACTIVE per customer; older ones are kept as SUPERSEDED.
CREATE TABLE sim_signatures (
    id          VARCHAR(40)    PRIMARY KEY,
    customer_id VARCHAR(30)    NOT NULL REFERENCES sim_customers (id),
    image       BYTEA          NOT NULL,
    mime_type   VARCHAR(40)    NOT NULL,
    sha256      CHAR(64)       NOT NULL,
    source      VARCHAR(20)    NOT NULL,          -- STM_ONBOARDING, STM_UPDATE, BRANCH
    status      VARCHAR(12)    NOT NULL DEFAULT 'ACTIVE',
    captured_at TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_sim_signatures_customer ON sim_signatures (customer_id, status);

-- Cards: TEMP_BLOCKED is a freeze the customer can lift; BLOCKED (LOST, STOLEN, DAMAGED, EXPIRED) is permanent.
ALTER TABLE sim_cards
    ADD COLUMN temp_blocked_at     TIMESTAMPTZ(3) NULL,
    ADD COLUMN temp_blocked_reason VARCHAR(20)    NULL,
    ADD COLUMN pin_changed_at      TIMESTAMPTZ(3) NULL;

-- ------------------------------------------------------------------ gateway

-- What a customer changed at the kiosk outside account opening and cash: PIN change or reset, card freeze,
-- account reactivation, phone number, biometric enrolment, signature. Shown on the session in the portal.
CREATE TABLE service_events (
    id          VARCHAR(40)    PRIMARY KEY,
    session_id  VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id BIGINT         NOT NULL REFERENCES terminals (id),
    customer_id VARCHAR(30)    NOT NULL,
    kind        VARCHAR(30)    NOT NULL,
    subject     VARCHAR(60)    NULL,              -- card, account or masked number the event is about
    detail      TEXT           NOT NULL DEFAULT '{}',
    reference   VARCHAR(30)    NULL,
    created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_service_events_session ON service_events (session_id, created_at);
CREATE INDEX ix_service_events_created ON service_events (created_at DESC);

-- A change of phone number waiting for the code sent to the new number.
CREATE TABLE mobile_changes (
    ref          VARCHAR(40)    PRIMARY KEY,
    session_id   VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    customer_id  VARCHAR(30)    NOT NULL,
    old_mobile   VARCHAR(20)    NOT NULL,
    new_mobile   VARCHAR(20)    NOT NULL,
    status       VARCHAR(12)    NOT NULL DEFAULT 'PENDING',   -- PENDING, CONFIRMED, CANCELLED
    created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ(3) NULL
);

-- How the customer was verified for a lookup: with the code to their phone, or (phone lost) face and thumbprint only.
ALTER TABLE customer_lookups ADD COLUMN verified_by VARCHAR(20) NULL;
ALTER TABLE customer_tokens ALTER COLUMN source TYPE VARCHAR(30);

-- ------------------------------------------------------------------ test customers

-- Achieng (since 2019): signature taken at the branch, no fingerprints with the bank yet.
INSERT INTO sim_signatures (id, customer_id, image, mime_type, sha256, source, captured_at)
SELECT 'sig_BRANCH_482913', 'CIF00482913', i.b, 'image/png', encode(sha256(i.b), 'hex'), 'BRANCH', TIMESTAMPTZ '2019-06-03 10:15:00+03'
FROM (SELECT decode('iVBORw0KGgoAAAANSUhEUgAAAUAAAAB4CAAAAACmpXQCAAAEGklEQVR42u3cC67jIAwFUBZx979VZjQatU3BYBs7Ic1Fmk/1ntrklI8xhFJZlkohAQEJSEACshCQgAQkIAsBCUhAArIQkIAEJCALAQlIQAKyEJCABCTg+QV/CwHX/Ai4CggCrvndQ5CAPwr4/y8C+ofgm1TBpwAe3y2wgyi7tuDYNvzVpwZ2sY8CRPfVxoC+y0Q8ILqA2B0Qy4AIBsT7A+4DCGcLDqyCx1EJiAyTyn7hcBZg/x8Cmkalj7Z7KaAKxhnNIQ2w7QqvAlTVLPgBg5OCh6/k+OJSQGg7Hi9gTQKsVwNCC+i5TKQATv9/OuD0071dTfgdym9zJeC8ZnmHgt0AZ/d5CiD8gMgFRF2NN8pKWJoA2KSdQsIpN+C8ty+rcb1wK8fRLgkQ2njK/JNswOZWmw9aAKz7ASID8Ctgg1h3TG1YrMeXACryNsUflg4TbE5AWACVaalVwJoPiPZWnNHIN0cAIIaAUNgPf60sBAVfA604TbIBquuIcq44eQvzcBkLCOGDpAHFGnF89xLW2wsBrNmAnVlbICAGgDUdcHoDK4BN05VSeRbASSpvHA3cARBdQHmAtiwToQuIJhU6/LA4QFVqtyxNLNGuFw4AB1EbIISsh0qO7kRZEeucDDi7IDlQl1rVK+AQbkf0+8hcQBzb9dfrvdXBtxAOCAlQNqoYA75/0s+23hiwt+ek+3kvhDESJrPRfqS51koXFxeKvdvFdO4l9GVS1IaZQK8zygdEFuDa5FEAVC4B9vv1NEDFJQYDzvM/cqjiXzTOB6xWQJwI6Ep3QZn6hzZSiQSs64CWL92VsRYBYV4UUO9pPQWwngc4yDkgBrDGA6q3dFTvOK5rw8K0uO1BTIAYDLzSZZboVQRzxsAOCDlzDWlupB1qxfB8a0DXZkmpZ0S17H+BtLLTaygwAPqXopMB++P8YWixrAh2FnYwS00qsjGpu0mSANEboi1rdu+Mxzg1uQMgFip1N1ASAC2Lns4NwEXXtYbu9jEFaspACd0pswPQujG0aNL2hus5AVBOSPQwzKu0sO3tLrpNsVsB6h5FMgJCjiVDAJG1Xy9qJ3kvMaliSAH86kRin6yo8G6cSXngs/1V09MZKsDoB1Djt0H7tiFL1pYbLapNDdBfz0aASoe1J+d0gMGnGJwAWE2ACAesnSXzOwImPhM5B0Q7UQx//j7xYAT9BacBImHHfNbzhCvzzqW6UXIq9g6A9dpTO1KPvgl+YnLPY09wQvPCLwPWcw7mIOAuJ5s8DhAEfMrxWFueXLQP4GvG4Jvul988IvADBJMX962BcJwGoJV5wPF3AAzV5N+f5/SBOplfGEIsgMEN6HGAeO5Z2zwKnoAEJCALAQlIQAKyEJCABCQgCwEJSEACshCQgAQkIAFJsFb+AObKsxxI8q37AAAAAElFTkSuQmCC', 'base64') AS b) i;
UPDATE sim_accounts SET last_activity_at = now() - interval '3 months' WHERE id = 'acc_0JS5';

-- Mary Njoki Kariuki (CIF00204417), a customer since 2014: no signature and no fingerprints on file,
-- an active savings account with two cards, a dormant savings account, a frozen current account,
-- and cards in every state. Card PINs are 2468.
INSERT INTO sim_registry (document_number, document_type, serial_number, surname, given_names, date_of_birth, gender,
                          place_of_birth, expiry, kra_pin, kra_status, scenario, notes) VALUES
 ('25567890', 'NATIONAL_ID', '230567890', 'KARIUKI', 'MARY NJOKI', '1979-09-05', 'F', 'Murang''a', NULL,
  'A025567890M', 'ACTIVE', 'EXISTING_CUSTOMER', 'Customer since 2014 CIF00204417 (dormant and frozen accounts, cards in every state)');
INSERT INTO sim_customers (id, id_number, full_name, first_name, mobile, kra_pin, address, customer_since, kyc_reviewed_at) VALUES
 ('CIF00204417', '25567890', 'Mary Njoki Kariuki', 'Mary', '+254733204417', 'A025567890M',
  '{"line1":"Kenyatta Highway","town":"Thika","county":"Kiambu"}', DATE '2014-08-21', now() - interval '14 months');
INSERT INTO sim_accounts (id, account_number, customer_id, type, product_code, ledger, available, card_eligible, status,
                          status_reason, last_activity_at) VALUES
 ('acc_M1SV', '0124417001', 'CIF00204417', 'SAVINGS', 'SAV_PERSONAL', 48200.00, 48200.00, true, 'ACTIVE', NULL, now() - interval '5 days'),
 ('acc_M2CU', '0124417002', 'CIF00204417', 'CURRENT', 'CUR_PERSONAL',  9750.00,  9750.00, true, 'ACTIVE', NULL, now() - interval '2 months'),
 ('acc_M3DM', '0124417003', 'CIF00204417', 'SAVINGS', 'SAV_PERSONAL', 15300.00, 15300.00, true, 'ACTIVE', NULL, now() - interval '20 months'),
 ('acc_M4FZ', '0124417004', 'CIF00204417', 'CURRENT', 'CUR_PERSONAL', 61000.00, 61000.00, true, 'FROZEN', 'COURT_ORDER', now() - interval '7 months');
INSERT INTO sim_cards (id, customer_id, account_id, pan_token, masked_pan, product, status, expiry, pin_hash, pin_tries_left) VALUES
 ('crd_8101', 'CIF00204417', 'acc_M1SV', 'tok_4512xxxx8101', '4512 •••• •••• 8101', 'CLASSIC', 'ACTIVE',       '2029-03',
  encode(sha256(convert_to('crd_8101:2468', 'UTF8')), 'hex'), 3),
 ('crd_8102', 'CIF00204417', 'acc_M1SV', 'tok_4512xxxx8102', '4512 •••• •••• 8102', 'GOLD',    'ACTIVE',       '2028-11',
  encode(sha256(convert_to('crd_8102:2468', 'UTF8')), 'hex'), 3),
 ('crd_8103', 'CIF00204417', 'acc_M2CU', 'tok_4512xxxx8103', '4512 •••• •••• 8103', 'CLASSIC', 'TEMP_BLOCKED', '2029-01',
  encode(sha256(convert_to('crd_8103:2468', 'UTF8')), 'hex'), 3),
 ('crd_8104', 'CIF00204417', 'acc_M2CU', 'tok_4512xxxx8104', '4512 •••• •••• 8104', 'CLASSIC', 'BLOCKED',      '2027-06',
  encode(sha256(convert_to('crd_8104:2468', 'UTF8')), 'hex'), 3),
 ('crd_8105', 'CIF00204417', 'acc_M1SV', 'tok_4512xxxx8105', '4512 •••• •••• 8105', 'CLASSIC', 'PIN_LOCKED',   '2028-08',
  encode(sha256(convert_to('crd_8105:2468', 'UTF8')), 'hex'), 0);
UPDATE sim_cards SET temp_blocked_at = now() - interval '2 days', temp_blocked_reason = 'MISPLACED' WHERE id = 'crd_8103';
UPDATE sim_cards SET blocked_reason = 'DAMAGED', blocked_at = now() - interval '10 days' WHERE id = 'crd_8104';
INSERT INTO sim_postings (core_ref, account_id, direction, amount, currency, narrative, balance_after, posted_at) VALUES
 ('FT26295MK001', 'acc_M1SV', 'CR', 45000.00, 'KES', 'Salary OCT',               45000.00, now() - interval '35 days'),
 ('FT26318MK002', 'acc_M1SV', 'CR',  3200.00, 'KES', 'M-PESA C2B 0733•••417',    48200.00, now() - interval '5 days'),
 ('FT26250MK003', 'acc_M2CU', 'CR',  9750.00, 'KES', 'Cheque deposit',            9750.00, now() - interval '2 months'),
 ('FT25070MK004', 'acc_M3DM', 'CR', 15300.00, 'KES', 'Transfer from M. KARIUKI', 15300.00, now() - interval '20 months'),
 ('FT26100MK005', 'acc_M4FZ', 'CR', 61000.00, 'KES', 'Business receipts',        61000.00, now() - interval '7 months');

-- The fingerprint template of a passed check, kept so account opening can enrol it with the bank at B-12.
ALTER TABLE biometric_checks ADD COLUMN finger_template BYTEA NULL;
ALTER TABLE biometric_checks ADD COLUMN matched_against VARCHAR(10) NULL;   -- IPRS or BANK
