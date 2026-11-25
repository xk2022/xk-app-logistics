CREATE TABLE logistics.fms_driver (
    id uuid PRIMARY KEY,
    driver_code varchar(40) NOT NULL UNIQUE CHECK (char_length(driver_code) BETWEEN 1 AND 40),
    name varchar(200) NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    phone varchar(50),
    email varchar(254),
    account_id uuid UNIQUE REFERENCES logistics.upms_account(id),
    employment_status varchar(16) NOT NULL CHECK (employment_status IN ('ACTIVE','INACTIVE','LEFT')),
    duty_status varchar(16) NOT NULL CHECK (duty_status IN ('ON_DUTY','OFF_DUTY')),
    license_type_id uuid REFERENCES logistics.adm_dict_item(id),
    license_reference varchar(100),
    license_due_date date CHECK (license_due_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    version bigint NOT NULL DEFAULT 0 CHECK (version BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES logistics.upms_account(id),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES logistics.upms_account(id)
);
CREATE INDEX fms_driver_options_idx ON logistics.fms_driver(employment_status,duty_status,driver_code,id);
CREATE INDEX fms_driver_license_idx ON logistics.fms_driver(license_type_id,driver_code,id);
-- Only an empty classification framework. No formal license values, drivers, or accounts are seeded.
INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,version,created_at,updated_at)
VALUES ('00000000-0000-0000-0000-000000000105','DRIVER_LICENSE_TYPE','駕照類別',true,50,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
