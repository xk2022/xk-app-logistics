CREATE TABLE logistics.crm_customer (
    id uuid PRIMARY KEY,
    customer_code varchar(64) NOT NULL UNIQUE CHECK (customer_code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
    name varchar(200) NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    tax_id varchar(50),
    phone varchar(50),
    email varchar(254),
    enabled boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES logistics.upms_account(id),
    updated_by uuid NOT NULL REFERENCES logistics.upms_account(id)
);
CREATE TABLE logistics.crm_contact (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES logistics.crm_customer(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    title varchar(100),
    phone varchar(50),
    email varchar(254),
    enabled boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES logistics.upms_account(id),
    updated_by uuid NOT NULL REFERENCES logistics.upms_account(id)
);
CREATE TABLE logistics.crm_address (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES logistics.crm_customer(id),
    label varchar(100),
    address varchar(1000) NOT NULL CHECK (length(trim(address)) BETWEEN 1 AND 1000),
    address_type varchar(40),
    enabled boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES logistics.upms_account(id),
    updated_by uuid NOT NULL REFERENCES logistics.upms_account(id)
);
CREATE INDEX crm_customer_options_idx ON logistics.crm_customer(enabled,customer_code,id);
CREATE INDEX crm_contact_parent_idx ON logistics.crm_contact(customer_id,enabled,name,id);
CREATE INDEX crm_address_parent_idx ON logistics.crm_address(customer_id,enabled,created_at,id);
-- No customer, contact, address, or test fixture is seeded into a normal database.
