ALTER TABLE public.inventory_asset_source_operation
  ADD COLUMN reserved_rental_item_id uuid,
  ADD COLUMN source_plan jsonb,
  ADD COLUMN proposal_response jsonb;

UPDATE public.inventory_asset_source_operation AS operation
SET reserved_rental_item_id = source.rental_item_id,
    proposal_response = source.response_body
FROM public.inventory_asset_source AS source
WHERE source.inventory_id = operation.inventory_id
  AND source.finding_id = operation.finding_id;

ALTER TABLE public.inventory_asset_source_operation
  ADD CONSTRAINT uk_inventory_asset_source_operation_reserved_item
    UNIQUE (reserved_rental_item_id),
  ADD CONSTRAINT ck_inventory_asset_source_operation_plan
    CHECK (source_plan IS NULL OR jsonb_typeof(source_plan) = 'object'),
  ADD CONSTRAINT ck_inventory_asset_source_operation_proposal
    CHECK (proposal_response IS NULL OR jsonb_typeof(proposal_response) = 'object'),
  ADD CONSTRAINT ck_inventory_asset_source_operation_proposal_shape
    CHECK (
      (reserved_rental_item_id IS NULL AND source_plan IS NULL AND proposal_response IS NULL)
      OR
      (reserved_rental_item_id IS NOT NULL AND proposal_response IS NOT NULL)
    );

COMMENT ON COLUMN public.inventory_asset_source_operation.reserved_rental_item_id IS
  'Stable inventory-only proposal identity; no rental_item row exists until completed reconciliation.';
COMMENT ON COLUMN public.inventory_asset_source_operation.source_plan IS
  'Normalized materialization plan for reserved sources; null only for already materialized legacy rows.';
COMMENT ON COLUMN public.inventory_asset_source_operation.proposal_response IS
  'Immutable source-registration response replayed before and after materialization.';
