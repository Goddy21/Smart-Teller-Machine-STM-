-- STM middleware schema (PostgreSQL 14+). Default READ COMMITTED + MVCC is what the code assumes.

CREATE TABLE terminals (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    terminal_code VARCHAR(50)  NOT NULL,
    branch_code   VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    token_hash    CHAR(64)     NOT NULL,
    created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    CONSTRAINT uq_terminals_code UNIQUE (terminal_code),
    CONSTRAINT uq_terminals_token UNIQUE (token_hash),
    CONSTRAINT ck_terminals_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE transactions (
    seq             BIGINT GENERATED ALWAYS AS IDENTITY,
    id              UUID          NOT NULL PRIMARY KEY,
    terminal_id     BIGINT        NOT NULL REFERENCES terminals (id),
    type            VARCHAR(30)   NOT NULL DEFAULT 'DEPOSIT',
    state           VARCHAR(30)   NOT NULL,
    customer_id     VARCHAR(50)   NOT NULL,
    account_no      VARCHAR(30)   NOT NULL,
    currency        CHAR(3)       NOT NULL,
    amount          NUMERIC(19,4) NULL,
    bank_reference  VARCHAR(100)  NULL,
    failure_reason  VARCHAR(200)  NULL,
    idempotency_key VARCHAR(100)  NOT NULL,
    version         INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_transactions_seq  ON transactions (seq);
CREATE UNIQUE INDEX uq_transactions_idem ON transactions (terminal_id, idempotency_key);
CREATE INDEX ix_transactions_state ON transactions (state, updated_at);

CREATE TABLE escrow_ledger (
    seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transactions (id),
    entry_type     VARCHAR(30)   NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_type CHECK (entry_type IN ('CASH_ACCEPTED', 'CREDIT_POSTED', 'CASH_RETURN_REQUIRED'))
);
CREATE INDEX ix_escrow_ledger_tx ON escrow_ledger (transaction_id);

CREATE TABLE audit_journal (
    seq         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type  VARCHAR(60)  NOT NULL,
    entity_id   VARCHAR(64)  NOT NULL,
    terminal_id BIGINT       NULL,
    payload     TEXT         NOT NULL,   -- TEXT (not jsonb) so the exact hashed bytes are preserved
    prev_hash   CHAR(64)     NOT NULL,
    hash        CHAR(64)     NOT NULL,
    created_at  TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX ix_audit_entity ON audit_journal (entity_id);

-- Append-only enforcement for the ledger and the audit journal.
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_escrow_ledger_immutable BEFORE UPDATE OR DELETE ON escrow_ledger
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_escrow_ledger_no_truncate BEFORE TRUNCATE ON escrow_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_audit_journal_immutable BEFORE UPDATE OR DELETE ON audit_journal
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_audit_journal_no_truncate BEFORE TRUNCATE ON audit_journal
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TABLE terminal_telemetry (
    terminal_id BIGINT      NOT NULL PRIMARY KEY REFERENCES terminals (id),
    status      VARCHAR(30) NOT NULL,
    cassettes   TEXT        NULL,
    card_stock  INT         NULL,
    alarms      TEXT        NULL,
    last_seen   TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
