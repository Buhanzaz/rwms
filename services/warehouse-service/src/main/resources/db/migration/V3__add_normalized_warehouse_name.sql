-- A display name is globally unique across active and inactive warehouses after
-- canonical whitespace folding and case normalization.  Do
-- not pick a survivor for historical collisions: operators must resolve them.
CREATE FUNCTION public.normalize_warehouse_name(value text)
RETURNS text
LANGUAGE sql
AS $$
  SELECT lower(
    regexp_replace(
      btrim(
        translate(
          value,
          chr(133) || chr(160) || chr(5760) ||
            chr(8192) || chr(8193) || chr(8194) || chr(8195) || chr(8196) || chr(8197) ||
            chr(8198) || chr(8199) || chr(8200) || chr(8201) || chr(8202) || chr(8232) ||
            chr(8233) || chr(8239) || chr(8287) || chr(12288),
          repeat(' ', 19))),
      E'\\s+',
      ' ',
      'g'))
$$;

DO $$
DECLARE
  conflicting_names text;
BEGIN
  SELECT string_agg(normalized_name, ', ' ORDER BY normalized_name)
    INTO conflicting_names
  FROM (
    SELECT public.normalize_warehouse_name(name) AS normalized_name
    FROM public.warehouse
    GROUP BY public.normalize_warehouse_name(name)
    HAVING count(*) > 1
  ) AS conflicts;

  IF conflicting_names IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE = '23505',
      MESSAGE = 'warehouse normalized-name migration failed: duplicate canonical warehouse names exist',
      DETAIL = 'Conflicting canonical names: ' || conflicting_names,
      HINT = 'Rename or merge the conflicting warehouse records before retrying the migration.';
  END IF;
END;
$$;

ALTER TABLE public.warehouse
  ADD COLUMN normalized_name varchar(255);

UPDATE public.warehouse
SET normalized_name = public.normalize_warehouse_name(name);

ALTER TABLE public.warehouse
  ALTER COLUMN normalized_name SET NOT NULL,
  ADD CONSTRAINT uk_warehouse_normalized_name UNIQUE (normalized_name);

DROP FUNCTION public.normalize_warehouse_name(text);
