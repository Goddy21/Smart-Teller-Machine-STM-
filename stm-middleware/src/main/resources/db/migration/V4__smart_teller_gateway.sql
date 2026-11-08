-- Smart Teller Gateway (Core Banking API Specification, Nov 2026).
-- Replaces the V2 kiosk journeys (passport account opening + debit card) with the spec's flows:
-- device/session, account opening, card services, card login & statements, cash deposit/withdrawal, forex,
-- receipts, electronic journal and cash reconciliation. Terminals, branches, telemetry, the audit journal and
-- portal accounts are kept. The audit journal keeps its hash chain; old entries stay readable.

-- ------------------------------------------------------------------ retire the V2 journey model
DROP TABLE IF EXISTS card_fulfilments;
DROP TABLE IF EXISTS kyc_documents;
DROP TABLE IF EXISTS kiosk_sessions;
DROP TABLE IF EXISTS account_products;
DROP TABLE IF EXISTS escrow_ledger;
DROP TABLE IF EXISTS transactions;

-- ------------------------------------------------------------------ reference data
INSERT INTO branches (code, name) VALUES ('012', 'Moi Avenue') ON CONFLICT (code) DO NOTHING;

CREATE TABLE products (
    code                VARCHAR(30)   PRIMARY KEY,
    category            VARCHAR(20)   NOT NULL,              -- ACCOUNT
    name                VARCHAR(80)   NOT NULL,
    name_sw             VARCHAR(80),
    currency            CHAR(3)       NOT NULL DEFAULT 'KES',
    account_type        VARCHAR(20)   NOT NULL,              -- SAVINGS, CURRENT (core account type it opens)
    min_opening_balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    features            TEXT          NOT NULL DEFAULT '[]', -- JSON array of strings
    features_sw         TEXT          NOT NULL DEFAULT '[]',
    card_products       TEXT          NOT NULL DEFAULT '[]', -- JSON array: CLASSIC, GOLD
    waive_card_fee      BOOLEAN       NOT NULL DEFAULT true, -- first card free when opened at the STM
    channel_stm         BOOLEAN       NOT NULL DEFAULT true,
    active              BOOLEAN       NOT NULL DEFAULT true,
    sort_order          INT           NOT NULL DEFAULT 0
);
INSERT INTO products (code, category, name, name_sw, account_type, features, features_sw, card_products, sort_order) VALUES
 ('SAV_PERSONAL', 'ACCOUNT', 'Personal Savings Account', 'Akaunti ya Akiba Binafsi', 'SAVINGS',
  '["No minimum opening balance","Free mobile and internet banking","Debit card issued today"]',
  '["Hakuna kiwango cha chini cha kufungua","Benki ya simu na mtandao bila malipo","Kadi ya benki inatolewa leo"]',
  '["CLASSIC","GOLD"]', 1),
 ('CUR_PERSONAL', 'ACCOUNT', 'Current Account', 'Akaunti ya Sasa', 'CURRENT',
  '["Cheque book on request","Overdraft eligible after 3 months","Debit card issued today"]',
  '["Kitabu cha hundi ukiomba","Unastahili overdraft baada ya miezi 3","Kadi ya benki inatolewa leo"]',
  '["CLASSIC","GOLD"]', 2);

CREATE TABLE tariffs (
    code            VARCHAR(30)   PRIMARY KEY,
    label           VARCHAR(80)   NOT NULL,
    fee             NUMERIC(19,2) NOT NULL,
    currency        CHAR(3)       NOT NULL DEFAULT 'KES',
    includes_excise BOOLEAN       NOT NULL DEFAULT false,
    version         VARCHAR(20)   NOT NULL
);
CREATE TABLE tariff_lines (
    tariff_code VARCHAR(30)   NOT NULL REFERENCES tariffs (code),
    seq         INT           NOT NULL,
    label       VARCHAR(80)   NOT NULL,
    amount      NUMERIC(19,2) NOT NULL,
    PRIMARY KEY (tariff_code, seq)
);
INSERT INTO tariffs (code, label, fee, includes_excise, version) VALUES
 ('CARD_ISSUE_CLASSIC', 'Classic debit card', 400.00, true,  '2026-11-01'),
 ('CARD_ISSUE_GOLD',    'Gold debit card',    600.00, true,  '2026-11-01'),
 ('STMT_MINI',          'Mini statement',      30.00, false, '2026-11-01'),
 ('STMT_FULL',          'Full A4 statement',  100.00, false, '2026-11-01'),
 ('CASH_WDL_STM',       'Cash withdrawal',      0.00, false, '2026-11-01'),
 ('CASH_DEP_STM',       'Cash deposit',         0.00, false, '2026-11-01');
INSERT INTO tariff_lines (tariff_code, seq, label, amount) VALUES
 ('CARD_ISSUE_CLASSIC', 1, 'Card issuance (Classic)', 300.00),
 ('CARD_ISSUE_CLASSIC', 2, 'Instant EMV personalisation', 100.00),
 ('CARD_ISSUE_GOLD',    1, 'Card issuance (Gold)', 450.00),
 ('CARD_ISSUE_GOLD',    2, 'Instant EMV personalisation', 150.00);

