-- Complete the Stage 5 asset-owned event streams without mutating the
-- immutable cumulative V1 baseline. Classifier facts and committed equipment
-- holds are first-class service-local streams and Kafka aggregate families.

ALTER TABLE public.equipment_allocation_hold
  ADD COLUMN committed_at timestamptz;

ALTER TABLE public.equipment_allocation_hold
  DROP CONSTRAINT ck_equipment_hold_state,
  DROP CONSTRAINT ck_equipment_hold_release;

ALTER TABLE public.equipment_allocation_hold
  ADD CONSTRAINT ck_equipment_hold_state CHECK (state IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED')),
  ADD CONSTRAINT ck_equipment_hold_release CHECK (
    (state = 'ACTIVE' AND released_at IS NULL AND committed_at IS NULL)
    OR (state = 'COMMITTED' AND released_at IS NULL AND committed_at IS NOT NULL)
    OR (state IN ('RELEASED','EXPIRED') AND released_at IS NOT NULL)
  );

ALTER TABLE public.event_stream_head
  DROP CONSTRAINT ck_asset_event_stream_head_type,
  ADD CONSTRAINT ck_asset_event_stream_head_type CHECK (aggregate_type IN (
    'RENTAL_ITEM','EQUIPMENT_CATALOG','EQUIPMENT_BALANCE','EQUIPMENT_MOVEMENT',
    'EQUIPMENT_ALLOCATION_HOLD','OPERATION_LEASE','CLASSIFIER'));

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_asset_domain_event_type,
  ADD CONSTRAINT ck_asset_domain_event_type CHECK (event_type IN (
    'asset.rental-item.created.v1','asset.rental-item.passport-changed.v1',
    'asset.rental-item.status-changed.v1','asset.rental-item.warehouse-changed.v1',
    'asset.rental-item.general-comment-changed.v1','asset.rental-item.manual-note-added.v1',
    'asset.equipment-catalog.created.v1','asset.equipment-catalog.changed.v1',
    'asset.equipment-balance.changed.v1','asset.equipment-movement.transferred.v1',
    'asset.equipment-movement.written-off.v1','asset.equipment-movement.lost.v1',
    'asset.equipment-allocation-hold.acquired.v1','asset.equipment-allocation-hold.renewed.v1',
    'asset.equipment-allocation-hold.committed.v1','asset.equipment-allocation-hold.released.v1',
    'asset.equipment-allocation-hold.expired.v1','asset.operation-lease.acquired.v1',
    'asset.operation-lease.renewed.v1','asset.operation-lease.released.v1',
    'asset.operation-lease.expired.v1','asset.classifier.created.v1','asset.classifier.changed.v1'));

ALTER TABLE public.outbox_event
  DROP CONSTRAINT ck_asset_outbox_topic,
  ADD CONSTRAINT ck_asset_outbox_topic CHECK (topic IN (
    'rwms.asset.rental-item.v1','rwms.asset.equipment-catalog.v1','rwms.asset.equipment-balance.v1',
    'rwms.asset.equipment-movement.v1','rwms.asset.equipment-allocation-hold.v1',
    'rwms.asset.operation-lease.v1','rwms.asset.classifier.v1'));
