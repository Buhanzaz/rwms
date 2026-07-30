DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.queue_entry
    WHERE queue_id IS NULL
       OR queue_code IS NULL
       OR upper(queue_code) = 'UNASSIGNED'
  ) THEN
    RAISE EXCEPTION
      'Queue entries without a real queue must be reconciled before V13';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.work_queue
    WHERE upper(code) = 'UNASSIGNED'
  ) THEN
    RAISE EXCEPTION
      'UNASSIGNED is reserved and cannot be a work queue';
  END IF;
END
$$;

ALTER TABLE public.queue_entry
  ALTER COLUMN queue_id SET NOT NULL,
  ALTER COLUMN queue_code SET NOT NULL;

ALTER TABLE public.queue_entry
  ADD CONSTRAINT ck_queue_entry_code_not_unassigned
    CHECK (upper(queue_code) <> 'UNASSIGNED');

ALTER TABLE public.work_queue
  ADD CONSTRAINT ck_work_queue_code_not_unassigned
    CHECK (upper(code) <> 'UNASSIGNED');
