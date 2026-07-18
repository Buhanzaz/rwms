-- Stage 8 logistics private boundary: preserve the immutable V1/V2 event
-- history while allowing the canonical asset effect fact to be published.

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_asset_domain_event_type,
  ADD CONSTRAINT ck_asset_domain_event_type CHECK (event_type IN (
    'asset.rental-item.created.v1','asset.rental-item.passport-changed.v1',
    'asset.rental-item.status-changed.v1','asset.rental-item.warehouse-changed.v1',
    'asset.rental-item.logistics-effect-applied.v1',
    'asset.rental-item.general-comment-changed.v1','asset.rental-item.manual-note-added.v1',
    'asset.equipment-catalog.created.v1','asset.equipment-catalog.changed.v1',
    'asset.equipment-balance.changed.v1','asset.equipment-movement.transferred.v1',
    'asset.equipment-movement.written-off.v1','asset.equipment-movement.lost.v1',
    'asset.equipment-allocation-hold.acquired.v1','asset.equipment-allocation-hold.renewed.v1',
    'asset.equipment-allocation-hold.committed.v1','asset.equipment-allocation-hold.released.v1',
    'asset.equipment-allocation-hold.expired.v1','asset.operation-lease.acquired.v1',
    'asset.operation-lease.renewed.v1','asset.operation-lease.released.v1',
    'asset.operation-lease.expired.v1','asset.classifier.created.v1','asset.classifier.changed.v1'));
