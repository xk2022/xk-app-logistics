CREATE TABLE logistics.upms_lock (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO logistics.upms_lock (id) VALUES (1);

CREATE TABLE logistics.upms_account (
    id uuid PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    display_name varchar(160) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'DISPATCHER', 'DRIVER')),
    enabled boolean NOT NULL,
    must_change_password boolean NOT NULL,
    password_hash varchar(512) NOT NULL,
    temporary_password_expires_at timestamptz,
    auth_version bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE logistics.upms_audit (
    id uuid PRIMARY KEY,
    actor varchar(128) NOT NULL,
    target_id uuid,
    action varchar(64) NOT NULL,
    reason varchar(500) NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX upms_audit_target_time_idx ON logistics.upms_audit (target_id, occurred_at);
