-- A movement event must be understandable without racing the independently
-- ordered balance streams.  Keep the physical movement row untouched and
-- capture immutable location/catalog context in a dedicated append-only row.
-- Existing movements can only be enriched from their current referenced
-- balances, so their provenance is explicitly marked as a migration backfill;
-- only AT_MOVEMENT rows are emitted as exact Kafka context.

CREATE TABLE public.equipment_movement_context (
  movement_id uuid NOT NULL,
  equipment_name_snapshot varchar(255) NOT NULL,
  equipment_category_snapshot varchar(32) NOT NULL,
  source_warehouse_id uuid NOT NULL,
  source_rental_item_id uuid,
  source_location_kind varchar(32) NOT NULL,
  target_warehouse_id uuid NOT NULL,
  target_rental_item_id uuid,
  target_location_kind varchar(32) NOT NULL,
  capture_origin varchar(32) NOT NULL,
  captured_at timestamptz NOT NULL,
  CONSTRAINT equipment_movement_context_pkey PRIMARY KEY (movement_id),
  CONSTRAINT fk_equipment_movement_context_movement
    FOREIGN KEY (movement_id) REFERENCES public.equipment_movement(id),
  CONSTRAINT ck_equipment_movement_context_name CHECK (
    length(btrim(equipment_name_snapshot)) BETWEEN 1 AND 255),
  CONSTRAINT ck_equipment_movement_context_category CHECK (
    equipment_category_snapshot IN ('FURNITURE','ELECTRICAL','OTHER')),
  CONSTRAINT ck_equipment_movement_context_source_kind CHECK (
    source_location_kind IN (
      'STOCK','CABIN_NON_RENTED','CABIN_RENTED','WRITTEN_OFF','LOST')),
  CONSTRAINT ck_equipment_movement_context_target_kind CHECK (
    target_location_kind IN (
      'STOCK','CABIN_NON_RENTED','CABIN_RENTED','WRITTEN_OFF','LOST')),
  CONSTRAINT ck_equipment_movement_context_source_location CHECK (
    (
      source_location_kind IN ('CABIN_NON_RENTED','CABIN_RENTED')
      AND source_rental_item_id IS NOT NULL
    )
    OR
    (
      source_location_kind IN ('STOCK','WRITTEN_OFF','LOST')
      AND source_rental_item_id IS NULL
    )
  ),
  CONSTRAINT ck_equipment_movement_context_target_location CHECK (
    (
      target_location_kind IN ('CABIN_NON_RENTED','CABIN_RENTED')
      AND target_rental_item_id IS NOT NULL
    )
    OR
    (
      target_location_kind IN ('STOCK','WRITTEN_OFF','LOST')
      AND target_rental_item_id IS NULL
    )
  ),
  CONSTRAINT ck_equipment_movement_context_origin CHECK (
    capture_origin IN ('AT_MOVEMENT','MIGRATION_BACKFILL'))
);

CREATE INDEX idx_equipment_movement_context_source_warehouse
  ON public.equipment_movement_context(source_warehouse_id, movement_id);

CREATE INDEX idx_equipment_movement_context_target_warehouse
  ON public.equipment_movement_context(target_warehouse_id, movement_id);

INSERT INTO public.equipment_movement_context(
  movement_id,
  equipment_name_snapshot,
  equipment_category_snapshot,
  source_warehouse_id,
  source_rental_item_id,
  source_location_kind,
  target_warehouse_id,
  target_rental_item_id,
  target_location_kind,
  capture_origin,
  captured_at)
SELECT
  movement.id,
  catalog.name,
  catalog.category,
  source_balance.warehouse_id,
  source_balance.rental_item_id,
  source_balance.location_kind,
  target_balance.warehouse_id,
  target_balance.rental_item_id,
  target_balance.location_kind,
  'MIGRATION_BACKFILL',
  clock_timestamp()
FROM public.equipment_movement movement
JOIN public.equipment_catalog_item catalog
  ON catalog.id = movement.equipment_id
JOIN public.equipment_balance source_balance
  ON source_balance.id = movement.source_balance_id
 AND source_balance.equipment_id = movement.equipment_id
JOIN public.equipment_balance target_balance
  ON target_balance.id = movement.target_balance_id
 AND target_balance.equipment_id = movement.equipment_id;

CREATE FUNCTION public.capture_equipment_movement_context()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  inserted_rows integer;
BEGIN
  INSERT INTO public.equipment_movement_context(
    movement_id,
    equipment_name_snapshot,
    equipment_category_snapshot,
    source_warehouse_id,
    source_rental_item_id,
    source_location_kind,
    target_warehouse_id,
    target_rental_item_id,
    target_location_kind,
    capture_origin,
    captured_at)
  SELECT
    NEW.id,
    catalog.name,
    catalog.category,
    source_balance.warehouse_id,
    source_balance.rental_item_id,
    source_balance.location_kind,
    target_balance.warehouse_id,
    target_balance.rental_item_id,
    target_balance.location_kind,
    'AT_MOVEMENT',
    clock_timestamp()
  FROM public.equipment_catalog_item catalog
  JOIN public.equipment_balance source_balance
    ON source_balance.id = NEW.source_balance_id
   AND source_balance.equipment_id = NEW.equipment_id
  JOIN public.equipment_balance target_balance
    ON target_balance.id = NEW.target_balance_id
   AND target_balance.equipment_id = NEW.equipment_id
  WHERE catalog.id = NEW.equipment_id;

  GET DIAGNOSTICS inserted_rows = ROW_COUNT;
  IF inserted_rows <> 1 THEN
    RAISE EXCEPTION
      'equipment movement % does not have coherent catalog/source/target context',
      NEW.id;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_equipment_movement_capture_context
AFTER INSERT ON public.equipment_movement
FOR EACH ROW EXECUTE FUNCTION public.capture_equipment_movement_context();

CREATE TRIGGER trg_equipment_movement_context_immutable
BEFORE UPDATE OR DELETE ON public.equipment_movement_context
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
