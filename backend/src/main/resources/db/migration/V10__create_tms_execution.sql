-- No backfill: WAITING/version zero is a virtual view, never an invented departure fact.
ALTER TABLE logistics.tms_assignment ADD CONSTRAINT tms_assignment_execution_identity UNIQUE(id,task_id,driver_id);
CREATE TABLE logistics.tms_execution (
 assignment_id uuid PRIMARY KEY REFERENCES logistics.tms_assignment(id),
 phase varchar(32) NOT NULL CHECK(phase IN ('DEPARTED','IN_PROGRESS','AWAITING_COMPLETION','COMPLETED')),
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 departed_at timestamptz(6) NOT NULL, updated_at timestamptz(6) NOT NULL,
 CHECK(updated_at>=departed_at)
);
CREATE TABLE logistics.tms_execution_event (
 id uuid PRIMARY KEY,
 assignment_id uuid NOT NULL REFERENCES logistics.tms_execution(assignment_id),
 from_phase varchar(32) NOT NULL CHECK(from_phase IN ('WAITING','DEPARTED','IN_PROGRESS','AWAITING_COMPLETION','COMPLETED')),
 to_phase varchar(32) NOT NULL CHECK(to_phase IN ('DEPARTED','IN_PROGRESS','AWAITING_COMPLETION','COMPLETED')),
 from_version bigint NOT NULL CHECK(from_version BETWEEN 0 AND 9007199254740991),
 to_version bigint NOT NULL CHECK(to_version BETWEEN 1 AND 9007199254740991),
 actor_id uuid NOT NULL CONSTRAINT tms_execution_event_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 occurred_at timestamptz(6) NOT NULL, snapshot json NOT NULL,
 CHECK(to_version=from_version+1),
 CHECK((from_phase='WAITING' AND to_phase='DEPARTED' AND from_version=0)
    OR (from_phase='DEPARTED' AND to_phase='IN_PROGRESS' AND from_version>=1)
    OR (from_phase='IN_PROGRESS' AND to_phase='AWAITING_COMPLETION' AND from_version>=1)
    OR (from_phase='AWAITING_COMPLETION' AND to_phase='COMPLETED' AND from_version>=1)),
 UNIQUE(assignment_id,to_version)
);
CREATE TABLE logistics.tms_execution_command (
 id uuid PRIMARY KEY, company_scope varchar(64) NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tms_execution_command_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 driver_id uuid NOT NULL REFERENCES logistics.fms_driver(id),
 http_method varchar(8) NOT NULL CHECK(http_method='POST'), canonical_path varchar(200) NOT NULL,
 idempotency_key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL,
 assignment_id uuid NOT NULL REFERENCES logistics.tms_execution(assignment_id),
 task_id uuid NOT NULL REFERENCES logistics.tms_task_dispatch(task_id),
 original_status integer NOT NULL CHECK(original_status=200), original_data json NOT NULL,
 created_at timestamptz(6) NOT NULL,
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key),
 CONSTRAINT tms_execution_command_identity_fk FOREIGN KEY(assignment_id,task_id,driver_id)
  REFERENCES logistics.tms_assignment(id,task_id,driver_id)
);
CREATE FUNCTION logistics.protect_tms_execution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.phase<>'DEPARTED' OR NEW.version<>1 THEN RAISE EXCEPTION 'Execution must begin with its first departure'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Execution departure facts cannot be deleted'; END IF;
 IF ROW(NEW.assignment_id,NEW.departed_at) IS DISTINCT FROM ROW(OLD.assignment_id,OLD.departed_at)
  OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at
  OR NOT ((OLD.phase='DEPARTED' AND NEW.phase='IN_PROGRESS')
       OR (OLD.phase='IN_PROGRESS' AND NEW.phase='AWAITING_COMPLETION')
       OR (OLD.phase='AWAITING_COMPLETION' AND NEW.phase='COMPLETED'))
 THEN RAISE EXCEPTION 'Execution transitions and departure time are protected'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER tms_execution_protected BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_execution
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_execution();
CREATE FUNCTION logistics.protect_tms_execution_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Execution events and command receipts are immutable'; END; $$;
CREATE TRIGGER tms_execution_event_immutable BEFORE UPDATE OR DELETE ON logistics.tms_execution_event
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_execution_history();
CREATE TRIGGER tms_execution_command_immutable BEFORE UPDATE OR DELETE ON logistics.tms_execution_command
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_execution_history();
