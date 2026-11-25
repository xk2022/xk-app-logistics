CREATE TABLE logistics.fms_vehicle (
    id uuid PRIMARY KEY,
    plate_no varchar(40) NOT NULL UNIQUE CHECK (char_length(plate_no) BETWEEN 1 AND 40),
    vehicle_type_id uuid NOT NULL REFERENCES logistics.adm_dict_item(id),
    capacity_ton numeric CHECK (capacity_ton >= 0
        AND capacity_ton NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric)
        AND length(capacity_ton::text) <= 32),
    registration_no varchar(100),
    registration_date date CHECK (registration_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    inspection_due_date date CHECK (inspection_due_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    administrative_status varchar(16) NOT NULL CHECK (administrative_status IN ('AVAILABLE','MAINTENANCE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0 CHECK (version BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES logistics.upms_account(id),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES logistics.upms_account(id)
);
CREATE INDEX fms_vehicle_options_idx ON logistics.fms_vehicle(administrative_status,plate_no,id);
CREATE INDEX fms_vehicle_type_idx ON logistics.fms_vehicle(vehicle_type_id,plate_no,id);
-- No vehicles or formal VEHICLE_TYPE items are seeded. Occupancy belongs to a later TMS work package.
