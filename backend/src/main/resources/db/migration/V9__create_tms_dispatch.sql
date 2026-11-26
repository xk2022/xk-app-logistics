CREATE TABLE logistics.tms_task_dispatch (
 task_id uuid PRIMARY KEY, order_id uuid NOT NULL, company_scope varchar(64) NOT NULL,
 order_no varchar(100) NOT NULL, task_no varchar(100) NOT NULL,
 source_order_version bigint NOT NULL CHECK(source_order_version BETWEEN 0 AND 9007199254740991),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation BETWEEN 0 AND 9007199254740991),
 FOREIGN KEY(order_id,task_id) REFERENCES logistics.transport_task(order_id,id)
);
CREATE INDEX tms_task_page_idx ON logistics.tms_task_dispatch(company_scope,task_no,task_id);
-- Authorized identity-only derived index. Never invent assignments, actors or business events.
INSERT INTO logistics.tms_task_dispatch(task_id,order_id,company_scope,order_no,task_no,source_order_version,generation)
SELECT t.id,c.id,c.company_scope,c.order_no,t.task_no,o.version,0
FROM logistics.transport_task t JOIN logistics.tom_order o ON o.order_id=t.order_id JOIN logistics.order_core c ON c.id=o.order_id
WHERE c.lifecycle_status='OPEN' AND o.acceptance_status='ACCEPTED';

