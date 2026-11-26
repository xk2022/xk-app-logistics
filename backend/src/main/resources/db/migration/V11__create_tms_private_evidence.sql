-- Empty evidence infrastructure only. The normal application has no approved provider or policy.
CREATE TABLE logistics.tms_execution_evidence_object (
 id uuid PRIMARY KEY, content bytea NOT NULL,
 byte_size bigint NOT NULL CHECK(byte_size BETWEEN 1 AND 1048576),
 sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 CHECK(octet_length(content)=byte_size), CHECK(encode(sha256(content),'hex')=sha256), UNIQUE(id,byte_size,sha256)
);
CREATE TABLE logistics.tms_execution_evidence (
 id uuid PRIMARY KEY, assignment_id uuid NOT NULL REFERENCES logistics.tms_execution(assignment_id),
 task_id uuid NOT NULL REFERENCES logistics.tms_task_dispatch(task_id), driver_id uuid NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tms_evidence_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 phase varchar(32) NOT NULL CHECK(phase IN ('DEPARTED','IN_PROGRESS')),
 execution_version bigint NOT NULL CHECK(execution_version BETWEEN 1 AND 9007199254740991),
 media_type varchar(64) NOT NULL CHECK(media_type='image/png'),
 byte_size bigint NOT NULL CHECK(byte_size BETWEEN 1 AND 1048576),
 sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 uploaded_at timestamptz(6) NOT NULL, status varchar(16) NOT NULL CHECK(status='VERIFIED'),
 object_id uuid NOT NULL UNIQUE, policy_snapshot json NOT NULL,
 UNIQUE(id,assignment_id), UNIQUE(id,assignment_id,phase,execution_version),
 FOREIGN KEY(assignment_id,task_id,driver_id) REFERENCES logistics.tms_assignment(id,task_id,driver_id),
 FOREIGN KEY(object_id,byte_size,sha256) REFERENCES logistics.tms_execution_evidence_object(id,byte_size,sha256)
);
ALTER TABLE logistics.tms_execution_evidence_object ADD CONSTRAINT tms_evidence_object_metadata_fk
 FOREIGN KEY(id) REFERENCES logistics.tms_execution_evidence(object_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX tms_evidence_page_idx ON logistics.tms_execution_evidence(assignment_id,uploaded_at,id);
ALTER TABLE logistics.tms_execution_event ADD CONSTRAINT tms_execution_event_assignment_identity UNIQUE(id,assignment_id);
ALTER TABLE logistics.tms_execution_event ADD CONSTRAINT tms_execution_event_evidence_identity UNIQUE(id,assignment_id,from_phase,from_version);
CREATE TABLE logistics.tms_execution_event_evidence (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, evidence_id uuid NOT NULL, assignment_id uuid NOT NULL,
 input_position integer NOT NULL CHECK(input_position BETWEEN 0 AND 31),
 from_phase varchar(32) NOT NULL, from_version bigint NOT NULL,
 FOREIGN KEY(event_id,assignment_id,from_phase,from_version) REFERENCES logistics.tms_execution_event(id,assignment_id,from_phase,from_version),
 FOREIGN KEY(evidence_id,assignment_id,from_phase,from_version) REFERENCES logistics.tms_execution_evidence(id,assignment_id,phase,execution_version),
 UNIQUE(event_id,evidence_id), UNIQUE(event_id,input_position)
);
CREATE TABLE logistics.tms_execution_evidence_command (
 id uuid PRIMARY KEY, company_scope varchar(64) NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tms_evidence_command_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 driver_id uuid NOT NULL, assignment_id uuid NOT NULL, task_id uuid NOT NULL,
 evidence_id uuid NOT NULL,
 http_method varchar(8) NOT NULL CHECK(http_method='POST'), canonical_path varchar(200) NOT NULL,
 idempotency_key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL,
 original_status integer NOT NULL CHECK(original_status=200), original_data json NOT NULL, created_at timestamptz(6) NOT NULL,
 FOREIGN KEY(assignment_id,task_id,driver_id) REFERENCES logistics.tms_assignment(id,task_id,driver_id),
 FOREIGN KEY(evidence_id,assignment_id) REFERENCES logistics.tms_execution_evidence(id,assignment_id),
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key)
);
CREATE FUNCTION logistics.protect_tms_evidence_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Private evidence and its original receipts are immutable'; END; $$;
CREATE TRIGGER tms_evidence_object_immutable BEFORE UPDATE OR DELETE ON logistics.tms_execution_evidence_object
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_evidence_history();
CREATE TRIGGER tms_evidence_immutable BEFORE UPDATE OR DELETE ON logistics.tms_execution_evidence
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_evidence_history();
CREATE TRIGGER tms_event_evidence_immutable BEFORE UPDATE OR DELETE ON logistics.tms_execution_event_evidence
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_evidence_history();
CREATE TRIGGER tms_evidence_command_immutable BEFORE UPDATE OR DELETE ON logistics.tms_execution_evidence_command
 FOR EACH ROW EXECUTE FUNCTION logistics.protect_tms_evidence_history();
