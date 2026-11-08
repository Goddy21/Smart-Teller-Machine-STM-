CREATE TABLE call_session (
  id            NVARCHAR(36) NOT NULL PRIMARY KEY,
  terminal_id   NVARCHAR(64) NOT NULL,
  teller_id     NVARCHAR(64) NULL,
  tx_id         NVARCHAR(64) NULL,
  status        NVARCHAR(16) NOT NULL,
  end_reason    NVARCHAR(64) NULL,
  requested_at  DATETIME2 NOT NULL,
  accepted_at   DATETIME2 NULL,
  connected_at  DATETIME2 NULL,
  ended_at      DATETIME2 NULL
);
CREATE INDEX ix_call_session_terminal ON call_session (terminal_id, requested_at);
CREATE INDEX ix_call_session_teller   ON call_session (teller_id, requested_at);
CREATE INDEX ix_call_session_tx       ON call_session (tx_id);
