-- OrderCore and TOM are one persistence aggregate in this slice. No dispatch/execution/asset tables or seeds.
CREATE TABLE logistics.order_core (
 id uuid PRIMARY KEY,
 company_scope varchar(64) NOT NULL,
 order_no varchar(100) NOT NULL UNIQUE,
 lifecycle_status varchar(16) NOT NULL CHECK(lifecycle_status IN ('OPEN','CANCELLED','CLOSED')),
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id)
);
CREATE TABLE logistics.tom_order (
 order_id uuid PRIMARY KEY REFERENCES logistics.order_core(id),
 order_type varchar(16) NOT NULL CHECK(order_type IN ('IMPORT','EXPORT','LOCAL')),
 customer_id uuid NOT NULL REFERENCES logistics.crm_customer(id), customer_name varchar(200) NOT NULL,
 acceptance_status varchar(16) NOT NULL CHECK(acceptance_status IN ('DRAFT','ACCEPTED')),
 version bigint NOT NULL CHECK(version BETWEEN 0 AND 9007199254740991),
 customer_reference bytea, remark bytea,
 shipping_present boolean NOT NULL,
 shipping_company bytea, vessel_voyage bytea, pol bytea, pod bytea, bl_no bytea,
 eta timestamptz(6), etd timestamptz(6), eta_wire varchar(64), etd_wire varchar(64),
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id),
 CHECK(shipping_present OR (shipping_company IS NULL AND vessel_voyage IS NULL AND pol IS NULL AND pod IS NULL AND bl_no IS NULL AND eta IS NULL AND etd IS NULL AND eta_wire IS NULL AND etd_wire IS NULL)),
 CHECK((eta IS NULL)=(eta_wire IS NULL) AND (etd IS NULL)=(etd_wire IS NULL))
);
CREATE TABLE logistics.cargo_item (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES logistics.tom_order(order_id),
 client_ref varchar(64) NOT NULL CHECK(client_ref ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'),
 input_position integer NOT NULL CHECK(input_position>=0),
 sequence integer CHECK(sequence>0), item_kind varchar(16) CHECK(item_kind IN ('CONTAINER','CARGO')),
 container_type bytea, container_no bytea, package_qty integer CHECK(package_qty>0),
 gross_weight numeric CHECK(gross_weight>=0), cbm numeric CHECK(cbm>=0),
 last_pickup_date date, last_pickup_date_wire varchar(16),
 delivery_cutoff_at timestamptz(6), delivery_cutoff_wire varchar(64),
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id),
 UNIQUE(order_id,id), UNIQUE(order_id,client_ref),
 CONSTRAINT cargo_item_order_sequence UNIQUE(order_id,sequence) DEFERRABLE INITIALLY DEFERRED,
 CHECK((last_pickup_date IS NULL)=(last_pickup_date_wire IS NULL) AND (delivery_cutoff_at IS NULL)=(delivery_cutoff_wire IS NULL))
);
CREATE TABLE logistics.transport_task (
 id uuid PRIMARY KEY, task_no varchar(100) NOT NULL UNIQUE,
 order_id uuid NOT NULL REFERENCES logistics.tom_order(order_id), item_id uuid NOT NULL,
 client_ref varchar(64) NOT NULL CHECK(client_ref ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'), input_position integer NOT NULL CHECK(input_position>=0),
 task_kind bytea, pickup_name bytea, pickup_address bytea, delivery_name bytea, delivery_address bytea,
 contact_present boolean NOT NULL, contact_name bytea, contact_phone bytea,
 planned_start_at timestamptz(6), planned_end_at timestamptz(6), planned_start_wire varchar(64), planned_end_wire varchar(64),
 required_task boolean CHECK(required_task IS NULL OR required_task),
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id),
 UNIQUE(order_id,id), UNIQUE(order_id,client_ref),
 FOREIGN KEY(order_id,item_id) REFERENCES logistics.cargo_item(order_id,id),
 CHECK(contact_present OR (contact_name IS NULL AND contact_phone IS NULL)),
 CHECK(planned_start_at IS NULL OR planned_end_at IS NULL OR planned_end_at>=planned_start_at),
 CHECK((planned_start_at IS NULL)=(planned_start_wire IS NULL) AND (planned_end_at IS NULL)=(planned_end_wire IS NULL))
);
CREATE TABLE logistics.task_dependency (
 order_id uuid NOT NULL, task_id uuid NOT NULL, predecessor_task_id uuid NOT NULL,
 input_position integer NOT NULL CHECK(input_position>=0),
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id),
 PRIMARY KEY(order_id,task_id,predecessor_task_id),
 FOREIGN KEY(order_id,task_id) REFERENCES logistics.transport_task(order_id,id),
 FOREIGN KEY(order_id,predecessor_task_id) REFERENCES logistics.transport_task(order_id,id),
 CHECK(task_id<>predecessor_task_id)
);
CREATE TABLE logistics.order_draft_revision (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES logistics.tom_order(order_id),
 aggregate_version bigint NOT NULL CHECK(aggregate_version BETWEEN 0 AND 9007199254740991),
 snapshot json NOT NULL, request_trace_id varchar(200) NOT NULL,
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id),
 UNIQUE(order_id,aggregate_version)
);
CREATE TABLE logistics.api_idempotency (
 id uuid PRIMARY KEY, company_scope varchar(64) NOT NULL, actor_id uuid NOT NULL REFERENCES logistics.upms_account(id),
 http_method varchar(8) NOT NULL, canonical_path varchar(200) NOT NULL, idempotency_key varchar(128) NOT NULL,
 request_hash varchar(64) NOT NULL, order_id uuid NOT NULL REFERENCES logistics.tom_order(order_id),
 original_status integer NOT NULL CHECK(original_status IN (200,201)), original_data json NOT NULL, original_location varchar(200),
 created_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 created_by uuid NOT NULL REFERENCES logistics.upms_account(id), updated_by uuid NOT NULL REFERENCES logistics.upms_account(id),
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key)
);
CREATE INDEX tom_order_customer_idx ON logistics.tom_order(customer_id,order_type,order_id);
CREATE INDEX order_core_scope_updated_idx ON logistics.order_core(company_scope,updated_at,id);
CREATE INDEX cargo_item_read_idx ON logistics.cargo_item(order_id,input_position,id);
CREATE INDEX transport_task_read_idx ON logistics.transport_task(order_id,input_position,id);
CREATE FUNCTION logistics.reject_order_revision_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Order draft revisions are immutable'; END;
$$;
CREATE TRIGGER order_draft_revision_immutable BEFORE UPDATE OR DELETE ON logistics.order_draft_revision
FOR EACH ROW EXECUTE FUNCTION logistics.reject_order_revision_change();
