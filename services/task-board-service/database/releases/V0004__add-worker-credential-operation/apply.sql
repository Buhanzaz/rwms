ALTER TABLE public.worker
  ADD COLUMN IF NOT EXISTS credential_operation_id uuid,
  ADD COLUMN IF NOT EXISTS credential_operation_type varchar(32),
  ADD COLUMN IF NOT EXISTS credential_operation_started_at timestamptz;

DO $rwms$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.worker'::regclass
      AND conname = 'ck_worker_credential_operation_type'
  ) THEN
    ALTER TABLE public.worker
      ADD CONSTRAINT ck_worker_credential_operation_type
      CHECK (credential_operation_type IN (
        'CONFIGURE',
        'RESET',
        'DISABLE',
        'CLEAR',
        'RECONCILE_DISABLE'
      ));
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.worker'::regclass
      AND conname = 'ck_worker_credential_operation_metadata'
  ) THEN
    ALTER TABLE public.worker
      ADD CONSTRAINT ck_worker_credential_operation_metadata
      CHECK (num_nonnulls(
        credential_operation_id,
        credential_operation_type,
        credential_operation_started_at
      ) IN (0, 3));
  END IF;
END $rwms$;
