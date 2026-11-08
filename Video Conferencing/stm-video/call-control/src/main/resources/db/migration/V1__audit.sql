CREATE TABLE calls (
  call_id text PRIMARY KEY, tx_id text NOT NULL, terminal_id text NOT NULL, teller_id text, lang text,
  requested_at timestamptz NOT NULL DEFAULT now(), connected_at timestamptz, ended_at timestamptz, final_state text
);
CREATE INDEX calls_tx ON calls (tx_id);

-- Append-only, hash-chained per call. payload is stored as text so the hashed bytes are exactly the stored bytes.
CREATE TABLE call_events (
  call_id text NOT NULL, seq int NOT NULL, type text NOT NULL, at timestamptz NOT NULL,
  payload text NOT NULL, prev_hash text NOT NULL, entry_hash text NOT NULL,
  PRIMARY KEY (call_id, seq)
);
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION '% is append-only', TG_TABLE_NAME; END $$ LANGUAGE plpgsql;
CREATE TRIGGER call_events_no_update BEFORE UPDATE OR DELETE ON call_events FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER call_events_no_truncate BEFORE TRUNCATE ON call_events FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TABLE recordings (
  track_sid text PRIMARY KEY, egress_id text UNIQUE, call_id text NOT NULL, tx_id text NOT NULL,
  participant text NOT NULL, track_name text NOT NULL,
  status text NOT NULL,   -- PENDING STARTING ACTIVE COMPLETE HASHING SEALED | START_FAILED FAILED HASH_FAILED
  object_key text, size_bytes bigint, duration_ms bigint, sha256 text,
  started_at timestamptz NOT NULL DEFAULT now(), hashed_at timestamptz, hash_claimed_at timestamptz,
  hash_attempts int NOT NULL DEFAULT 0, retention_until timestamptz NOT NULL, error text
);
CREATE INDEX recordings_call ON recordings (call_id);
CREATE INDEX recordings_tx ON recordings (tx_id);
CREATE INDEX recordings_status ON recordings (status);
