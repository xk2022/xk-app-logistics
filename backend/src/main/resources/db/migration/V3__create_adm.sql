CREATE TABLE logistics.adm_dict_category (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    name varchar(160) NOT NULL,
    enabled boolean NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order BETWEEN 0 AND 1000000),
    read_only boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE logistics.adm_dict_item (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES logistics.adm_dict_category(id),
    code varchar(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    label varchar(160) NOT NULL,
    enabled boolean NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order BETWEEN 0 AND 1000000),
    read_only boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (category_id, code)
);
CREATE INDEX adm_item_options_idx ON logistics.adm_dict_item(category_id, enabled, sort_order, code, id);

-- Approved metadata only: intentionally empty. Add business keys solely by a separately approved migration.
CREATE TABLE logistics.adm_parameter_definition (
    parameter_key varchar(64) PRIMARY KEY CHECK (parameter_key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    name varchar(160) NOT NULL,
    value_type varchar(16) NOT NULL CHECK (value_type IN ('TEXT','NUMBER','BOOLEAN')),
    description varchar(1000) NOT NULL CHECK (length(trim(description)) > 0),
    min_length integer,
    max_length integer,
    allowed_values jsonb NOT NULL DEFAULT '[]'::jsonb,
    min_value numeric(38,12),
    max_value numeric(38,12),
    decimal_scale integer,
    UNIQUE (parameter_key, value_type),
    CHECK (jsonb_typeof(allowed_values) = 'array' AND jsonb_array_length(allowed_values) <= 1000),
    CHECK (parameter_key !~ '(^|_)(AUTH|SESSION|SECURITY|SECRET|PASSWORD|TOKEN|CSRF|JWT|COOKIE|TLS|SSL|DATABASE|DATASOURCE|SMTP|CREDENTIAL|INFRA|PORT|HOST|URL|URI|ORIGIN|CORS|ITERATIONS)(_|$)'),
    CHECK ((
      (value_type='TEXT' AND min_value IS NULL AND max_value IS NULL AND decimal_scale IS NULL AND
        ((jsonb_array_length(allowed_values)>0 AND min_length IS NULL AND max_length IS NULL) OR
         (jsonb_array_length(allowed_values)=0 AND min_length BETWEEN 0 AND 2000 AND max_length BETWEEN min_length AND 2000))) OR
      (value_type='NUMBER' AND min_length IS NULL AND max_length IS NULL AND jsonb_array_length(allowed_values)=0
        AND min_value IS NOT NULL AND max_value IS NOT NULL AND min_value<=max_value AND decimal_scale BETWEEN 0 AND 12) OR
      (value_type='BOOLEAN' AND min_length IS NULL AND max_length IS NULL AND jsonb_array_length(allowed_values)=0
        AND min_value IS NULL AND max_value IS NULL AND decimal_scale IS NULL)
    ) IS TRUE)
);
CREATE TABLE logistics.adm_parameter (
    id uuid PRIMARY KEY,
    parameter_key varchar(64) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    value_type varchar(16) NOT NULL,
    value_text varchar(2000) NOT NULL,
    description varchar(1000) NOT NULL,
    enabled boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (parameter_key, value_type) REFERENCES logistics.adm_parameter_definition(parameter_key, value_type)
);
CREATE TABLE logistics.adm_change_log (
    id uuid PRIMARY KEY,
    target_type varchar(16) NOT NULL CHECK (target_type IN ('CATEGORY','ITEM','PARAMETER')),
    target_id uuid NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('CREATE','UPDATE')),
    actor_id uuid NOT NULL REFERENCES logistics.upms_account(id),
    actor_username varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    before_value jsonb,
    after_value jsonb NOT NULL,
    CHECK (before_value IS NULL OR jsonb_typeof(before_value)='object'),
    CHECK (jsonb_typeof(after_value)='object')
);
CREATE INDEX adm_change_target_idx ON logistics.adm_change_log(target_type,target_id,occurred_at DESC,id DESC);

-- Fixed enum mirrors only. Codes and labels are the original order-contract values, not configurable business rules.
INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,created_at,updated_at) VALUES
('00000000-0000-0000-0000-000000000101','ORDER_TYPE','訂單類型',true,10,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000102','CONTAINER_TYPE','貨櫃類型',true,20,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000103','TASK_KIND','任務類型',true,30,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000104','VEHICLE_TYPE','車輛類型',true,40,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,created_at,updated_at) VALUES
('00000000-0000-0000-0000-000000000111','00000000-0000-0000-0000-000000000101','IMPORT','IMPORT',true,10,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000112','00000000-0000-0000-0000-000000000101','EXPORT','EXPORT',true,20,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000113','00000000-0000-0000-0000-000000000101','LOCAL','LOCAL',true,30,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
