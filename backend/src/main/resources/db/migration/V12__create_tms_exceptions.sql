-- Separate exception/supplement facts; no backfill, waiver, progress or release mutation.
ALTER TABLE logistics.tms_execution_evidence DROP CONSTRAINT tms_execution_evidence_phase_check;
ALTER TABLE logistics.tms_execution_evidence ADD CONSTRAINT tms_execution_evidence_phase_check
 CHECK(phase IN ('DEPARTED','IN_PROGRESS','AWAITING_COMPLETION'));
CREATE TABLE logistics.tms_exception (
 id uuid PRIMARY KEY, assignment_id uuid NOT NULL, task_id uuid NOT NULL, driver_id uuid NOT NULL,
 task_no varchar(100) NOT NULL, reported_phase varchar(32) NOT NULL CHECK(reported_phase IN ('DEPARTED','IN_PROGRESS','AWAITING_COMPLETION')),
 reported_execution_version bigint NOT NULL CHECK(reported_execution_version BETWEEN 1 AND 9007199254740991),
 description bytea NOT NULL, source_snapshot json NOT NULL, driver_snapshot json NOT NULL,
 policy_mode varchar(32) NOT NULL CHECK(policy_mode IN ('UNCONFIRMED','SYNTHETIC_TEST_ONLY')),
 actor_id uuid NOT NULL CONSTRAINT tms_exception_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 reported_at timestamptz(6) NOT NULL, status varchar(24) NOT NULL CHECK(status IN ('OPEN','NEEDS_SUPPLEMENT','RESOLVED')),
 version bigint NOT NULL CHECK(version BETWEEN 0 AND 9007199254740991), active_request_id uuid,
 resolved_at timestamptz(6), resolution_note bytea,
 FOREIGN KEY(assignment_id,task_id,driver_id) REFERENCES logistics.tms_assignment(id,task_id,driver_id),
 UNIQUE(id,assignment_id), UNIQUE(id,assignment_id,task_id,driver_id),
 CHECK((status='NEEDS_SUPPLEMENT')=(active_request_id IS NOT NULL)),
 CHECK((status='RESOLVED' AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL)
    OR(status<>'RESOLVED' AND resolved_at IS NULL AND resolution_note IS NULL))
);
CREATE INDEX tms_exception_page_idx ON logistics.tms_exception(reported_at DESC,id);
CREATE INDEX tms_exception_assignment_idx ON logistics.tms_exception(assignment_id,id);
CREATE TABLE logistics.tms_exception_supplement_request (
 id uuid PRIMARY KEY, exception_id uuid NOT NULL, assignment_id uuid NOT NULL,
 requested_version bigint NOT NULL CHECK(requested_version BETWEEN 1 AND 9007199254740991), reason bytea NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tms_exception_request_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 requested_at timestamptz(6) NOT NULL, policy_snapshot json NOT NULL,
 FOREIGN KEY(exception_id,assignment_id) REFERENCES logistics.tms_exception(id,assignment_id), UNIQUE(id,exception_id,assignment_id)
);
ALTER TABLE logistics.tms_exception ADD CONSTRAINT tms_exception_active_request_fk
 FOREIGN KEY(active_request_id,id,assignment_id) REFERENCES logistics.tms_exception_supplement_request(id,exception_id,assignment_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE logistics.tms_exception_event (
 id uuid PRIMARY KEY, exception_id uuid NOT NULL, assignment_id uuid NOT NULL,
 type varchar(40) NOT NULL CHECK(type IN ('REPORTED','SUPPLEMENT_REQUESTED','SUPPLEMENT_EVIDENCE_UPLOADED','SUPPLEMENT_SUBMITTED','RESOLVED')),
 from_status varchar(24), to_status varchar(24) NOT NULL,
 from_version bigint, to_version bigint NOT NULL CHECK(to_version BETWEEN 0 AND 9007199254740991),
 request_id uuid, note bytea,
 actor_id uuid NOT NULL CONSTRAINT tms_exception_event_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 actor_role varchar(16) NOT NULL CHECK(actor_role IN ('ADMIN','DISPATCHER','DRIVER')), actor_display_name bytea NOT NULL,
 occurred_at timestamptz(6) NOT NULL, snapshot json NOT NULL,
 FOREIGN KEY(exception_id,assignment_id) REFERENCES logistics.tms_exception(id,assignment_id),
 FOREIGN KEY(request_id,exception_id,assignment_id) REFERENCES logistics.tms_exception_supplement_request(id,exception_id,assignment_id),
 UNIQUE(exception_id,to_version), UNIQUE(id,exception_id,assignment_id,request_id,type),
 CHECK(((type='REPORTED' AND from_status IS NULL AND from_version IS NULL AND to_status='OPEN' AND to_version=0 AND request_id IS NULL AND note IS NOT NULL)
 OR(type='SUPPLEMENT_REQUESTED' AND from_status='OPEN' AND to_status='NEEDS_SUPPLEMENT' AND from_version>=0 AND to_version=from_version+1 AND request_id IS NOT NULL AND note IS NOT NULL)
 OR(type='SUPPLEMENT_EVIDENCE_UPLOADED' AND from_status='NEEDS_SUPPLEMENT' AND to_status='NEEDS_SUPPLEMENT' AND from_version>=0 AND to_version=from_version+1 AND request_id IS NOT NULL AND note IS NULL)
 OR(type='SUPPLEMENT_SUBMITTED' AND from_status='NEEDS_SUPPLEMENT' AND to_status='OPEN' AND from_version>=0 AND to_version=from_version+1 AND request_id IS NOT NULL AND note IS NOT NULL)
 OR(type='RESOLVED' AND from_status='OPEN' AND to_status='RESOLVED' AND from_version>=0 AND to_version=from_version+1 AND request_id IS NULL AND note IS NOT NULL)) IS TRUE)
);
CREATE UNIQUE INDEX tms_exception_one_submission ON logistics.tms_exception_event(request_id) WHERE type='SUPPLEMENT_SUBMITTED';
CREATE TABLE logistics.tms_exception_supplement_evidence (
 id uuid PRIMARY KEY, exception_id uuid NOT NULL, assignment_id uuid NOT NULL, request_id uuid NOT NULL,
 evidence_id uuid NOT NULL UNIQUE, event_id uuid NOT NULL UNIQUE,
 event_type varchar(40) NOT NULL DEFAULT 'SUPPLEMENT_EVIDENCE_UPLOADED' CHECK(event_type='SUPPLEMENT_EVIDENCE_UPLOADED'),
 FOREIGN KEY(request_id,exception_id,assignment_id) REFERENCES logistics.tms_exception_supplement_request(id,exception_id,assignment_id),
 FOREIGN KEY(evidence_id,assignment_id) REFERENCES logistics.tms_execution_evidence(id,assignment_id),
 FOREIGN KEY(event_id,exception_id,assignment_id,request_id,event_type) REFERENCES logistics.tms_exception_event(id,exception_id,assignment_id,request_id,type),
 UNIQUE(evidence_id,exception_id,assignment_id,request_id)
);
CREATE TABLE logistics.tms_exception_event_evidence (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, exception_id uuid NOT NULL, assignment_id uuid NOT NULL, request_id uuid NOT NULL, evidence_id uuid NOT NULL,
 event_type varchar(40) NOT NULL DEFAULT 'SUPPLEMENT_SUBMITTED' CHECK(event_type='SUPPLEMENT_SUBMITTED'),
 input_position integer NOT NULL CHECK(input_position BETWEEN 0 AND 31),
 FOREIGN KEY(event_id,exception_id,assignment_id,request_id,event_type) REFERENCES logistics.tms_exception_event(id,exception_id,assignment_id,request_id,type),
 FOREIGN KEY(evidence_id,exception_id,assignment_id,request_id) REFERENCES logistics.tms_exception_supplement_evidence(evidence_id,exception_id,assignment_id,request_id),
 UNIQUE(event_id,evidence_id), UNIQUE(event_id,input_position)
);
CREATE TABLE logistics.tms_exception_command (
 id uuid PRIMARY KEY, company_scope varchar(64) NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tms_exception_command_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 exception_id uuid NOT NULL, assignment_id uuid NOT NULL, task_id uuid NOT NULL, driver_id uuid NOT NULL, request_id uuid,
 http_method varchar(8) NOT NULL CHECK(http_method='POST'), canonical_path varchar(300) NOT NULL,
 idempotency_key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL,
 original_status integer NOT NULL CHECK(original_status=200), result_type varchar(16) NOT NULL CHECK(result_type IN ('RECORD','UPLOAD')),
 original_data json NOT NULL, created_at timestamptz(6) NOT NULL,
 FOREIGN KEY(exception_id,assignment_id,task_id,driver_id) REFERENCES logistics.tms_exception(id,assignment_id,task_id,driver_id),
 FOREIGN KEY(request_id,exception_id,assignment_id) REFERENCES logistics.tms_exception_supplement_request(id,exception_id,assignment_id),
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key)
);
CREATE FUNCTION logistics.protect_tms_exception() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'OPEN' OR NEW.version<>0 THEN RAISE EXCEPTION 'Exceptions begin with the original open report'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Exception reports cannot be deleted'; END IF;
 IF ROW(NEW.id,NEW.assignment_id,NEW.task_id,NEW.driver_id,NEW.task_no,NEW.reported_phase,NEW.reported_execution_version,NEW.description,NEW.source_snapshot::text,NEW.driver_snapshot::text,NEW.policy_mode,NEW.actor_id,NEW.reported_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.assignment_id,OLD.task_id,OLD.driver_id,OLD.task_no,OLD.reported_phase,OLD.reported_execution_version,OLD.description,OLD.source_snapshot::text,OLD.driver_snapshot::text,OLD.policy_mode,OLD.actor_id,OLD.reported_at)
 OR NEW.version<>OLD.version+1
 OR NOT ((OLD.status='OPEN' AND NEW.status='NEEDS_SUPPLEMENT') OR (OLD.status='OPEN' AND NEW.status='RESOLVED')
    OR (OLD.status='NEEDS_SUPPLEMENT' AND NEW.status='OPEN')
    OR (OLD.status='NEEDS_SUPPLEMENT' AND NEW.status='NEEDS_SUPPLEMENT' AND NEW.active_request_id=OLD.active_request_id))
 THEN RAISE EXCEPTION 'Exception report and controlled state transitions are protected'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER tms_exception_protected BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_exception
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_exception();
CREATE FUNCTION logistics.protect_tms_exception_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Exception history, requests, photos and receipts are immutable'; END; $$;
CREATE TRIGGER tms_exception_event_immutable BEFORE UPDATE OR DELETE ON logistics.tms_exception_event FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_exception_history();
CREATE TRIGGER tms_exception_command_immutable BEFORE UPDATE OR DELETE ON logistics.tms_exception_command FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_exception_history();
CREATE TRIGGER tms_exception_request_immutable BEFORE UPDATE OR DELETE ON logistics.tms_exception_supplement_request FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_exception_history();
CREATE TRIGGER tms_exception_supplement_evidence_immutable BEFORE UPDATE OR DELETE ON logistics.tms_exception_supplement_evidence FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_exception_history();
CREATE TRIGGER tms_exception_event_evidence_immutable BEFORE UPDATE OR DELETE ON logistics.tms_exception_event_evidence FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_exception_history();
