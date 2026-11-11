-- Portal accounts get an email address; sign-in asks for a one-time code sent to it (second factor).

ALTER TABLE portal_users ADD COLUMN email VARCHAR(200) NULL;
CREATE UNIQUE INDEX uq_portal_users_email ON portal_users (lower(email)) WHERE email IS NOT NULL;

-- One row per sign-in waiting for its emailed code. The challenge token and the code are stored as SHA-256 only.
CREATE TABLE portal_login_challenges (
    token_hash   CHAR(64)       PRIMARY KEY,
    user_id      BIGINT         NOT NULL REFERENCES portal_users (id),
    code_hash    CHAR(64)       NOT NULL,
    expires_at   TIMESTAMPTZ(3) NOT NULL,
    attempts     INT            NOT NULL DEFAULT 0,
    resends      INT            NOT NULL DEFAULT 0,
    last_sent_at TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    consumed     BOOLEAN        NOT NULL DEFAULT false,
    client_ip    VARCHAR(45)    NULL,
    created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX ix_portal_login_challenges_user ON portal_login_challenges (user_id, created_at);

-- Every email the middleware sends (or simulates when no mail server is configured).
-- Bodies are kept only for SIMULATED mail, so an administrator can read the code during a pilot without SMTP.
CREATE TABLE email_outbox (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    to_address  VARCHAR(200)   NOT NULL,
    subject     VARCHAR(200)   NOT NULL,
    body        TEXT           NULL,
    purpose     VARCHAR(40)    NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    error       VARCHAR(500)   NULL,
    created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_outbox_status CHECK (status IN ('SENT', 'FAILED', 'SIMULATED'))
);
CREATE INDEX ix_email_outbox_created ON email_outbox (created_at DESC);
