CREATE TABLE shipment_task_settings (
  warehouse_id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  max_cabins_per_shipment_task integer NOT NULL,
  updated_by_subject_id uuid NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_shipment_task_settings_max_cabins
    CHECK (max_cabins_per_shipment_task BETWEEN 1 AND 100)
);

ALTER TABLE driver_logistics_task
  DROP CONSTRAINT ck_driver_logistics_task_source,
  ADD CONSTRAINT ck_driver_logistics_task_source
    CHECK (source_type IN (
      'REPAIR', 'ESTIMATE', 'INVENTORY', 'REPAIR_PLACE', 'CAPITAL_REPAIR',
      'MANUAL', 'LOGISTICS_DOCUMENT', 'LOGISTICS_DOCUMENT_LINE'
    )),
  ADD COLUMN client_snapshot varchar(512),
  ALTER COLUMN movement_comment TYPE varchar(2000),
  ADD CONSTRAINT ck_driver_logistics_task_document_group
    CHECK (
      source_type <> 'LOGISTICS_DOCUMENT'
      OR (
        task_kind = 'SHIPMENT'
        AND client_snapshot IS NOT NULL
        AND char_length(btrim(client_snapshot)) BETWEEN 1 AND 512
      )
    );

CREATE TABLE driver_logistics_task_member (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  driver_task_id uuid NOT NULL,
  document_line_id uuid NOT NULL,
  cabin_id uuid NOT NULL,
  unit_number varchar(64) NOT NULL,
  position integer NOT NULL,
  cover_applied boolean NOT NULL DEFAULT false,
  cover_media_id uuid,
  cover_entry_id uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT fk_driver_logistics_task_member_task
    FOREIGN KEY (driver_task_id) REFERENCES driver_logistics_task(id),
  CONSTRAINT fk_driver_logistics_task_member_document_line
    FOREIGN KEY (document_line_id) REFERENCES logistics_document_line(id),
  CONSTRAINT uk_driver_logistics_task_member_line
    UNIQUE (document_line_id),
  CONSTRAINT uk_driver_logistics_task_member_cabin
    UNIQUE (driver_task_id, cabin_id),
  CONSTRAINT ck_driver_logistics_task_member_position
    CHECK (position >= 1),
  CONSTRAINT ck_driver_logistics_task_member_cover
    CHECK (
      (cover_applied = false AND cover_media_id IS NULL AND cover_entry_id IS NULL)
      OR (cover_applied = true AND cover_media_id IS NOT NULL AND cover_entry_id IS NOT NULL)
    )
);

CREATE INDEX ix_driver_logistics_task_member_task_position
  ON driver_logistics_task_member(driver_task_id, position);

CREATE INDEX ix_driver_logistics_task_member_cabin
  ON driver_logistics_task_member(cabin_id);