CREATE TABLE legal_terms (
    terms_id     VARCHAR(40)  NOT NULL,
    language     CHAR(2)      NOT NULL,
    version      VARCHAR(20)  NOT NULL,
    product_code VARCHAR(30),                 -- NULL = all account products
    sections     TEXT         NOT NULL,       -- JSON [{title, body}]
    consents     TEXT         NOT NULL,       -- JSON [{code, required, label}]
    audio_url    VARCHAR(300),
    active       BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (terms_id, language)
);
INSERT INTO legal_terms (terms_id, language, version, sections, consents, audio_url) VALUES
('TC-ACC-2026.11', 'en', '2026.11',
 '[{"title":"1. About these terms","body":"These terms govern the account you are opening today and the debit card issued with it. They form a contract between you and the bank once you tap “I Accept”. You can request a printed copy at the end of this session."},
   {"title":"2. Central Bank of Kenya disclosures","body":"The bank is licensed and regulated by the Central Bank of Kenya under the Banking Act (Cap. 488). All fees and charges are published in the tariff guide. We will give you at least 30 days’ notice before changing any fee that applies to your account."},
   {"title":"3. Deposit protection","body":"Deposits are protected by the Kenya Deposit Insurance Corporation up to the statutory limit per depositor, per institution."},
   {"title":"4. Your personal data","body":"Under the Data Protection Act, 2019 we collect your name, ID details, photograph, fingerprint template, signature, KRA PIN, mobile number and address to verify your identity and open your account. We confirm your identity with the Integrated Population Registration Services (IPRS). Biometric data is stored encrypted and used only for authentication."},
   {"title":"5. Sharing your information","body":"We share account performance data with licensed credit reference bureaus as required by regulation. We do not sell your data. You can ask to see, correct or delete data we hold about you, subject to legal retention periods."},
   {"title":"6. Anti-money laundering","body":"We are required by the Proceeds of Crime and Anti-Money Laundering Act to verify your identity, monitor transactions and report suspicious activity."},
   {"title":"7. Card and PIN security","body":"Never share your PIN or one-time codes, including with bank staff. Report a lost or stolen card immediately."},
   {"title":"8. Complaints","body":"You can raise a complaint at any branch or through the contact centre, and escalate to the Central Bank of Kenya if unresolved."}]',
 '[{"code":"ACCOUNT_TERMS","required":true,"label":"I agree to the account terms"},
   {"code":"DATA_PROCESSING_DPA2019","required":true,"label":"I agree to the processing of my personal data"},
   {"code":"BIOMETRIC_PROCESSING","required":true,"label":"I agree to the processing of my biometric data"},
   {"code":"MARKETING","required":false,"label":"Send me offers and product news"}]',
 'https://cdn.example-bank.co.ke/terms/TC-ACC-2026.11-en.mp3'),
('TC-ACC-2026.11', 'sw', '2026.11',
 '[{"title":"1. Kuhusu masharti haya","body":"Masharti haya yanasimamia akaunti unayofungua leo na kadi ya benki inayotolewa pamoja nayo. Yanakuwa mkataba kati yako na benki mara unapogusa “Ninakubali”. Unaweza kuomba nakala iliyochapishwa mwishoni mwa kipindi hiki."},
   {"title":"2. Taarifa za Benki Kuu ya Kenya","body":"Benki ina leseni na inasimamiwa na Benki Kuu ya Kenya chini ya Sheria ya Benki (Sura ya 488). Ada na gharama zote zimechapishwa katika mwongozo wa ada. Tutakupa notisi ya angalau siku 30 kabla ya kubadilisha ada yoyote inayohusu akaunti yako."},
   {"title":"3. Ulinzi wa amana","body":"Amana zinalindwa na Shirika la Bima ya Amana la Kenya hadi kikomo cha kisheria kwa kila mwekaji, kwa kila taasisi."},
   {"title":"4. Data yako binafsi","body":"Chini ya Sheria ya Ulinzi wa Data, 2019 tunakusanya jina lako, maelezo ya kitambulisho, picha, alama ya kidole, sahihi, PIN ya KRA, nambari ya simu na anwani ili kuthibitisha utambulisho wako na kufungua akaunti yako. Tunathibitisha utambulisho wako kupitia IPRS. Data ya kibayometriki huhifadhiwa kwa usimbaji fiche na hutumika kwa uthibitishaji pekee."},
   {"title":"5. Kushiriki taarifa zako","body":"Tunashiriki taarifa za utendaji wa akaunti na mashirika ya marejeleo ya mikopo yenye leseni kama inavyotakiwa na kanuni. Hatuuzi data yako. Unaweza kuomba kuona, kusahihisha au kufuta data tunayoshikilia kukuhusu, kwa kuzingatia vipindi vya kisheria vya uhifadhi."},
   {"title":"6. Kupambana na utakatishaji fedha","body":"Sheria ya Mapato ya Uhalifu na Kupambana na Utakatishaji Fedha inatutaka kuthibitisha utambulisho wako, kufuatilia miamala na kuripoti shughuli zinazotiliwa shaka."},
   {"title":"7. Usalama wa kadi na PIN","body":"Usimwambie mtu yeyote PIN yako au nambari za siri za mara moja, wakiwemo wafanyakazi wa benki. Ripoti kadi iliyopotea au kuibiwa mara moja."},
   {"title":"8. Malalamiko","body":"Unaweza kuwasilisha malalamiko katika tawi lolote au kupitia kituo cha mawasiliano, na kuyapeleka Benki Kuu ya Kenya ikiwa hayatatatuliwa."}]',
 '[{"code":"ACCOUNT_TERMS","required":true,"label":"Ninakubali masharti ya akaunti"},
   {"code":"DATA_PROCESSING_DPA2019","required":true,"label":"Ninakubali data yangu binafsi ichakatwe"},
   {"code":"BIOMETRIC_PROCESSING","required":true,"label":"Ninakubali data yangu ya kibayometriki ichakatwe"},
   {"code":"MARKETING","required":false,"label":"Nitumie ofa na habari za bidhaa"}]',
 'https://cdn.example-bank.co.ke/terms/TC-ACC-2026.11-sw.mp3');

