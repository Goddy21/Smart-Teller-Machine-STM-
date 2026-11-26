-- Switches administrators flip in the portal that must survive a restart or a redeploy.
-- admin.test-data-tools: the administrators' edit/delete tools for test records (Simulator page switch).
CREATE TABLE portal_settings (
    key         VARCHAR(60)    PRIMARY KEY,
    value       VARCHAR(200)   NOT NULL,
    updated_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
    updated_by  VARCHAR(50)
);
INSERT INTO portal_settings (key, value, updated_by) VALUES ('admin.test-data-tools', 'true', 'setup');
