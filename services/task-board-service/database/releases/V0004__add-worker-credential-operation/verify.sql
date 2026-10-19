DO $rwms$
DECLARE
  mismatch text;
  actual_definition text;
  actual_validated boolean;
BEGIN
  WITH expected(column_name, data_type, udt_name, character_maximum_length) AS (VALUES
    ('credential_operation_id', 'uuid', 'uuid', NULL::bigint),
    ('credential_operation_type', 'character varying', 'varchar', 32::bigint),
    ('credential_operation_started_at', 'timestamp with time zone', 'timestamptz', NULL::bigint)
  ), actual AS (
    SELECT column_name, data_type, udt_name, character_maximum_length,
           is_nullable, column_default
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'worker'
      AND column_name IN (
        'credential_operation_id',
        'credential_operation_type',
        'credential_operation_started_at'
      )
  )
  SELECT string_agg(expected.column_name, ', ' ORDER BY expected.column_name)
    INTO mismatch
  FROM expected
  LEFT JOIN actual USING (column_name)
  WHERE actual.column_name IS NULL
     OR actual.data_type IS DISTINCT FROM expected.data_type
     OR actual.udt_name IS DISTINCT FROM expected.udt_name
     OR actual.character_maximum_length IS DISTINCT FROM expected.character_maximum_length
     OR actual.is_nullable IS DISTINCT FROM 'YES'
     OR actual.column_default IS NOT NULL;

  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'V0004 worker credential operation column mismatch: %', mismatch;
  END IF;

  SELECT pg_get_constraintdef(oid, true), convalidated
    INTO actual_definition, actual_validated
  FROM pg_constraint
  WHERE conrelid = 'public.worker'::regclass
    AND conname = 'ck_worker_credential_operation_type';

  IF actual_definition IS DISTINCT FROM
      'CHECK (credential_operation_type::text = ANY (ARRAY[''CONFIGURE''::character varying, ''RESET''::character varying, ''DISABLE''::character varying, ''CLEAR''::character varying, ''RECONCILE_DISABLE''::character varying]::text[]))'
     OR actual_validated IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Unexpected ck_worker_credential_operation_type definition';
  END IF;

  SELECT pg_get_constraintdef(oid, true), convalidated
    INTO actual_definition, actual_validated
  FROM pg_constraint
  WHERE conrelid = 'public.worker'::regclass
    AND conname = 'ck_worker_credential_operation_metadata';

  IF actual_definition IS DISTINCT FROM
      'CHECK (num_nonnulls(credential_operation_id, credential_operation_type, credential_operation_started_at) = ANY (ARRAY[0, 3]))'
     OR actual_validated IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Unexpected ck_worker_credential_operation_metadata definition';
  END IF;
END $rwms$;