CREATE TABLE fx_rates (
    currency   CHAR(3)       PRIMARY KEY,
    symbol     VARCHAR(4)    NOT NULL,
    buy        NUMERIC(12,4) NOT NULL,     -- bank buys foreign notes from the customer
    sell       NUMERIC(12,4) NOT NULL,     -- bank sells foreign notes to the customer
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
INSERT INTO fx_rates (currency, symbol, buy, sell) VALUES
 ('USD', '$', 130.50, 131.20), ('EUR', '€', 150.20, 151.10), ('GBP', '£', 172.40, 173.50);

-- Running reference numbers: STM-DEP-90417, STM-WDL-73288 ...
CREATE SEQUENCE stm_ref_seq START WITH 10001;

-- ------------------------------------------------------------------ devices
ALTER TABLE terminals ADD COLUMN software_version VARCHAR(40);

CREATE TABLE device_tokens (
    token_hash  CHAR(64)       PRIMARY KEY,
    terminal_id BIGINT         NOT NULL REFERENCES terminals (id),
    issued_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX ix_device_tokens_terminal ON device_tokens (terminal_id, expires_at);

ALTER TABLE terminal_telemetry ADD COLUMN modules TEXT;            -- D-6 module states (JSON)
ALTER TABLE terminal_telemetry ADD COLUMN disabled_services TEXT;  -- JSON array
ALTER TABLE terminal_telemetry ADD COLUMN reported_at TIMESTAMPTZ(3);

-- Notes the gateway believes are in each cassette (seeded on first use, corrected by A-2 cash positions).
CREATE TABLE cassette_inventory (
    terminal_id  BIGINT        NOT NULL REFERENCES terminals (id),
    currency     CHAR(3)       NOT NULL,
    denomination NUMERIC(10,0) NOT NULL,
    note_count   INT           NOT NULL CHECK (note_count >= 0),
    updated_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    PRIMARY KEY (terminal_id, currency, denomination)
);

CREATE TABLE device_journal (
    terminal_id     BIGINT         NOT NULL REFERENCES terminals (id),
    seq             BIGINT         NOT NULL,
    at              TIMESTAMPTZ(3) NOT NULL,
    event           VARCHAR(40)    NOT NULL,
    ref             VARCHAR(60),
    payload         TEXT,
    signature_valid BOOLEAN        NOT NULL DEFAULT false,
    received_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    PRIMARY KEY (terminal_id, seq)
);
CREATE INDEX ix_device_journal_ref ON device_journal (ref);

CREATE TABLE cash_positions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    terminal_id     BIGINT         NOT NULL REFERENCES terminals (id),
    event           VARCHAR(30)    NOT NULL,
    cassettes       TEXT           NOT NULL,
    retract_bin     INT            NOT NULL DEFAULT 0,
    reject_bin      INT            NOT NULL DEFAULT 0,
    counterfeit_bin INT            NOT NULL DEFAULT 0,
    reconciled      BOOLEAN        NOT NULL,
    variances       TEXT           NOT NULL,
    case_id         VARCHAR(30),
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_cash_positions_terminal ON cash_positions (terminal_id, created_at DESC);

-- ------------------------------------------------------------------ sessions and security
CREATE TABLE stm_sessions (
    id               VARCHAR(40)    PRIMARY KEY,
    terminal_id      BIGINT         NOT NULL REFERENCES terminals (id),
    start_trigger    VARCHAR(20)    NOT NULL,          -- TOUCH, CARD
    language         CHAR(2)        NOT NULL DEFAULT 'en',
    status           VARCHAR(12)    NOT NULL DEFAULT 'ACTIVE',
    flow             VARCHAR(30),                      -- the last flow used: ACCOUNT_OPENING, CARD_SERVICES ...
    customer_id      VARCHAR(30),
    customer_name    VARCHAR(120),
    started_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ(3) NOT NULL,
    ended_at         TIMESTAMPTZ(3),
    end_reason       VARCHAR(40),
    card_retained    BOOLEAN        NOT NULL DEFAULT false,
    CONSTRAINT ck_stm_sessions_status CHECK (status IN ('ACTIVE', 'ENDED', 'EXPIRED'))
);
CREATE INDEX ix_stm_sessions_terminal ON stm_sessions (terminal_id, status);
CREATE INDEX ix_stm_sessions_started ON stm_sessions (started_at DESC);

CREATE TABLE customer_tokens (
    token_hash  CHAR(64)       PRIMARY KEY,
    session_id  VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    customer_id VARCHAR(30)    NOT NULL,
    source      VARCHAR(20)    NOT NULL,                  -- CARD_PIN, VERIFIED_IDENTITY, ONBOARDING
    created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ(3) NOT NULL,
    revoked     BOOLEAN        NOT NULL DEFAULT false
);
CREATE INDEX ix_customer_tokens_session ON customer_tokens (session_id);

-- Idempotency-Key store: a retry returns the first answer, never a second posting.
CREATE TABLE idempotency_records (
    terminal_id   BIGINT         NOT NULL REFERENCES terminals (id),
    idem_key      VARCHAR(100)   NOT NULL,
    operation     VARCHAR(60)    NOT NULL,
    request_hash  CHAR(64)       NOT NULL,
    status_code   INT,
    response_body TEXT,
    created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    PRIMARY KEY (terminal_id, idem_key, operation)
);

CREATE TABLE otps (
    ref           VARCHAR(40)    PRIMARY KEY,
    session_id    VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    purpose       VARCHAR(30)    NOT NULL,     -- ONBOARDING_PHONE, CARD_SERVICES
    subject_id    VARCHAR(40)    NOT NULL,     -- applicationId or lookupRef
    mobile        VARCHAR(20)    NOT NULL,
    code_hash     CHAR(64)       NOT NULL,
    attempts_left INT            NOT NULL DEFAULT 3,
    sends         INT            NOT NULL DEFAULT 1,
    last_sent_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ(3) NOT NULL,
    verified_at   TIMESTAMPTZ(3),
    created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_otps_subject ON otps (subject_id);

-- ------------------------------------------------------------------ identity and account opening
CREATE TABLE identity_verifications (
    id                   VARCHAR(40)    PRIMARY KEY,
    session_id           VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    application_id       VARCHAR(40),
    purpose              VARCHAR(20)    NOT NULL,      -- ONBOARDING, FOREX, LOOKUP
    document_type        VARCHAR(20)    NOT NULL,      -- NATIONAL_ID, PASSPORT
    country              CHAR(3)        NOT NULL,
    document_number      VARCHAR(30)    NOT NULL,
    serial_number        VARCHAR(30),
    ocr                  TEXT,
    mrz                  TEXT,
    result               VARCHAR(12)    NOT NULL,      -- MATCH, MISMATCH, NOT_FOUND
    document_checks      TEXT           NOT NULL,
    registry             TEXT,                         -- registry record without the photo
    registry_source      VARCHAR(20),
    existing_customer    BOOLEAN        NOT NULL DEFAULT false,
    existing_customer_id VARCHAR(30),
    created_at           TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_idv_session ON identity_verifications (session_id);

CREATE TABLE applications (
    id                VARCHAR(40)    PRIMARY KEY,
    session_id        VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id       BIGINT         NOT NULL REFERENCES terminals (id),
    product_code      VARCHAR(30)    NOT NULL REFERENCES products (code),
    terms_id          VARCHAR(40)    NOT NULL,
    consents          TEXT           NOT NULL,
    accepted_at       TIMESTAMPTZ(3) NOT NULL,
    status            VARCHAR(24)    NOT NULL,
    verification_id   VARCHAR(40),
    identity_attempts INT            NOT NULL DEFAULT 0,
    biometrics_passed BOOLEAN        NOT NULL DEFAULT false,
    biometric_attempts INT           NOT NULL DEFAULT 0,
    identity_confirmed BOOLEAN       NOT NULL DEFAULT false,
    full_name         VARCHAR(120),
    id_number         VARCHAR(30),
    kra_pin           VARCHAR(20),
    mobile            VARCHAR(20),
    address           TEXT,
    occupation        VARCHAR(80),
    source_of_funds   VARCHAR(80),
    screening         TEXT,
    otp_verified      BOOLEAN        NOT NULL DEFAULT false,
    signature_id      VARCHAR(40),
    customer_id       VARCHAR(30),
    account_id        VARCHAR(30),
    account_number    VARCHAR(30),
    card_product      VARCHAR(10),
    name_on_card      VARCHAR(30),
    reference         VARCHAR(30),
    referral_status   VARCHAR(12)    NOT NULL DEFAULT 'NONE',   -- NONE, PENDING, CLEARED, REJECTED
    teller_override   VARCHAR(20),                              -- IDENTITY verified by a teller on video
    decided_by        VARCHAR(50),
    decided_at        TIMESTAMPTZ(3),
    decision_notes    VARCHAR(500),
    cancel_reason     VARCHAR(40),
    cancelled_at_step VARCHAR(30),
    created_at        TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX ix_applications_status ON applications (status, updated_at DESC);
CREATE INDEX ix_applications_session ON applications (session_id);

CREATE TABLE biometric_checks (
    id              VARCHAR(40)    PRIMARY KEY,
    session_id      VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    application_id  VARCHAR(40),
    customer_id     VARCHAR(30),
    verification_id VARCHAR(40),
    face_result     VARCHAR(12),
    face_score      NUMERIC(5,3),
    liveness_score  NUMERIC(5,3),
    finger          VARCHAR(20),
    finger_result   VARCHAR(12),
    finger_score    NUMERIC(5,3),
    finger_quality  INT,
    overall         VARCHAR(8)     NOT NULL,
    attempts_left   INT            NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_biometric_subject ON biometric_checks (application_id, customer_id);

-- Images (ID front/back/UV, face, registry photo, signature). Viewing one in the portal is audited.
CREATE TABLE captures (
    id         UUID           PRIMARY KEY,
    owner_type VARCHAR(20)    NOT NULL,      -- VERIFICATION, BIOMETRIC, APPLICATION, LOOKUP
    owner_id   VARCHAR(40)    NOT NULL,
    kind       VARCHAR(20)    NOT NULL,      -- FRONT, BACK, UV, FACE, REGISTRY_PHOTO, SIGNATURE
    mime_type  VARCHAR(40)    NOT NULL,
    size_bytes INT            NOT NULL,
    sha256     CHAR(64)       NOT NULL,
    content    BYTEA          NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_captures_owner ON captures (owner_id);

-- ------------------------------------------------------------------ card services
CREATE TABLE customer_lookups (
    ref             VARCHAR(40)    PRIMARY KEY,
    session_id      VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    customer_id     VARCHAR(30)    NOT NULL,
    lookup_by       VARCHAR(20)    NOT NULL,
    otp_verified    BOOLEAN        NOT NULL DEFAULT false,
    finger_verified BOOLEAN        NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE fee_quotes (
    id                VARCHAR(40)    PRIMARY KEY,
    session_id        VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    customer_id       VARCHAR(30)    NOT NULL,
    service           VARCHAR(20)    NOT NULL,
    card_product      VARCHAR(10),
    debit_account_id  VARCHAR(30)    NOT NULL,
    lines             TEXT           NOT NULL,
    total             NUMERIC(19,2)  NOT NULL,
    currency          CHAR(3)        NOT NULL,
    includes_excise   BOOLEAN        NOT NULL,
    sufficient_funds  BOOLEAN        NOT NULL,
    expires_at        TIMESTAMPTZ(3) NOT NULL,
    used_at           TIMESTAMPTZ(3),
    created_at        TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE card_orders (
    id               VARCHAR(40)    PRIMARY KEY,
    session_id       VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id      BIGINT         NOT NULL REFERENCES terminals (id),
    customer_id      VARCHAR(30)    NOT NULL,
    application_id   VARCHAR(40),
    service          VARCHAR(20)    NOT NULL,     -- LINK_AND_ISSUE, REPLACEMENT, NEW_CARD_SETUP
    account_id       VARCHAR(30)    NOT NULL,
    card_product     VARCHAR(10)    NOT NULL,
    name_on_card     VARCHAR(30)    NOT NULL,
    fee_quote_id     VARCHAR(40),
    fee_amount       NUMERIC(19,2)  NOT NULL DEFAULT 0,
    fee_posted       BOOLEAN        NOT NULL DEFAULT false,
    fee_ref          VARCHAR(30),
    replaces_card_id VARCHAR(30),
    card_id          VARCHAR(30),
    masked_pan       VARCHAR(25),
    job_id           VARCHAR(40),
    status           VARCHAR(20)    NOT NULL,     -- PERSONALISING, READY, DISPENSED, RETAINED, SPOILED
    steps            TEXT           NOT NULL,
    outcome          VARCHAR(10),
    card_serial      VARCHAR(30),
    quality_check    VARCHAR(8),
    reference        VARCHAR(30),
    created_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_card_orders_status ON card_orders (status, created_at DESC);

CREATE TABLE card_auths (
    ref           VARCHAR(40)    PRIMARY KEY,
    session_id    VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    card_id       VARCHAR(30)    NOT NULL,
    masked_pan    VARCHAR(25)    NOT NULL,
    customer_id   VARCHAR(30)    NOT NULL,
    entry_mode    VARCHAR(20)    NOT NULL,
    verified      BOOLEAN        NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE statements (
    id            VARCHAR(40)    PRIMARY KEY,
    session_id    VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    customer_id   VARCHAR(30)    NOT NULL,
    account_id    VARCHAR(30)    NOT NULL,
    type          VARCHAR(8)     NOT NULL,       -- MINI, FULL
    period_months INT,
    fee           NUMERIC(19,2)  NOT NULL,
    currency      CHAR(3)        NOT NULL,
    fee_ref       VARCHAR(30),
    balance_after NUMERIC(19,2),
    pages         INT,
    lines         TEXT           NOT NULL,
    reference     VARCHAR(30)    NOT NULL,
    created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ cash and forex
CREATE TABLE deposit_targets (
    ref               VARCHAR(40)    PRIMARY KEY,
    session_id        VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    type              VARCHAR(10)    NOT NULL,     -- ACCOUNT, PAYBILL
    account_id        VARCHAR(30),
    paybill           VARCHAR(20),
    account_reference VARCHAR(40),
    account_name      VARCHAR(120)   NOT NULL,
    masked            VARCHAR(60)    NOT NULL,
    created_at        TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE cash_deposits (
    id                    VARCHAR(40)    PRIMARY KEY,
    session_id            VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id           BIGINT         NOT NULL REFERENCES terminals (id),
    target_ref            VARCHAR(40)    NOT NULL,
    target_label          VARCHAR(160)   NOT NULL,
    currency              CHAR(3)        NOT NULL,
    depositor_mobile      VARCHAR(20),
    status                VARCHAR(16)    NOT NULL,   -- AWAITING_CASH, COUNTED, CREDITED, CANCELLED, EXPIRED
    accepted              TEXT,
    rejected_count        INT            NOT NULL DEFAULT 0,
    rejected_reasons      TEXT,
    suspected_counterfeit INT            NOT NULL DEFAULT 0,
    total                 NUMERIC(19,2),
    fee                   NUMERIC(19,2)  NOT NULL DEFAULT 0,
    transaction_ref       VARCHAR(30),
    core_ref              VARCHAR(30),
    previous_balance      NUMERIC(19,2),
    new_balance           NUMERIC(19,2),
    posted_at             TIMESTAMPTZ(3),
    cancel_reason         VARCHAR(40),
    expires_at            TIMESTAMPTZ(3) NOT NULL,
    created_at            TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_cash_deposits_status ON cash_deposits (status, created_at DESC);

CREATE TABLE fx_quotes (
    id               VARCHAR(40)    PRIMARY KEY,
    session_id       VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    direction        VARCHAR(10)    NOT NULL,      -- BANK_BUYS, BANK_SELLS
    currency         CHAR(3)        NOT NULL,
    rate             NUMERIC(12,4)  NOT NULL,
    foreign_amount   NUMERIC(19,2),
    local_amount     NUMERIC(19,2),
    debit_account_id VARCHAR(30),
    verification_id  VARCHAR(40)    NOT NULL,
    expires_at       TIMESTAMPTZ(3) NOT NULL,
    used_at          TIMESTAMPTZ(3),
    created_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE cash_withdrawals (
    id              VARCHAR(40)    PRIMARY KEY,
    session_id      VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id     BIGINT         NOT NULL REFERENCES terminals (id),
    customer_id     VARCHAR(30),
    account_id      VARCHAR(30),
    source          VARCHAR(12)    NOT NULL,       -- ACCOUNT, FX_SELL, FX_KES_OUT
    currency        CHAR(3)        NOT NULL,
    amount          NUMERIC(19,2)  NOT NULL,
    local_amount    NUMERIC(19,2)  NOT NULL,
    fx_quote_id     VARCHAR(40),
    exchange_id     VARCHAR(40),
    mix             TEXT           NOT NULL,
    status          VARCHAR(24)    NOT NULL,       -- AUTHORISED, COMPLETED, REVERSED, PENDING_RECONCILIATION
    transaction_ref VARCHAR(30)    NOT NULL,
    core_ref        VARCHAR(30),
    balance_after   NUMERIC(19,2),
    presented       TEXT,
    retracted_count INT,
    reversal_ref    VARCHAR(30),
    reverse_reason  VARCHAR(30),
    credit_back_by  TIMESTAMPTZ(3),
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_cash_withdrawals_status ON cash_withdrawals (status, created_at DESC);

CREATE TABLE fx_exchanges (
    id               VARCHAR(40)    PRIMARY KEY,
    session_id       VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id      BIGINT         NOT NULL REFERENCES terminals (id),
    quote_id         VARCHAR(40)    NOT NULL,
    currency         CHAR(3)        NOT NULL,
    rate             NUMERIC(12,4)  NOT NULL,
    destination_type VARCHAR(10)    NOT NULL,      -- ACCOUNT, CASH
    account_id       VARCHAR(30),
    status           VARCHAR(16)    NOT NULL,      -- AWAITING_CASH, COUNTED, CREDITED, AUTHORISED, CANCELLED
    accepted         TEXT,
    serials          TEXT,
    rejected_count   INT            NOT NULL DEFAULT 0,
    foreign_total    NUMERIC(19,2),
    local_amount     NUMERIC(19,2),
    transaction_ref  VARCHAR(30),
    core_ref         VARCHAR(30),
    new_balance      NUMERIC(19,2),
    withdrawal_id    VARCHAR(40),
    created_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ receipts, SMS, video
CREATE TABLE receipts (
    id              VARCHAR(40)    PRIMARY KEY,
    session_id      VARCHAR(40)    REFERENCES stm_sessions (id),
    transaction_ref VARCHAR(30)    NOT NULL,
    delivery        VARCHAR(8)     NOT NULL,
    language        CHAR(2)        NOT NULL,
    lines           TEXT           NOT NULL,
    qr              VARCHAR(200),
    print_status    VARCHAR(12)    NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE sms_messages (
    id         VARCHAR(40)    PRIMARY KEY,
    session_id VARCHAR(40),
    template   VARCHAR(40)    NOT NULL,
    mobile     VARCHAR(20)    NOT NULL,
    language   CHAR(2)        NOT NULL,
    body       TEXT           NOT NULL,
    status     VARCHAR(12)    NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_sms_created ON sms_messages (created_at DESC);

CREATE TABLE teller_calls (
    id             VARCHAR(60)    PRIMARY KEY,     -- call-control's call id
    session_id     VARCHAR(40)    NOT NULL REFERENCES stm_sessions (id),
    terminal_id    BIGINT         NOT NULL REFERENCES terminals (id),
    screen         VARCHAR(10),
    flow           VARCHAR(30),
    application_id VARCHAR(40),
    language       CHAR(2)        NOT NULL,
    status         VARCHAR(12)    NOT NULL,
    created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_teller_calls_session ON teller_calls (session_id);

-- ------------------------------------------------------------------ SIMULATED back ends (demo profile)
-- Stand-ins for core banking, IPRS / immigration, KRA iTax, the card system and screening, so every flow runs
-- end to end before the real systems are connected. The prod profile does not use these tables.
CREATE TABLE sim_registry (
    document_number VARCHAR(30)  PRIMARY KEY,   -- national ID number or passport number
    document_type   VARCHAR(20)  NOT NULL,      -- NATIONAL_ID, PASSPORT
    country         CHAR(3)      NOT NULL DEFAULT 'KEN',
    serial_number   VARCHAR(30),
    surname         VARCHAR(60)  NOT NULL,
    given_names     VARCHAR(80)  NOT NULL,
    date_of_birth   DATE         NOT NULL,
    gender          CHAR(1)      NOT NULL,
    place_of_birth  VARCHAR(60),
    nationality     CHAR(3)      NOT NULL DEFAULT 'KEN',
    expiry          DATE,
    kra_pin         VARCHAR(20),
    kra_status      VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    scenario        VARCHAR(30)  NOT NULL DEFAULT 'HAPPY_PATH',
    notes           VARCHAR(200)
);
INSERT INTO sim_registry (document_number, document_type, serial_number, surname, given_names, date_of_birth, gender,
                          place_of_birth, expiry, kra_pin, kra_status, scenario, notes) VALUES
 ('32145678', 'NATIONAL_ID', '240015678', 'OTIENO', 'ACHIENG WANJIRU', '1994-03-14', 'F', 'Kisumu', NULL,
  'A012345678Z', 'ACTIVE', 'EXISTING_CUSTOMER', 'Existing customer CIF00482913 (card services, statements, cash)'),
 ('29876543', 'NATIONAL_ID', '240098765', 'KAMAU', 'AMARA NJERI', '1996-07-22', 'F', 'Nyeri', NULL,
  'A029876543K', 'ACTIVE', 'HAPPY_PATH', 'New customer: account opening succeeds'),
 ('30111222', 'NATIONAL_ID', '240111222', 'MWANGI', 'JUMA HASSAN', '1990-01-09', 'M', 'Mombasa', NULL,
  'A030111222M', 'ACTIVE', 'BIOMETRIC_NO_MATCH', 'Face never matches: retry, then teller help'),
 ('30222333', 'NATIONAL_ID', '240222333', 'NDEGWA', 'WANJIKU MARY', '1985-11-30', 'F', 'Nakuru', NULL,
  'A030222333N', 'ACTIVE', 'SCREENING_REFERRED', 'PEP hit at KYC: referred to a teller'),
 ('30333444', 'NATIONAL_ID', '240333444', 'OUMA', 'OTIENO PETER', '1992-05-17', 'M', 'Kisii', NULL,
  'A030333444P', 'INACTIVE', 'KRA_PIN_INVALID', 'KRA PIN is inactive'),
 ('30444555', 'NATIONAL_ID', '240444555', 'ODHIAMBO', 'FAITH ATIENO', '1988-08-02', 'F', 'Homa Bay', NULL,
  'A030444555F', 'ACTIVE', 'DOCUMENT_TAMPERED', 'Document tamper check fails'),
 ('27654321', 'NATIONAL_ID', '240654321', 'MUTAI', 'BRIAN KIPROTICH', '1983-02-11', 'M', 'Eldoret', NULL,
  'A027654321B', 'ACTIVE', 'EXISTING_CUSTOMER', 'Existing customer CIF00391144 (blocked and expired cards)');
INSERT INTO sim_registry (document_number, document_type, country, surname, given_names, date_of_birth, gender,
                          place_of_birth, nationality, expiry, scenario, notes) VALUES
 ('P12345678', 'PASSPORT', 'GBR', 'SMITH', 'JOHN ROBERT', '1979-04-03', 'M', 'Leeds', 'GBR', '2031-09-30',
  'VISITOR', 'Visitor passport for forex'),
 ('AK0098765', 'PASSPORT', 'KEN', 'KAMAU', 'AMARA NJERI', '1996-07-22', 'F', 'Nyeri', 'KEN', '2033-01-15',
  'HAPPY_PATH', 'Kenyan passport of the happy-path customer');

CREATE TABLE sim_customers (
    id         VARCHAR(30)    PRIMARY KEY,      -- CIF
    id_number  VARCHAR(30)    NOT NULL UNIQUE,
    full_name  VARCHAR(120)   NOT NULL,
    first_name VARCHAR(60)    NOT NULL,
    mobile     VARCHAR(20)    NOT NULL,
    kra_pin    VARCHAR(20),
    address    TEXT,
    branch     VARCHAR(20)    NOT NULL DEFAULT '012',
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE SEQUENCE sim_cif_seq START WITH 482914;
CREATE SEQUENCE sim_account_seq START WITH 1000;

CREATE TABLE sim_accounts (
    id             VARCHAR(30)    PRIMARY KEY,
    account_number VARCHAR(20)    NOT NULL UNIQUE,
    customer_id    VARCHAR(30)    NOT NULL REFERENCES sim_customers (id),
    type           VARCHAR(20)    NOT NULL,       -- SAVINGS, CURRENT, JUNIOR_SAVER
    product_code   VARCHAR(30),
    currency       CHAR(3)        NOT NULL DEFAULT 'KES',
    ledger         NUMERIC(19,2)  NOT NULL DEFAULT 0,
    available      NUMERIC(19,2)  NOT NULL DEFAULT 0,
    branch         VARCHAR(20)    NOT NULL DEFAULT '012',
    status         VARCHAR(12)    NOT NULL DEFAULT 'ACTIVE',
    card_eligible  BOOLEAN        NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE sim_cards (
    id              VARCHAR(30)    PRIMARY KEY,
    customer_id     VARCHAR(30)    NOT NULL REFERENCES sim_customers (id),
    account_id      VARCHAR(30)    NOT NULL REFERENCES sim_accounts (id),
    pan_token       VARCHAR(40)    NOT NULL UNIQUE,
    masked_pan      VARCHAR(25)    NOT NULL,
    product         VARCHAR(10)    NOT NULL,
    status          VARCHAR(24)    NOT NULL,      -- INACTIVE_PIN_REQUIRED, ACTIVE, BLOCKED, EXPIRED, DESTROYED
    expiry          VARCHAR(7)     NOT NULL,      -- YYYY-MM
    pin_hash        CHAR(64),
    pin_tries_left  INT            NOT NULL DEFAULT 3,
    blocked_reason  VARCHAR(20),
    blocked_at      TIMESTAMPTZ(3),
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE sim_postings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    core_ref      VARCHAR(30)    NOT NULL,
    account_id    VARCHAR(40)    NOT NULL,       -- customer account id or a GL code (GL-CASH-KES-STM-01 ...)
    direction     CHAR(2)        NOT NULL,       -- DR, CR
    amount        NUMERIC(19,2)  NOT NULL,
    currency      CHAR(3)        NOT NULL,
    narrative     VARCHAR(120)   NOT NULL,
    balance_after NUMERIC(19,2),
    reversal_of   VARCHAR(30),
    posted_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_sim_postings_account ON sim_postings (account_id, posted_at DESC);
CREATE INDEX ix_sim_postings_ref ON sim_postings (core_ref);

-- Seed: Achieng (CIF00482913) and Brian (CIF00391144). Card PINs are 2580 (Achieng) and 1357 (Brian):
-- pin_hash = sha256('<cardId>:<PIN>'), computed by the simulated HSM.
INSERT INTO sim_customers (id, id_number, full_name, first_name, mobile, kra_pin, address) VALUES
 ('CIF00482913', '32145678', 'Achieng Wanjiru Otieno', 'Achieng', '+254712345678', 'A012345678Z',
  '{"line1":"Moi Avenue","town":"Nairobi","county":"Nairobi"}'),
 ('CIF00391144', '27654321', 'Brian Kiprotich Mutai', 'Brian', '+254722987654', 'A027654321B',
  '{"line1":"Uganda Road","town":"Eldoret","county":"Uasin Gishu"}');
INSERT INTO sim_accounts (id, account_number, customer_id, type, product_code, ledger, available, card_eligible) VALUES
 ('acc_2LM8', '0123454521', 'CIF00482913', 'SAVINGS',      'SAV_PERSONAL', 35350.00, 35350.00, true),
 ('acc_6TR3', '0123457780', 'CIF00482913', 'CURRENT',      'CUR_PERSONAL', 12840.50, 12840.50, true),
 ('acc_0JS5', '0123451093', 'CIF00482913', 'JUNIOR_SAVER', NULL,            2200.00,  2200.00, false),
 ('acc_7BX2', '0119876543', 'CIF00391144', 'SAVINGS',      'SAV_PERSONAL', 120000.00, 120000.00, true);
INSERT INTO sim_cards (id, customer_id, account_id, pan_token, masked_pan, product, status, expiry, pin_hash) VALUES
 ('crd_4521', 'CIF00482913', 'acc_2LM8', 'tok_4512xxxx4521', '4512 •••• •••• 4521', 'CLASSIC', 'ACTIVE', '2028-05',
  encode(sha256(convert_to('crd_4521:2580', 'UTF8')), 'hex')),
 ('crd_6611', 'CIF00391144', 'acc_7BX2', 'tok_4512xxxx6611', '4512 •••• •••• 6611', 'CLASSIC', 'BLOCKED',  '2028-01',
  encode(sha256(convert_to('crd_6611:1357', 'UTF8')), 'hex')),
 ('crd_7002', 'CIF00391144', 'acc_7BX2', 'tok_4512xxxx7002', '4512 •••• •••• 7002', 'GOLD',    'EXPIRED',  '2025-10',
  encode(sha256(convert_to('crd_7002:1357', 'UTF8')), 'hex'));
UPDATE sim_cards SET blocked_reason = 'STOLEN', blocked_at = now() WHERE id = 'crd_6611';

-- A little history so mini statements have lines.
INSERT INTO sim_postings (core_ref, account_id, direction, amount, currency, narrative, balance_after, posted_at) VALUES
 ('FT26300AA001', 'acc_2LM8', 'CR', 30000.00, 'KES', 'Salary NOV',                    30000.00, now() - interval '20 days'),
 ('FT26302AA002', 'acc_2LM8', 'DR',  1500.00, 'KES', 'Card POS NAIVAS MOI AVE',        28500.00, now() - interval '18 days'),
 ('FT26304AA003', 'acc_2LM8', 'DR',  2000.00, 'KES', 'ATM withdrawal KENCOM',          26500.00, now() - interval '15 days'),
 ('FT26305AA004', 'acc_2LM8', 'CR',  6850.00, 'KES', 'Transfer from J. OTIENO',        33350.00, now() - interval '12 days'),
 ('FT26307AA005', 'acc_2LM8', 'DR',   500.00, 'KES', 'M-PESA B2C 0712•••678',          32850.00, now() - interval '9 days'),
 ('FT26309AA006', 'acc_2LM8', 'CR',  2500.00, 'KES', 'M-PESA C2B 0712•••678',          35350.00, now() - interval '1 day'),
 ('FT26301BB001', 'acc_6TR3', 'CR', 12840.50, 'KES', 'Opening transfer',               12840.50, now() - interval '30 days'),
 ('FT26290CC001', 'acc_7BX2', 'CR',120000.00, 'KES', 'Business receipts',             120000.00, now() - interval '40 days');
