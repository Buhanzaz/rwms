-- Publication retries and manual history recovery must use the observation that belonged to the
-- completed finding revision, not re-read mutable cabin metadata from another service.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN asset_passport_observation jsonb;

UPDATE public.inventory_publication_intent intent
SET asset_passport_observation = jsonb_build_object(
  'presence', finding.passport_observation_state,
  'value', finding.passport_observation)
FROM public.inventory_finding finding,
     public.inventory_final_plan_entry entry
WHERE finding.inventory_id = intent.inventory_id
  AND finding.id = intent.finding_id
  AND finding.finding_revision = intent.source_revision
  AND entry.inventory_id = intent.inventory_id
  AND entry.final_plan_version = intent.final_plan_version
  AND entry.finding_id = intent.finding_id
  AND entry.finding_revision = intent.source_revision
  AND entry.asset_id = finding.asset_id;

-- Rows predating finding-bound final plans are retained as explicit no-observation evidence.
UPDATE public.inventory_publication_intent
SET asset_passport_observation = '{"presence":"ABSENT","value":null}'::jsonb
WHERE asset_passport_observation IS NULL;

ALTER TABLE public.inventory_publication_intent
  ALTER COLUMN asset_passport_observation
    SET DEFAULT '{"presence":"ABSENT","value":null}'::jsonb,
  ALTER COLUMN asset_passport_observation SET NOT NULL,
  ADD CONSTRAINT ck_inventory_publication_passport_observation CHECK (
    jsonb_typeof(asset_passport_observation) = 'object'
    AND jsonb_exists_all(asset_passport_observation, array['presence', 'value'])
    AND asset_passport_observation - array['presence', 'value'] = '{}'::jsonb
    AND (
      (asset_passport_observation ->> 'presence' = 'ABSENT'
        AND asset_passport_observation -> 'value' = 'null'::jsonb)
      OR (asset_passport_observation ->> 'presence' = 'EXPLICIT_EMPTY'
        AND asset_passport_observation -> 'value' = '{}'::jsonb)
      OR (asset_passport_observation ->> 'presence' = 'PRESENT'
        AND jsonb_typeof(asset_passport_observation -> 'value') = 'object'
        AND asset_passport_observation -> 'value' <> '{}'::jsonb)
    )
  );
