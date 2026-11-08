-- Kiosk journeys: account opening and debit card issuance.

ALTER TABLE terminals ADD COLUMN service_mode VARCHAR(20) NOT NULL DEFAULT 'IN_SERVICE';
ALTER TABLE terminals ADD CONSTRAINT ck_terminals_mode CHECK (service_mode IN ('IN_SERVICE', 'LOCKED'));

CREATE TABLE branches (
    code VARCHAR(20)  PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);
INSERT INTO branches (code, name) VALUES ('NBO-01', 'Nairobi CBD Branch (demo)');

-- DEMO product terms. Replace with the bank's real terms before go-live.
CREATE TABLE account_products (
    code                VARCHAR(20)   PRIMARY KEY,
    name                VARCHAR(50)   NOT NULL,
    currency            CHAR(3)       NOT NULL,
    interest_rate       NUMERIC(5,2)  NULL,
    min_opening_balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    monthly_fee         NUMERIC(19,2) NOT NULL DEFAULT 0,
    overdraft           BOOLEAN       NOT NULL DEFAULT false,
    active              BOOLEAN       NOT NULL DEFAULT true
);
INSERT INTO account_products (code, name, currency, interest_rate, min_opening_balance, monthly_fee, overdraft)
VALUES ('SAVINGS',  'Savings',  'KES', 3.50, 500.00, 0.00,   false),
       ('CHECKING', 'Checking', 'KES', NULL, 0.00,   300.00, true);

CREATE TABLE kiosk_sessions (
    id                UUID PRIMARY KEY,
    terminal_id       BIGINT       NOT NULL REFERENCES terminals (id),
    idempotency_key   VARCHAR(100) NOT NULL,
    journey           VARCHAR(20)  NOT NULL,
    language          CHAR(2)      NOT NULL DEFAULT 'en',
    step              VARCHAR(30)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    -- identity (from the passport MRZ)
    surname           VARCHAR(80),
    given_names       VARCHAR(120),
    date_of_birth     DATE,
    nationality       CHAR(3),
    sex               CHAR(1),
    document_type     VARCHAR(20),
    document_number   VARCHAR(20),
    document_expiry   DATE,
    mobile            VARCHAR(20),
    email             VARCHAR(120),
    consent_given     BOOLEAN      NOT NULL DEFAULT false,
    consent_at        TIMESTAMPTZ(3),
    customer_id       VARCHAR(50),
    review_required   BOOLEAN      NOT NULL DEFAULT false,
    review_status     VARCHAR(12)  NOT NULL DEFAULT 'NONE',
    identity_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    face_status       VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    face_attempts     INT          NOT NULL DEFAULT 0,
    finger_status     VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    finger_attempts   INT          NOT NULL DEFAULT 0,
    -- product and agreement
    tier              VARCHAR(10),
    product_code      VARCHAR(20)  REFERENCES account_products (code),
    agreement_version VARCHAR(40),
    signature_method  VARCHAR(10),
    signed_at         TIMESTAMPTZ(3),
    -- outcome
    account_no        VARCHAR(30),
    account_branch    VARCHAR(100),
    account_currency  CHAR(3),
    card_ref          VARCHAR(60),
    card_masked_pan   VARCHAR(25),
    card_status       VARCHAR(30)  NOT NULL DEFAULT 'NONE',
    embossed_name     VARCHAR(30),
    error_ref         VARCHAR(30),
    lookup_attempts   INT          NOT NULL DEFAULT 0,
    escalated         BOOLEAN      NOT NULL DEFAULT false,
    escalation_reason VARCHAR(200),
    pii_scrubbed      BOOLEAN      NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ(3),
    CONSTRAINT ck_sessions_status CHECK (status IN
        ('IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'TIMED_OUT', 'ABANDONED', 'DECLINED', 'REJECTED'))
);
CREATE UNIQUE INDEX uq_sessions_idem ON kiosk_sessions (terminal_id, idempotency_key);
CREATE INDEX ix_sessions_status ON kiosk_sessions (status, updated_at);
CREATE INDEX ix_sessions_terminal ON kiosk_sessions (terminal_id, status);

-- KYC document archive (passport portrait/face capture, proof of address, signature, signed paper agreement).
CREATE TABLE kyc_documents (
    id         UUID PRIMARY KEY,
    session_id UUID         NOT NULL REFERENCES kiosk_sessions (id),
    doc_type   VARCHAR(30)  NOT NULL,
    mime_type  VARCHAR(60)  NOT NULL,
    size_bytes INT          NOT NULL,
    sha256     CHAR(64)     NOT NULL,
    content    BYTEA        NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_kyc_documents_session ON kyc_documents (session_id, doc_type);

-- Cards that could not be dispensed at the kiosk and must be collected at a branch.
CREATE TABLE card_fulfilments (
    id          UUID PRIMARY KEY,
    session_id  UUID         NOT NULL REFERENCES kiosk_sessions (id),
    card_ref    VARCHAR(60),
    account_no  VARCHAR(30)  NOT NULL,
    branch_code VARCHAR(20)  NOT NULL,
    error_ref   VARCHAR(30)  NOT NULL,
    status      VARCHAR(25)  NOT NULL DEFAULT 'PENDING_COLLECTION',
    created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_card_fulfilments_status ON card_fulfilments (status, created_at);