CREATE TABLE logistics.tms_assignment (
 id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES logistics.tms_task_dispatch(task_id), order_id uuid NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ASSIGNED','REVOKED','SUPERSEDED')),
 version bigint NOT NULL CHECK(version IN (0,1)), assigned_generation bigint NOT NULL CHECK(assigned_generation BETWEEN 1 AND 9007199254740991),
 ended_generation bigint CHECK(ended_generation BETWEEN 1 AND 9007199254740991),
 driver_id uuid NOT NULL REFERENCES logistics.fms_driver(id), main_vehicle_id uuid NOT NULL REFERENCES logistics.fms_vehicle(id), trailer_id uuid REFERENCES logistics.fms_vehicle(id),
 driver_snapshot json NOT NULL, main_vehicle_snapshot json NOT NULL, trailer_snapshot json, source_snapshot json NOT NULL,
 policy_mode varchar(32) NOT NULL CHECK(policy_mode IN ('UNCONFIRMED','SYNTHETIC_TEST_ONLY')),
 assigned_by uuid NOT NULL REFERENCES logistics.upms_account(id), assigned_at timestamptz(6) NOT NULL, assignment_reason varchar(500),
 ended_by uuid REFERENCES logistics.upms_account(id), ended_at timestamptz(6), end_reason varchar(500), previous_assignment_id uuid REFERENCES logistics.tms_assignment(id),
 FOREIGN KEY(order_id,task_id) REFERENCES logistics.transport_task(order_id,id),
 CHECK(trailer_id IS NULL OR trailer_id<>main_vehicle_id), CHECK((trailer_id IS NULL)=(trailer_snapshot IS NULL)),
 CHECK((status='ASSIGNED' AND version=0 AND ended_generation IS NULL AND ended_by IS NULL AND ended_at IS NULL AND end_reason IS NULL)
    OR(status IN ('REVOKED','SUPERSEDED') AND version=1 AND ended_generation IS NOT NULL AND ended_by IS NOT NULL AND ended_at IS NOT NULL AND end_reason IS NOT NULL)),
 CHECK(ended_generation IS NULL OR ended_generation>assigned_generation)
);
CREATE UNIQUE INDEX tms_assignment_current_task ON logistics.tms_assignment(task_id) WHERE status='ASSIGNED';
CREATE INDEX tms_assignment_history_idx ON logistics.tms_assignment(task_id,assigned_generation DESC,id);
CREATE TABLE logistics.tms_resource_reservation (
 id uuid PRIMARY KEY, assignment_id uuid NOT NULL REFERENCES logistics.tms_assignment(id),
 driver_id uuid REFERENCES logistics.fms_driver(id), vehicle_id uuid REFERENCES logistics.fms_vehicle(id),
 resource_kind varchar(16) GENERATED ALWAYS AS (CASE WHEN driver_id IS NOT NULL THEN 'DRIVER' ELSE 'VEHICLE' END) STORED,
 resource_id uuid GENERATED ALWAYS AS (coalesce(driver_id,vehicle_id)) STORED,
 position varchar(16) NOT NULL CHECK(position IN ('DRIVER','MAIN','TRAILER')),
 acquired_at timestamptz(6) NOT NULL, released_at timestamptz(6), release_reason varchar(500),
 CHECK(num_nonnulls(driver_id,vehicle_id)=1), CHECK((driver_id IS NOT NULL AND position='DRIVER') OR(vehicle_id IS NOT NULL AND position IN ('MAIN','TRAILER'))),
 CHECK((released_at IS NULL)=(release_reason IS NULL)), UNIQUE(assignment_id,position)
);
CREATE UNIQUE INDEX tms_reservation_active_resource ON logistics.tms_resource_reservation(resource_kind,resource_id) WHERE released_at IS NULL;
CREATE TABLE logistics.tms_dispatch_event (
 id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES logistics.tms_task_dispatch(task_id),
 action varchar(16) NOT NULL CHECK(action IN ('ASSIGNED','REVOKED','REASSIGNED')),
 assignment_id uuid NOT NULL REFERENCES logistics.tms_assignment(id), previous_assignment_id uuid REFERENCES logistics.tms_assignment(id),
 from_generation bigint NOT NULL CHECK(from_generation BETWEEN 0 AND 9007199254740991),
 to_generation bigint NOT NULL CHECK(to_generation BETWEEN 1 AND 9007199254740991),
 actor_id uuid NOT NULL REFERENCES logistics.upms_account(id), occurred_at timestamptz(6) NOT NULL, reason varchar(500), snapshot json NOT NULL,
 CHECK(to_generation=from_generation+1), UNIQUE(task_id,to_generation),
 CHECK((action='ASSIGNED' AND reason IS NULL) OR(action<>'ASSIGNED' AND reason IS NOT NULL))
);
CREATE TABLE logistics.tms_idempotency (
 id uuid PRIMARY KEY, company_scope varchar(64) NOT NULL, actor_id uuid NOT NULL REFERENCES logistics.upms_account(id),
 http_method varchar(8) NOT NULL, canonical_path varchar(200) NOT NULL, idempotency_key varchar(128) NOT NULL,
 request_hash varchar(64) NOT NULL, task_id uuid NOT NULL REFERENCES logistics.tms_task_dispatch(task_id), original_status integer NOT NULL CHECK(original_status=200),
 original_data json NOT NULL, created_at timestamptz(6) NOT NULL,
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key)
);
CREATE FUNCTION logistics.protect_tms_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Dispatch assignments cannot be deleted'; END IF;
 IF OLD.status<>'ASSIGNED' OR NEW.status NOT IN ('REVOKED','SUPERSEDED') OR NEW.version<>1
  OR ROW(NEW.id,NEW.task_id,NEW.order_id,NEW.assigned_generation,NEW.driver_id,NEW.main_vehicle_id,NEW.trailer_id,NEW.driver_snapshot::text,NEW.main_vehicle_snapshot::text,NEW.trailer_snapshot::text,NEW.source_snapshot::text,NEW.policy_mode,NEW.assigned_by,NEW.assigned_at,NEW.assignment_reason,NEW.previous_assignment_id)
   IS DISTINCT FROM ROW(OLD.id,OLD.task_id,OLD.order_id,OLD.assigned_generation,OLD.driver_id,OLD.main_vehicle_id,OLD.trailer_id,OLD.driver_snapshot::text,OLD.main_vehicle_snapshot::text,OLD.trailer_snapshot::text,OLD.source_snapshot::text,OLD.policy_mode,OLD.assigned_by,OLD.assigned_at,OLD.assignment_reason,OLD.previous_assignment_id)
 THEN RAISE EXCEPTION 'Dispatch source snapshots and terminal assignments are immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER tms_assignment_protected BEFORE UPDATE OR DELETE ON logistics.tms_assignment FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_assignment();
CREATE FUNCTION logistics.protect_tms_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Resource reservations cannot be deleted'; END IF;
 IF OLD.released_at IS NOT NULL OR NEW.released_at IS NULL OR NEW.release_reason IS NULL
  OR ROW(NEW.id,NEW.assignment_id,NEW.driver_id,NEW.vehicle_id,NEW.position,NEW.acquired_at) IS DISTINCT FROM ROW(OLD.id,OLD.assignment_id,OLD.driver_id,OLD.vehicle_id,OLD.position,OLD.acquired_at)
 THEN RAISE EXCEPTION 'Reservation source and released history are immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER tms_reservation_protected BEFORE UPDATE OR DELETE ON logistics.tms_resource_reservation FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_reservation();
CREATE FUNCTION logistics.protect_tms_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Dispatch events are immutable'; END; $$;
CREATE TRIGGER tms_event_immutable BEFORE UPDATE OR DELETE ON logistics.tms_dispatch_event FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_event();
-- No dispatch seeds, qualification policy, departure, completion, cleanup or release automation.
