ALTER TABLE public.estimate_line
  ADD COLUMN unit varchar(32);

UPDATE public.estimate_line
SET unit = NULLIF(btrim(catalog_snapshot ->> 'unit'), '')
WHERE catalog_snapshot IS NOT NULL;
