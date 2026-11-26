-- No terminal backfill: the old enum reserved COMPLETED, but no authorized writer produced it.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM logistics.tms_execution WHERE phase='COMPLETED') THEN
  RAISE EXCEPTION 'Unwitnessed terminal execution prevents this migration' USING ERRCODE='23503';
 END IF;
END; $$;
ALTER TABLE logistics.tms_resource_reservation ADD COLUMN completion_id uuid;
ALTER TABLE logistics.tms_task_dispatch ADD CONSTRAINT tms_task_completion_identity UNIQUE(task_id,order_id,source_order_version);
ALTER TABLE logistics.tms_execution ADD CONSTRAINT tms_execution_terminal_identity UNIQUE(assignment_id,version,phase);
ALTER TABLE logistics.tms_execution_event ADD CONSTRAINT tms_execution_transition_identity UNIQUE(id,assignment_id,from_phase,to_phase,from_version,to_version);
ALTER TABLE logistics.tms_execution_command ADD CONSTRAINT tms_execution_receipt_identity UNIQUE(id,assignment_id,task_id,driver_id);

CREATE TABLE logistics.tms_completion (
 id uuid PRIMARY KEY, assignment_id uuid NOT NULL UNIQUE, task_id uuid NOT NULL, order_id uuid NOT NULL,
 driver_id uuid NOT NULL, main_vehicle_id uuid NOT NULL REFERENCES logistics.fms_vehicle(id), trailer_id uuid REFERENCES logistics.fms_vehicle(id),
 source_order_version bigint NOT NULL,
 from_execution_version bigint NOT NULL CHECK(from_execution_version BETWEEN 3 AND 9007199254740990),
 to_execution_version bigint NOT NULL CHECK(to_execution_version=from_execution_version+1),
 terminal_phase varchar(32) NOT NULL CHECK(terminal_phase='COMPLETED'),
 source_event_id uuid NOT NULL, source_command_id uuid NOT NULL,
 source_from_phase varchar(32) NOT NULL CHECK(source_from_phase='IN_PROGRESS'),
 source_to_phase varchar(32) NOT NULL CHECK(source_to_phase='AWAITING_COMPLETION'),
 source_from_version bigint NOT NULL CHECK(source_from_version=from_execution_version-1),
 completion_event_id uuid NOT NULL UNIQUE,
 completion_from_phase varchar(32) NOT NULL CHECK(completion_from_phase='AWAITING_COMPLETION'),
 completion_to_phase varchar(32) NOT NULL CHECK(completion_to_phase='COMPLETED'),
 evidence_ids uuid[] NOT NULL CHECK(cardinality(evidence_ids) BETWEEN 1 AND 32),
 actor_id uuid NOT NULL CONSTRAINT tms_completion_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 completed_at timestamptz(6) NOT NULL, released_at timestamptz(6) NOT NULL CHECK(released_at=completed_at),
 safe_release_confirmed boolean NOT NULL CHECK(safe_release_confirmed),
 policy_mode varchar(32) NOT NULL CHECK(policy_mode='SYNTHETIC_TEST_ONLY'), policy_snapshot json NOT NULL,
 receipt_id uuid NOT NULL UNIQUE,
 UNIQUE(id,assignment_id), UNIQUE(id,assignment_id,task_id,driver_id,actor_id,completed_at),
 FOREIGN KEY(assignment_id,task_id,driver_id) REFERENCES logistics.tms_assignment(id,task_id,driver_id),
 FOREIGN KEY(task_id,order_id,source_order_version) REFERENCES logistics.tms_task_dispatch(task_id,order_id,source_order_version),
 FOREIGN KEY(assignment_id,to_execution_version,terminal_phase) REFERENCES logistics.tms_execution(assignment_id,version,phase) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(source_command_id,assignment_id,task_id,driver_id) REFERENCES logistics.tms_execution_command(id,assignment_id,task_id,driver_id),
 FOREIGN KEY(source_event_id,assignment_id,source_from_phase,source_to_phase,source_from_version,from_execution_version)
  REFERENCES logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version),
 FOREIGN KEY(completion_event_id,assignment_id,completion_from_phase,completion_to_phase,from_execution_version,to_execution_version)
  REFERENCES logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE logistics.tms_completion_command (
 id uuid PRIMARY KEY, completion_id uuid NOT NULL UNIQUE, company_scope varchar(64) NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tms_completion_command_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 driver_id uuid NOT NULL, assignment_id uuid NOT NULL, task_id uuid NOT NULL,
 http_method varchar(8) NOT NULL CHECK(http_method='POST'), canonical_path varchar(200) NOT NULL,
 idempotency_key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL CHECK(request_hash~'^[0-9a-f]{64}$'),
 original_status integer NOT NULL CHECK(original_status=200), original_data json NOT NULL, created_at timestamptz(6) NOT NULL,
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key),
 UNIQUE(id,completion_id,assignment_id,task_id,driver_id,actor_id,created_at),
 FOREIGN KEY(completion_id,assignment_id,task_id,driver_id,actor_id,created_at)
  REFERENCES logistics.tms_completion(id,assignment_id,task_id,driver_id,actor_id,completed_at) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE logistics.tms_completion ADD CONSTRAINT tms_completion_success_receipt_fk
 FOREIGN KEY(receipt_id,id,assignment_id,task_id,driver_id,actor_id,completed_at)
 REFERENCES logistics.tms_completion_command(id,completion_id,assignment_id,task_id,driver_id,actor_id,created_at) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE logistics.tms_resource_reservation ADD CONSTRAINT tms_reservation_completion_fk
 FOREIGN KEY(completion_id,assignment_id) REFERENCES logistics.tms_completion(id,assignment_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE logistics.tms_resource_reservation ADD CONSTRAINT tms_reservation_completion_release CHECK(completion_id IS NULL OR(released_at IS NULL AND release_reason IS NULL));
DROP INDEX logistics.tms_reservation_active_resource;
CREATE UNIQUE INDEX tms_reservation_active_resource ON logistics.tms_resource_reservation(resource_kind,resource_id) WHERE released_at IS NULL AND completion_id IS NULL;

-- The original exact three-field progress receipt seals its selected set, not just individual rows.
CREATE FUNCTION logistics.tms_progress_success(event_uuid uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE receipt_uuid uuid;
BEGIN
 SELECT c.id INTO receipt_uuid FROM logistics.tms_execution_event e
 JOIN logistics.tms_assignment a ON a.id=e.assignment_id
 JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id
 JOIN logistics.tms_execution_command c ON c.assignment_id=a.id AND c.task_id=a.task_id AND c.driver_id=a.driver_id AND c.actor_id=e.actor_id
 WHERE e.id=event_uuid AND e.to_phase IN('IN_PROGRESS','AWAITING_COMPLETION')
  AND c.company_scope=t.company_scope AND c.http_method='POST' AND c.original_status=200
  AND c.canonical_path='/api/v1/tms/my-tasks/'||a.id::text||'/progress'
  AND c.original_data->>'assignmentId'=a.id::text AND c.original_data->>'taskId'=a.task_id::text
  AND c.original_data->'execution'->>'phase'=e.to_phase
  AND c.original_data->'execution'->>'version'=e.to_version::text
  AND c.created_at=e.occurred_at
 ORDER BY c.id LIMIT 1;
 RETURN receipt_uuid;
END; $$;
CREATE FUNCTION logistics.seal_tms_progress_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target logistics.tms_execution_event%ROWTYPE;
BEGIN
 PERFORM id FROM logistics.tms_assignment WHERE id=NEW.assignment_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Evidence transition identity is protected' USING ERRCODE='23503'; END IF;
 -- Progress changes this tuple: stale repeatable-read snapshots fail instead of hiding a success receipt.
 PERFORM assignment_id FROM logistics.tms_execution WHERE assignment_id=NEW.assignment_id FOR UPDATE;
 SELECT * INTO target FROM logistics.tms_execution_event WHERE id=NEW.event_id;
 IF NOT FOUND OR target.assignment_id<>NEW.assignment_id OR target.from_phase<>NEW.from_phase OR target.from_version<>NEW.from_version
  OR target.to_phase NOT IN('IN_PROGRESS','AWAITING_COMPLETION') OR logistics.tms_progress_success(target.id) IS NOT NULL
 THEN RAISE EXCEPTION 'Successful progress evidence sets are sealed' USING ERRCODE='23503'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER tms_progress_evidence_sealed BEFORE INSERT ON logistics.tms_execution_event_evidence FOR EACH ROW EXECUTE FUNCTION logistics.seal_tms_progress_evidence();

CREATE OR REPLACE FUNCTION logistics.protect_tms_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  PERFORM id FROM logistics.tms_assignment WHERE id=NEW.assignment_id FOR UPDATE;
  PERFORM id FROM logistics.tms_resource_reservation WHERE assignment_id=NEW.assignment_id ORDER BY position,id FOR UPDATE;
  IF NEW.completion_id IS NOT NULL OR EXISTS(SELECT 1 FROM logistics.tms_execution WHERE assignment_id=NEW.assignment_id AND phase='COMPLETED')
   OR EXISTS(SELECT 1 FROM logistics.tms_completion WHERE assignment_id=NEW.assignment_id)
  THEN RAISE EXCEPTION 'Completed reservation sets are sealed' USING ERRCODE='23503'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Resource reservations cannot be deleted'; END IF;
 IF OLD.released_at IS NOT NULL OR OLD.completion_id IS NOT NULL
  OR ROW(NEW.id,NEW.assignment_id,NEW.driver_id,NEW.vehicle_id,NEW.position,NEW.acquired_at) IS DISTINCT FROM ROW(OLD.id,OLD.assignment_id,OLD.driver_id,OLD.vehicle_id,OLD.position,OLD.acquired_at)
 THEN RAISE EXCEPTION 'Reservation source and terminal history are immutable' USING ERRCODE='23503'; END IF;
 IF NEW.completion_id IS NOT NULL THEN
  IF NEW.released_at IS NOT NULL OR NEW.release_reason IS NOT NULL THEN RAISE EXCEPTION 'Completion cannot rewrite release history' USING ERRCODE='23503'; END IF;
 ELSIF NEW.released_at IS NULL OR NEW.release_reason IS NULL THEN
  RAISE EXCEPTION 'Reservation release requires its original terminal fields' USING ERRCODE='23503';
 END IF;
 RETURN NEW;
END; $$;
DROP TRIGGER tms_reservation_protected ON logistics.tms_resource_reservation;
CREATE TRIGGER tms_reservation_protected BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_resource_reservation FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_reservation();

CREATE FUNCTION logistics.validate_tms_completion(completion_uuid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE w logistics.tms_completion%ROWTYPE; a logistics.tms_assignment%ROWTYPE; x logistics.tms_execution%ROWTYPE;
 event_row logistics.tms_execution_event%ROWTYPE; receipt logistics.tms_completion_command%ROWTYPE;
 selected uuid[]; n integer;
BEGIN
 SELECT * INTO w FROM logistics.tms_completion WHERE id=completion_uuid;
 IF NOT FOUND THEN RAISE EXCEPTION 'Completion witness is required' USING ERRCODE='23503'; END IF;
 SELECT * INTO a FROM logistics.tms_assignment WHERE id=w.assignment_id;
 SELECT * INTO x FROM logistics.tms_execution WHERE assignment_id=w.assignment_id;
 IF a.id IS NULL OR x.assignment_id IS NULL OR a.status<>'ASSIGNED' OR a.version<>0 OR a.task_id<>w.task_id OR a.order_id<>w.order_id
  OR a.driver_id<>w.driver_id OR a.main_vehicle_id<>w.main_vehicle_id OR a.trailer_id IS DISTINCT FROM w.trailer_id
  OR x.phase<>'COMPLETED' OR x.version<>w.to_execution_version OR x.updated_at<w.completed_at OR w.completed_at<x.departed_at
 THEN RAISE EXCEPTION 'Completion source identity is protected' USING ERRCODE='23503'; END IF;
 SELECT * INTO event_row FROM logistics.tms_execution_event WHERE id=w.completion_event_id;
 IF event_row.id IS NULL OR event_row.actor_id<>w.actor_id OR event_row.occurred_at<>w.completed_at
 THEN RAISE EXCEPTION 'Completion event identity is protected' USING ERRCODE='23503'; END IF;
 IF logistics.tms_progress_success(w.source_event_id) IS DISTINCT FROM w.source_command_id THEN
  RAISE EXCEPTION 'Original successful progress proof is required' USING ERRCODE='23503'; END IF;
 SELECT array_agg(evidence_id ORDER BY input_position),count(*) INTO selected,n FROM logistics.tms_execution_event_evidence WHERE event_id=w.source_event_id;
 IF n NOT BETWEEN 1 AND 32 OR selected IS DISTINCT FROM w.evidence_ids
  OR selected IS DISTINCT FROM (SELECT array_agg(id ORDER BY id::text) FROM unnest(selected) id)
  OR (SELECT min(input_position)<>0 OR max(input_position)<>n-1 FROM logistics.tms_execution_event_evidence WHERE event_id=w.source_event_id)
 THEN RAISE EXCEPTION 'Original selected evidence set is protected' USING ERRCODE='23503'; END IF;
 SELECT count(*) INTO n FROM logistics.tms_resource_reservation WHERE assignment_id=w.assignment_id;
 IF n<>(CASE WHEN w.trailer_id IS NULL THEN 2 ELSE 3 END) OR EXISTS(
  SELECT 1 FROM logistics.tms_resource_reservation r WHERE r.assignment_id=w.assignment_id AND
   (r.completion_id IS DISTINCT FROM w.id OR r.released_at IS NOT NULL OR r.release_reason IS NOT NULL OR NOT
    ((r.position='DRIVER' AND r.driver_id=w.driver_id AND r.vehicle_id IS NULL)
     OR(r.position='MAIN' AND r.vehicle_id=w.main_vehicle_id AND r.driver_id IS NULL)
     OR(r.position='TRAILER' AND w.trailer_id IS NOT NULL AND r.vehicle_id=w.trailer_id AND r.driver_id IS NULL))))
 THEN RAISE EXCEPTION 'Complete typed reservation set is required' USING ERRCODE='23503'; END IF;
 SELECT * INTO receipt FROM logistics.tms_completion_command WHERE id=w.receipt_id;
 IF receipt.id IS NULL OR receipt.completion_id<>w.id OR receipt.company_scope<>'SINGLE_COMPANY'
  OR receipt.canonical_path<>'/api/v1/tms/my-tasks/'||w.assignment_id::text||'/complete'
  OR receipt.original_data->>'assignmentId' IS DISTINCT FROM w.assignment_id::text
  OR receipt.original_data->>'taskId' IS DISTINCT FROM w.task_id::text
  OR receipt.original_data->'execution'->>'phase' IS DISTINCT FROM 'COMPLETED'
  OR receipt.original_data->'execution'->>'version' IS DISTINCT FROM w.to_execution_version::text
  OR receipt.original_data->'completion'->>'id' IS DISTINCT FROM w.id::text
  OR receipt.original_data::text IS DISTINCT FROM event_row.snapshot::text
 THEN RAISE EXCEPTION 'Original successful completion receipt is required' USING ERRCODE='23503'; END IF;
END; $$;
CREATE FUNCTION logistics.check_tms_completion_proof() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE completion_uuid uuid;
BEGIN
 IF TG_TABLE_NAME='tms_completion' THEN completion_uuid:=NEW.id;
 ELSIF TG_TABLE_NAME='tms_completion_command' THEN completion_uuid:=NEW.completion_id;
 ELSIF TG_TABLE_NAME='tms_resource_reservation' THEN
  SELECT id INTO completion_uuid FROM logistics.tms_completion WHERE assignment_id=NEW.assignment_id;
  IF completion_uuid IS NULL THEN
   IF NEW.completion_id IS NOT NULL THEN RAISE EXCEPTION 'Reservation completion witness is required' USING ERRCODE='23503'; END IF;
   RETURN NULL;
  END IF;
 ELSIF TG_TABLE_NAME='tms_execution' THEN
  IF NEW.phase<>'COMPLETED' THEN RETURN NULL; END IF;
  SELECT id INTO completion_uuid FROM logistics.tms_completion WHERE assignment_id=NEW.assignment_id;
 ELSIF TG_TABLE_NAME='tms_execution_event' THEN
  IF NEW.to_phase<>'COMPLETED' THEN RETURN NULL; END IF;
  SELECT id INTO completion_uuid FROM logistics.tms_completion WHERE assignment_id=NEW.assignment_id AND completion_event_id=NEW.id;
 END IF;
 PERFORM logistics.validate_tms_completion(completion_uuid);
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER tms_completion_proof AFTER INSERT ON logistics.tms_completion DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_tms_completion_proof();
CREATE CONSTRAINT TRIGGER tms_completion_command_proof AFTER INSERT ON logistics.tms_completion_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_tms_completion_proof();
CREATE CONSTRAINT TRIGGER tms_completion_reservation_proof AFTER INSERT OR UPDATE ON logistics.tms_resource_reservation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_tms_completion_proof();
CREATE CONSTRAINT TRIGGER tms_completion_execution_proof AFTER INSERT OR UPDATE ON logistics.tms_execution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_tms_completion_proof();
CREATE CONSTRAINT TRIGGER tms_completion_event_proof AFTER INSERT ON logistics.tms_execution_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_tms_completion_proof();
CREATE FUNCTION logistics.protect_tms_completion_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Completion witnesses and original receipts are immutable' USING ERRCODE='23503'; END; $$;
CREATE TRIGGER tms_completion_immutable BEFORE UPDATE OR DELETE ON logistics.tms_completion FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_completion_history();
CREATE TRIGGER tms_completion_command_immutable BEFORE UPDATE OR DELETE ON logistics.tms_completion_command FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_completion_history();
