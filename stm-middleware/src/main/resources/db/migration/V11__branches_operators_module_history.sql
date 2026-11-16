-- Branches managed in the portal (not only through the kiosk form), kiosk model and address, a history of module
-- status changes from the D-6 health reports, and terminal users: the people who sign in on the machine itself
-- (custodians, cash-in-transit crews, engineers, branch supervisors) with a username and PIN.

ALTER TABLE branches ADD COLUMN location   VARCHAR(160);
ALTER TABLE branches ADD COLUMN region     VARCHAR(60);
ALTER TABLE branches ADD COLUMN status     VARCHAR(12)    NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE branches ADD COLUMN created_at TIMESTAMPTZ(3) NOT NULL DEFAULT now();
ALTER TABLE branches ADD COLUMN updated_at TIMESTAMPTZ(3);
ALTER TABLE branches ADD CONSTRAINT ck_branches_status CHECK (status IN ('ACTIVE', 'CLOSED'));

ALTER TABLE terminals ADD COLUMN model   VARCHAR(60);
ALTER TABLE terminals ADD COLUMN last_ip VARCHAR(45);

CREATE TABLE terminal_module_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    terminal_id BIGINT         NOT NULL REFERENCES terminals (id),
    module      VARCHAR(40)    NOT NULL,
    from_status VARCHAR(30),
    to_status   VARCHAR(30)    NOT NULL,
    detail      VARCHAR(200),
    at          TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_module_events_terminal ON terminal_module_events (terminal_id, at DESC);

CREATE TABLE terminal_operators (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username        VARCHAR(40)    NOT NULL UNIQUE,
    full_name       VARCHAR(100)   NOT NULL,
    role            VARCHAR(20)    NOT NULL,
    phone           VARCHAR(20),
    branch_code     VARCHAR(20)    REFERENCES branches (code),
    all_terminals   BOOLEAN        NOT NULL DEFAULT false,
    pin_hash        VARCHAR(100)   NOT NULL,
    status          VARCHAR(12)    NOT NULL DEFAULT 'ACTIVE',
    failed_attempts INT            NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ(3),
    last_login_at   TIMESTAMPTZ(3),
    last_terminal   VARCHAR(50),
    pin_changed_at  TIMESTAMPTZ(3),
    created_by      VARCHAR(40),
    created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ(3),
    CONSTRAINT ck_operator_role CHECK (role IN ('CUSTODIAN', 'CIT', 'ENGINEER', 'SUPERVISOR')),
    CONSTRAINT ck_operator_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE terminal_operator_kiosks (
    operator_id BIGINT NOT NULL REFERENCES terminal_operators (id) ON DELETE CASCADE,
    terminal_id BIGINT NOT NULL REFERENCES terminals (id) ON DELETE CASCADE,
    PRIMARY KEY (operator_id, terminal_id)
);
