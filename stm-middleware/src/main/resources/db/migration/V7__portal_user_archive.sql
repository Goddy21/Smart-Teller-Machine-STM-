-- Removing a portal account: never-used accounts are deleted; used ones are ARCHIVED, so the audit trail and past
-- decisions still name a real account. Archived accounts cannot sign in and are hidden from the account list.

ALTER TABLE portal_users DROP CONSTRAINT ck_portal_users_status;
ALTER TABLE portal_users ADD CONSTRAINT ck_portal_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED'));
ALTER TABLE portal_users ADD COLUMN archived_at TIMESTAMPTZ(3) NULL;
ALTER TABLE portal_users ADD COLUMN archived_by VARCHAR(50)    NULL;
