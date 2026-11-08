-- STM portal sign-in: people who use the web portal (tellers, supervisors, auditors, administrators).
-- Passwords are stored as BCrypt hashes only. Session tokens are stored as SHA-256 hashes only.

CREATE TABLE portal_users (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username             VARCHAR(50)  NOT NULL,
    display_name         VARCHAR(100) NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    role                 VARCHAR(20)  NOT NULL,
    branch_code          VARCHAR(20)  NULL REFERENCES branches (code),
    languages            VARCHAR(50)  NOT NULL DEFAULT 'en',
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password BOOLEAN      NOT NULL DEFAULT true,
    failed_attempts      INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ(3) NULL,
    last_login_at        TIMESTAMPTZ(3) NULL,
    password_changed_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    created_by           VARCHAR(50)  NULL,
    created_at           TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    CONSTRAINT uq_portal_users_username UNIQUE (username),
    CONSTRAINT ck_portal_users_role CHECK (role IN ('TELLER', 'SUPERVISOR', 'AUDITOR', 'ADMIN')),
    CONSTRAINT ck_portal_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE portal_sessions (
    token_hash   CHAR(64)     PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES portal_users (id),
    created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ(3) NOT NULL,
    revoked      BOOLEAN      NOT NULL DEFAULT false,
    client_ip    VARCHAR(45)  NULL,
    user_agent   VARCHAR(200) NULL
);
CREATE INDEX ix_portal_sessions_user ON portal_sessions (user_id, revoked);
CREATE INDEX ix_portal_sessions_expiry ON portal_sessions (expires_at);
