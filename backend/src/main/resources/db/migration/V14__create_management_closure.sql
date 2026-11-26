-- New controlled audit only. Legacy CLOSED rows remain legal and are never backfilled.
ALTER TABLE logistics.tms_completion ADD CONSTRAINT tms_completion_management_identity UNIQUE(id,assignment_id,task_id,order_id,source_order_version,to_execution_version);
ALTER TABLE logistics.order_core ADD CONSTRAINT order_core_closure_identity UNIQUE(id,company_scope,lifecycle_status);
ALTER TABLE logistics.tom_order ADD CONSTRAINT tom_order_closure_identity UNIQUE(order_id,version,acceptance_status);
ALTER TABLE logistics.order_acceptance_event ADD CONSTRAINT order_acceptance_closure_identity UNIQUE(order_id,to_version);
CREATE TABLE logistics.tms_completion_confirmation (
 id uuid PRIMARY KEY, completion_id uuid NOT NULL UNIQUE, assignment_id uuid NOT NULL, task_id uuid NOT NULL, order_id uuid NOT NULL,
 source_order_version bigint NOT NULL, execution_version bigint NOT NULL CHECK(execution_version>=4), version bigint NOT NULL CHECK(version=1),
 actor_id uuid NOT NULL CONSTRAINT tms_confirmation_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 actor_role varchar(16) NOT NULL CHECK(actor_role IN('ADMIN','DISPATCHER')), actor_display_name bytea NOT NULL,
 confirmed_at timestamptz(6) NOT NULL, reason bytea NOT NULL, policy_mode varchar(32) NOT NULL CHECK(policy_mode='SYNTHETIC_TEST_ONLY'), policy_snapshot json NOT NULL,
 receipt_id uuid NOT NULL UNIQUE,
 UNIQUE(id,completion_id,assignment_id,task_id,order_id,source_order_version), UNIQUE(id,completion_id,actor_id,confirmed_at),
 FOREIGN KEY(completion_id,assignment_id,task_id,order_id,source_order_version,execution_version)
  REFERENCES logistics.tms_completion(id,assignment_id,task_id,order_id,source_order_version,to_execution_version)
);
CREATE TABLE logistics.tms_completion_confirmation_command (
 id uuid PRIMARY KEY, confirmation_id uuid NOT NULL UNIQUE, completion_id uuid NOT NULL,
 company_scope varchar(64) NOT NULL, actor_id uuid NOT NULL CONSTRAINT tms_confirmation_command_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 http_method varchar(8) NOT NULL CHECK(http_method='POST'), canonical_path varchar(200) NOT NULL, idempotency_key varchar(128) NOT NULL,
 request_hash varchar(64) NOT NULL, original_status integer NOT NULL CHECK(original_status=200), original_data json NOT NULL, created_at timestamptz(6) NOT NULL,
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key), UNIQUE(id,confirmation_id,completion_id,actor_id,created_at),
 FOREIGN KEY(confirmation_id,completion_id,actor_id,created_at) REFERENCES logistics.tms_completion_confirmation(id,completion_id,actor_id,confirmed_at) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE logistics.tms_completion_confirmation ADD CONSTRAINT tms_confirmation_receipt_fk FOREIGN KEY(receipt_id,id,completion_id,actor_id,confirmed_at)
 REFERENCES logistics.tms_completion_confirmation_command(id,confirmation_id,completion_id,actor_id,created_at) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE logistics.tom_order_closure (
 id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE, company_scope varchar(64) NOT NULL CHECK(company_scope='SINGLE_COMPANY'),
 source_version bigint NOT NULL CHECK(source_version BETWEEN 1 AND 9007199254740990), target_version bigint NOT NULL CHECK(target_version=source_version+1),
 lifecycle_status varchar(16) NOT NULL CHECK(lifecycle_status='CLOSED'), acceptance_status varchar(16) NOT NULL CHECK(acceptance_status='ACCEPTED'),
 closed_at timestamptz(6) NOT NULL, actor_id uuid NOT NULL CONSTRAINT tom_closure_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 actor_role varchar(16) NOT NULL CHECK(actor_role IN('ADMIN','DISPATCHER')), actor_display_name bytea NOT NULL, reason bytea NOT NULL,
 policy_mode varchar(32) NOT NULL CHECK(policy_mode='SYNTHETIC_TEST_ONLY'), manager_confirmed boolean NOT NULL CHECK(manager_confirmed),
 task_count bigint NOT NULL CHECK(task_count>=1), policy_snapshot json NOT NULL, receipt_id uuid NOT NULL UNIQUE,
 UNIQUE(id,order_id,source_version), UNIQUE(id,order_id,actor_id,closed_at),
 FOREIGN KEY(order_id,company_scope,lifecycle_status) REFERENCES logistics.order_core(id,company_scope,lifecycle_status) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(order_id,target_version,acceptance_status) REFERENCES logistics.tom_order(order_id,version,acceptance_status) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(order_id,source_version) REFERENCES logistics.order_acceptance_event(order_id,to_version),
 FOREIGN KEY(order_id,target_version) REFERENCES logistics.order_draft_revision(order_id,aggregate_version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE logistics.tom_order_closure_task (
 closure_id uuid NOT NULL, order_id uuid NOT NULL, source_version bigint NOT NULL, task_id uuid NOT NULL,
 required_task boolean CHECK(required_task IS NULL OR required_task), assignment_id uuid NOT NULL, completion_id uuid NOT NULL,
 execution_version bigint NOT NULL CHECK(execution_version>=4), confirmation_id uuid NOT NULL, confirmation_version bigint NOT NULL CHECK(confirmation_version=1),
 PRIMARY KEY(closure_id,task_id),
 FOREIGN KEY(closure_id,order_id,source_version) REFERENCES logistics.tom_order_closure(id,order_id,source_version),
 FOREIGN KEY(order_id,task_id) REFERENCES logistics.transport_task(order_id,id),
 FOREIGN KEY(completion_id,assignment_id,task_id,order_id,source_version,execution_version) REFERENCES logistics.tms_completion(id,assignment_id,task_id,order_id,source_order_version,to_execution_version),
 FOREIGN KEY(confirmation_id,completion_id,assignment_id,task_id,order_id,source_version) REFERENCES logistics.tms_completion_confirmation(id,completion_id,assignment_id,task_id,order_id,source_order_version)
);
CREATE TABLE logistics.tom_order_closure_command (
 id uuid PRIMARY KEY, closure_id uuid NOT NULL UNIQUE, order_id uuid NOT NULL, company_scope varchar(64) NOT NULL,
 actor_id uuid NOT NULL CONSTRAINT tom_closure_command_actor_fk REFERENCES logistics.upms_account(id) DEFERRABLE INITIALLY IMMEDIATE,
 http_method varchar(8) NOT NULL CHECK(http_method='POST'), canonical_path varchar(200) NOT NULL, idempotency_key varchar(128) NOT NULL,
 request_hash varchar(64) NOT NULL, original_status integer NOT NULL CHECK(original_status=200), original_data json NOT NULL, created_at timestamptz(6) NOT NULL,
 UNIQUE(company_scope,actor_id,http_method,canonical_path,idempotency_key), UNIQUE(id,closure_id,order_id,actor_id,created_at),
 FOREIGN KEY(closure_id,order_id,actor_id,created_at) REFERENCES logistics.tom_order_closure(id,order_id,actor_id,closed_at) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE logistics.tom_order_closure ADD CONSTRAINT tom_closure_receipt_fk FOREIGN KEY(receipt_id,id,order_id,actor_id,closed_at)
 REFERENCES logistics.tom_order_closure_command(id,closure_id,order_id,actor_id,created_at) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION logistics.protect_management_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Management confirmations and closure audit are immutable' USING ERRCODE='23503'; END; $$;
CREATE TRIGGER tms_confirmation_immutable BEFORE UPDATE OR DELETE ON logistics.tms_completion_confirmation FOR EACH ROW EXECUTE FUNCTION logistics.protect_management_audit();
CREATE TRIGGER tms_confirmation_command_immutable BEFORE UPDATE OR DELETE ON logistics.tms_completion_confirmation_command FOR EACH ROW EXECUTE FUNCTION logistics.protect_management_audit();
CREATE TRIGGER tom_closure_immutable BEFORE UPDATE OR DELETE ON logistics.tom_order_closure FOR EACH ROW EXECUTE FUNCTION logistics.protect_management_audit();
CREATE TRIGGER tom_closure_task_immutable BEFORE UPDATE OR DELETE ON logistics.tom_order_closure_task FOR EACH ROW EXECUTE FUNCTION logistics.protect_management_audit();
CREATE TRIGGER tom_closure_command_immutable BEFORE UPDATE OR DELETE ON logistics.tom_order_closure_command FOR EACH ROW EXECUTE FUNCTION logistics.protect_management_audit();

-- UPDATE/DELETE tuple locks precede BEFORE ROW. NOWAIT avoids waiting in reverse order.
CREATE FUNCTION logistics.guard_owned_closure_orders(order_ids uuid[],try_only boolean) RETURNS void LANGUAGE plpgsql AS $$
DECLARE order_uuid uuid;
BEGIN
 FOR order_uuid IN SELECT DISTINCT value FROM unnest(order_ids) value WHERE value IS NOT NULL ORDER BY value LOOP
  IF try_only THEN PERFORM order_id FROM logistics.tom_order WHERE order_id=order_uuid FOR UPDATE NOWAIT;
  ELSE PERFORM order_id FROM logistics.tom_order WHERE order_id=order_uuid FOR UPDATE; END IF;
  IF EXISTS(SELECT 1 FROM logistics.tom_order_closure WHERE order_id=order_uuid) THEN
   RAISE EXCEPTION 'Recorded closure source is sealed' USING ERRCODE='23503';
  END IF;
 END LOOP;
END; $$;
CREATE FUNCTION logistics.guard_recorded_closure_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE orders uuid[]:=ARRAY[]::uuid[]; assignments uuid[]:=ARRAY[]::uuid[]; tasks uuid[]:=ARRAY[]::uuid[]; order_uuid uuid;
BEGIN
 IF TG_TABLE_NAME='order_core' THEN
  IF TG_OP<>'INSERT' THEN orders:=array_append(orders,OLD.id); END IF;
  IF TG_OP<>'DELETE' THEN orders:=array_append(orders,NEW.id); END IF;
 ELSIF TG_TABLE_NAME IN('tom_order','cargo_item','transport_task','task_dependency','tms_task_dispatch','tms_assignment') THEN
  IF TG_OP<>'INSERT' THEN orders:=array_append(orders,OLD.order_id); END IF;
  IF TG_OP<>'DELETE' THEN orders:=array_append(orders,NEW.order_id); END IF;
 END IF;
 IF TG_TABLE_NAME IN('tms_resource_reservation','tms_exception') THEN
  IF TG_OP<>'INSERT' THEN assignments:=array_append(assignments,OLD.assignment_id); END IF;
  IF TG_OP<>'DELETE' THEN assignments:=array_append(assignments,NEW.assignment_id); END IF;
  FOR order_uuid IN SELECT order_id FROM logistics.tms_assignment WHERE id=ANY(assignments) LOOP orders:=array_append(orders,order_uuid); END LOOP;
 END IF;
 IF TG_TABLE_NAME IN('tms_assignment','tms_task_dispatch','tms_exception','task_dependency') THEN
  IF TG_OP<>'INSERT' THEN tasks:=array_append(tasks,OLD.task_id); END IF;
  IF TG_OP<>'DELETE' THEN tasks:=array_append(tasks,NEW.task_id); END IF;
 END IF;
 IF TG_TABLE_NAME='task_dependency' THEN
  IF TG_OP<>'INSERT' THEN tasks:=array_append(tasks,OLD.predecessor_task_id); END IF;
  IF TG_OP<>'DELETE' THEN tasks:=array_append(tasks,NEW.predecessor_task_id); END IF;
 END IF;
 FOR order_uuid IN SELECT order_id FROM logistics.transport_task WHERE id=ANY(tasks) LOOP orders:=array_append(orders,order_uuid); END LOOP;
 FOR order_uuid IN SELECT order_id FROM logistics.tms_task_dispatch WHERE task_id=ANY(tasks) LOOP orders:=array_append(orders,order_uuid); END LOOP;
 PERFORM logistics.guard_owned_closure_orders(orders,TG_OP<>'INSERT');
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END; $$;
-- Lexical order controls only explicit INSERT downstream locks, never heap tuple locks.
CREATE TRIGGER a_owned_closure_core BEFORE UPDATE OR DELETE ON logistics.order_core FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_order BEFORE UPDATE OR DELETE ON logistics.tom_order FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_item BEFORE INSERT OR UPDATE OR DELETE ON logistics.cargo_item FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_task BEFORE INSERT OR UPDATE OR DELETE ON logistics.transport_task FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_dependency BEFORE INSERT OR UPDATE OR DELETE ON logistics.task_dependency FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_anchor BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_task_dispatch FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_assignment BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_assignment FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_reservation BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_resource_reservation FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE TRIGGER a_owned_closure_exception BEFORE INSERT OR UPDATE OR DELETE ON logistics.tms_exception FOR EACH ROW EXECUTE FUNCTION logistics.guard_recorded_closure_source();
CREATE FUNCTION logistics.seal_closure_manifest() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM order_id FROM logistics.tom_order WHERE order_id=NEW.order_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM logistics.tom_order_closure_command WHERE closure_id=NEW.closure_id) THEN RAISE EXCEPTION 'Successful closure manifest is sealed' USING ERRCODE='23503'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER tom_closure_manifest_sealed BEFORE INSERT ON logistics.tom_order_closure_task FOR EACH ROW EXECUTE FUNCTION logistics.seal_closure_manifest();

-- A temporary lens for FOUR ASCII metadata fields ONLY, never a raw-value decoder.
-- Replacement preserves quote/backslash structure: true NUL becomes non-ASCII;
-- escaped literal sequences retain slash/u characters. Neither can forge ASCII keys,
-- UUIDs, decimal versions or enum tokens. Never persist/return the lens or read raw fields.
CREATE FUNCTION logistics.management_ascii_metadata(value json) RETURNS json LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT replace(value::text,chr(92)||'u0000',chr(92)||'ufffd')::json;
$$;
CREATE FUNCTION logistics.validate_management_confirmation(fact_uuid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE f logistics.tms_completion_confirmation%ROWTYPE; receipt logistics.tms_completion_confirmation_command%ROWTYPE;
BEGIN
 SELECT * INTO f FROM logistics.tms_completion_confirmation WHERE id=fact_uuid;
 IF NOT FOUND THEN RAISE EXCEPTION 'Management confirmation is required' USING ERRCODE='23503'; END IF;
 PERFORM logistics.validate_tms_completion(f.completion_id);
 SELECT * INTO receipt FROM logistics.tms_completion_confirmation_command WHERE id=f.receipt_id;
 IF receipt.id IS NULL OR receipt.company_scope<>'SINGLE_COMPANY' OR receipt.canonical_path<>'/api/v1/tms/completions/'||f.completion_id::text||'/confirm'
  OR receipt.original_data->>'codecVersion' IS DISTINCT FROM '1'
  OR json_typeof(receipt.original_data->'codecVersion') IS DISTINCT FROM 'number'
  OR receipt.original_data->>'completionId' IS DISTINCT FROM f.completion_id::text OR receipt.original_data->>'assignmentId' IS DISTINCT FROM f.assignment_id::text
  OR receipt.original_data->>'taskId' IS DISTINCT FROM f.task_id::text OR receipt.original_data->>'sourceOrderVersion' IS DISTINCT FROM f.source_order_version::text
  OR receipt.original_data->>'version' IS DISTINCT FROM '1' OR receipt.original_data->'confirmation'->>'id' IS DISTINCT FROM f.id::text
  OR receipt.original_data->'confirmation'->>'version' IS DISTINCT FROM '1'
  OR receipt.original_data->'confirmation'->>'policyMode' IS DISTINCT FROM f.policy_mode
  OR receipt.original_data->'confirmation'->>'reason' IS DISTINCT FROM replace(encode(f.reason,'base64'),chr(10),'')
  OR receipt.original_data->'confirmation'->'actor'->>'role' IS DISTINCT FROM f.actor_role
  OR receipt.original_data->'confirmation'->'actor'->>'displayName' IS DISTINCT FROM replace(encode(f.actor_display_name,'base64'),chr(10),'')
  OR (receipt.original_data->'confirmation'->>'confirmedAt')::timestamptz IS DISTINCT FROM f.confirmed_at
 THEN RAISE EXCEPTION 'Original confirmation success is required' USING ERRCODE='23503'; END IF;
END; $$;
CREATE FUNCTION logistics.validate_order_closure(closure_uuid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE w logistics.tom_order_closure%ROWTYPE; receipt logistics.tom_order_closure_command%ROWTYPE; manifest record; n bigint;
BEGIN
 SELECT * INTO w FROM logistics.tom_order_closure WHERE id=closure_uuid;
 IF NOT FOUND THEN RAISE EXCEPTION 'Closure witness is required' USING ERRCODE='23503'; END IF;
 SELECT count(*) INTO n FROM logistics.transport_task WHERE order_id=w.order_id;
 IF n<>w.task_count OR n<1 OR (SELECT count(*) FROM logistics.tom_order_closure_task WHERE closure_id=w.id)<>n OR EXISTS(
  SELECT 1 FROM logistics.transport_task t LEFT JOIN logistics.tom_order_closure_task m ON m.closure_id=w.id AND m.task_id=t.id
   WHERE t.order_id=w.order_id AND(m.task_id IS NULL OR m.required_task IS DISTINCT FROM t.required_task))
 THEN RAISE EXCEPTION 'Whole persisted task manifest is required' USING ERRCODE='23503'; END IF;
 FOR manifest IN SELECT * FROM logistics.tom_order_closure_task WHERE closure_id=w.id LOOP
  PERFORM logistics.validate_tms_completion(manifest.completion_id);PERFORM logistics.validate_management_confirmation(manifest.confirmation_id);
 END LOOP;
 IF EXISTS(SELECT 1 FROM logistics.tms_resource_reservation r JOIN logistics.tms_assignment a ON a.id=r.assignment_id WHERE a.order_id=w.order_id AND r.released_at IS NULL AND r.completion_id IS NULL)
  OR EXISTS(SELECT 1 FROM logistics.tms_exception e JOIN logistics.tms_assignment a ON a.id=e.assignment_id WHERE a.order_id=w.order_id AND e.status<>'RESOLVED')
 THEN RAISE EXCEPTION 'Whole order release and exception disposition are required' USING ERRCODE='23503'; END IF;
 IF NOT EXISTS(SELECT 1 FROM logistics.order_draft_revision r WHERE r.order_id=w.order_id AND r.aggregate_version=w.target_version
  AND logistics.management_ascii_metadata(r.snapshot)->>'id'=w.order_id::text AND logistics.management_ascii_metadata(r.snapshot)->>'version'=w.target_version::text AND logistics.management_ascii_metadata(r.snapshot)->>'lifecycleStatus'='CLOSED' AND logistics.management_ascii_metadata(r.snapshot)->>'acceptanceStatus'='ACCEPTED')
 THEN RAISE EXCEPTION 'Full closed revision is required' USING ERRCODE='23503'; END IF;
 SELECT * INTO receipt FROM logistics.tom_order_closure_command WHERE id=w.receipt_id;
 IF receipt.id IS NULL OR receipt.company_scope<>w.company_scope OR receipt.canonical_path<>'/api/v1/orders/'||w.order_id::text||'/close'
  OR receipt.original_data->>'codecVersion' IS DISTINCT FROM '1' OR receipt.original_data->>'orderId' IS DISTINCT FROM w.order_id::text OR receipt.original_data->>'lifecycleStatus' IS DISTINCT FROM 'CLOSED'
  OR json_typeof(receipt.original_data->'codecVersion') IS DISTINCT FROM 'number'
  OR receipt.original_data->>'orderNo' IS DISTINCT FROM (SELECT order_no FROM logistics.order_core WHERE id=w.order_id)
  OR receipt.original_data->>'acceptanceStatus' IS DISTINCT FROM 'ACCEPTED' OR receipt.original_data->>'version' IS DISTINCT FROM w.target_version::text
  OR receipt.original_data->'closure'->>'id' IS DISTINCT FROM w.id::text
  OR receipt.original_data->'closure'->>'sourceVersion' IS DISTINCT FROM w.source_version::text
  OR receipt.original_data->'closure'->>'version' IS DISTINCT FROM w.target_version::text
  OR receipt.original_data->'closure'->>'policyMode' IS DISTINCT FROM w.policy_mode
  OR receipt.original_data->'closure'->>'managerConfirmed' IS DISTINCT FROM 'true'
  OR receipt.original_data->'closure'->>'taskCount' IS DISTINCT FROM w.task_count::text
  OR receipt.original_data->'closure'->>'reason' IS DISTINCT FROM replace(encode(w.reason,'base64'),chr(10),'')
  OR receipt.original_data->'closure'->'actor'->>'role' IS DISTINCT FROM w.actor_role
  OR receipt.original_data->'closure'->'actor'->>'displayName' IS DISTINCT FROM replace(encode(w.actor_display_name,'base64'),chr(10),'')
  OR (receipt.original_data->'closure'->>'closedAt')::timestamptz IS DISTINCT FROM w.closed_at
 THEN RAISE EXCEPTION 'Original closure success is required' USING ERRCODE='23503'; END IF;
END; $$;
CREATE FUNCTION logistics.check_management_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='tms_completion_confirmation' THEN PERFORM logistics.validate_management_confirmation(NEW.id);
 ELSIF TG_TABLE_NAME='tms_completion_confirmation_command' THEN PERFORM logistics.validate_management_confirmation(NEW.confirmation_id);
 ELSIF TG_TABLE_NAME='tom_order_closure' THEN PERFORM logistics.validate_order_closure(NEW.id);
 ELSE PERFORM logistics.validate_order_closure(NEW.closure_id); END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER tms_confirmation_proof AFTER INSERT ON logistics.tms_completion_confirmation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_management_audit();
CREATE CONSTRAINT TRIGGER tms_confirmation_receipt_proof AFTER INSERT ON logistics.tms_completion_confirmation_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_management_audit();
CREATE CONSTRAINT TRIGGER tom_closure_proof AFTER INSERT ON logistics.tom_order_closure DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_management_audit();
CREATE CONSTRAINT TRIGGER tom_closure_task_proof AFTER INSERT ON logistics.tom_order_closure_task DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_management_audit();
CREATE CONSTRAINT TRIGGER tom_closure_receipt_proof AFTER INSERT ON logistics.tom_order_closure_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION logistics.check_management_audit();
