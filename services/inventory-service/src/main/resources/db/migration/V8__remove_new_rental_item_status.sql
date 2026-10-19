-- `NEW` was a rental-item lifecycle status in early data. It is now a cabin
-- category (`Новая`), while a newly received cabin is immediately FREE.
-- Normalize every mutable inventory projection before tightening the one
-- status constraint owned by this service.

UPDATE public.inventory_expected_item
SET asset_status_snapshot = 'FREE'
WHERE asset_status_snapshot = 'NEW';

UPDATE public.inventory_finding
SET current_status = 'FREE'
WHERE current_status = 'NEW';

UPDATE public.inventory_finding
SET inspection_status = 'FREE'
WHERE inspection_status = 'NEW';

UPDATE public.inventory_validation_item
SET asset_status = 'FREE'
WHERE asset_status = 'NEW';

ALTER TABLE public.inventory_membership_movement
  DISABLE TRIGGER inventory_membership_movement_append_only;

UPDATE public.inventory_membership_movement
SET asset_status = 'FREE'
WHERE asset_status = 'NEW';

ALTER TABLE public.inventory_membership_movement
  ENABLE TRIGGER inventory_membership_movement_append_only;

ALTER TABLE public.inventory_expected_item
  DROP CONSTRAINT ck_expected_item_status,
  ADD CONSTRAINT ck_expected_item_status CHECK (asset_status_snapshot IN (
    'BOOKED','REPAIR','WAITING_REPAIR_CHECK','CAPITAL_REPAIR','AFTER_RENT',
    'SALE','USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS'));

-- A validation preview hashes the source asset truth and the acknowledgement
-- built from it. Rewriting its JSON would make those hashes stale, so discard
-- only affected derived previews; an active session must request a fresh one.
DELETE FROM public.inventory_validation_item item
USING public.inventory_validation_snapshot snapshot
WHERE item.inventory_id = snapshot.inventory_id
  AND jsonb_path_exists(snapshot.snapshot_body, '$.** ? (@ == "NEW")');

DELETE FROM public.inventory_validation_snapshot
WHERE jsonb_path_exists(snapshot_body, '$.** ? (@ == "NEW")');

-- Completed idempotency responses are a mutable replay cache, not an audit
-- stream. Rewrite their exact retired status values so retries never expose
-- the removed status. Domain events and outbox facts stay immutable audit.
CREATE FUNCTION public.inventory_replace_retired_new_status(value jsonb)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
RETURNS NULL ON NULL INPUT
AS $$
DECLARE
  normalized jsonb;
BEGIN
  CASE jsonb_typeof(value)
    WHEN 'object' THEN
      SELECT COALESCE(
          jsonb_object_agg(entry.key, public.inventory_replace_retired_new_status(entry.value)),
          '{}'::jsonb)
      INTO normalized
      FROM jsonb_each(value) AS entry(key, value);
      RETURN normalized;
    WHEN 'array' THEN
      SELECT COALESCE(
          jsonb_agg(public.inventory_replace_retired_new_status(entry.value)
            ORDER BY entry.ordinality),
          '[]'::jsonb)
      INTO normalized
      FROM jsonb_array_elements(value) WITH ORDINALITY AS entry(value, ordinality);
      RETURN normalized;
    WHEN 'string' THEN
      IF value = '"NEW"'::jsonb THEN
        RETURN '"FREE"'::jsonb;
      END IF;
    ELSE
      NULL;
  END CASE;
  RETURN value;
END;
$$;

UPDATE public.inventory_idempotency_record
SET response_body = public.inventory_replace_retired_new_status(response_body),
    updated_at = clock_timestamp()
WHERE response_body IS NOT NULL
  AND jsonb_path_exists(response_body, '$.** ? (@ == "NEW")');

DROP FUNCTION public.inventory_replace_retired_new_status(jsonb);
