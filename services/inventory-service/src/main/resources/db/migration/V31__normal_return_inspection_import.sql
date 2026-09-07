ALTER TABLE public.inbox_message
  DROP CONSTRAINT ck_inventory_inbox_topic,
  ADD CONSTRAINT ck_inventory_inbox_topic CHECK (
    source_topic IN (
      'rwms.media.media.v1',
      'rwms.asset.rental-item.v1',
      'rwms.logistics.return.v1',
      'rwms.maintenance.estimate.v1'));

ALTER TABLE public.inventory_membership_movement
  DROP CONSTRAINT uk_inventory_membership_movement_source,
  ADD CONSTRAINT uk_inventory_membership_movement_source UNIQUE (
    inventory_id, source_event_id, asset_id);

CREATE TABLE public.inventory_return_inspection_import (
  return_id uuid NOT NULL,
  line_id uuid NOT NULL,
  document_version bigint NOT NULL,
  estimate_id uuid,
  source_event_id uuid NOT NULL,
  source_occurred_at timestamptz NOT NULL,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  arrived_at timestamptz NOT NULL,
  completed_at timestamptz NOT NULL,
  terminal_state varchar(32) NOT NULL,
  asset_id uuid NOT NULL,
  asset_version bigint NOT NULL,
  asset_status varchar(48) NOT NULL,
  media_evidence jsonb NOT NULL,
  proof_sha256 varchar(64) NOT NULL,
  imported_at timestamptz NOT NULL,
  CONSTRAINT inventory_return_inspection_import_pkey
    PRIMARY KEY (return_id, line_id, document_version),
  CONSTRAINT fk_return_inspection_import_inventory
    FOREIGN KEY (inventory_id) REFERENCES public.inventory_session(id),
  CONSTRAINT fk_return_inspection_import_finding
    FOREIGN KEY (finding_id) REFERENCES public.inventory_finding(id),
  CONSTRAINT ck_return_inspection_import_version CHECK (
    document_version >= 0 AND asset_version >= 0),
  CONSTRAINT ck_return_inspection_import_times CHECK (completed_at >= arrived_at),
  CONSTRAINT ck_return_inspection_import_terminal CHECK (
    (terminal_state = 'ACCEPTED' AND asset_status = 'FREE')
    OR (terminal_state = 'ESTIMATE_REQUESTED'
      AND asset_status = 'WAITING_ESTIMATE_CONFIRMATION')),
  CONSTRAINT ck_return_inspection_import_media CHECK (
    jsonb_typeof(media_evidence) = 'array'
    AND jsonb_array_length(media_evidence) BETWEEN 1 AND 20),
  CONSTRAINT ck_return_inspection_import_hash CHECK (
    proof_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_return_inspection_import_finding
  ON public.inventory_return_inspection_import(inventory_id, finding_id);

CREATE UNIQUE INDEX uq_return_inspection_import_estimate
  ON public.inventory_return_inspection_import(estimate_id)
  WHERE estimate_id IS NOT NULL;

CREATE TRIGGER trg_return_inspection_import_immutable
BEFORE UPDATE OR DELETE ON public.inventory_return_inspection_import
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();
