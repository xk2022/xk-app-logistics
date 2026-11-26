CREATE TABLE logistics.order_acceptance_event (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES logistics.tom_order(order_id),
    from_version bigint NOT NULL CHECK (from_version BETWEEN 0 AND 9007199254740991),
    to_version bigint NOT NULL CHECK (to_version BETWEEN 1 AND 9007199254740991),
    actor_id uuid NOT NULL REFERENCES logistics.upms_account(id),
    accepted_at timestamptz(6) NOT NULL,
    snapshot json NOT NULL,
    request_trace_id varchar(200) NOT NULL,
    CHECK (to_version = from_version + 1),
    FOREIGN KEY (order_id,to_version) REFERENCES logistics.order_draft_revision(order_id,aggregate_version)
);
-- Textual JSON preserves every valid original value, including escaped U+0000.
-- Snapshot shape is validated in the application/tests; do not cast it to jsonb or decode JSON strings here.
CREATE FUNCTION logistics.reject_order_acceptance_event_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Order acceptance events are immutable'; END;
$$;
CREATE TRIGGER order_acceptance_event_immutable BEFORE UPDATE OR DELETE ON logistics.order_acceptance_event
FOR EACH ROW EXECUTE FUNCTION logistics.reject_order_acceptance_event_change();
-- No backfill, invented acceptance actors/times, dispatch state, or seeds.
