ALTER TABLE public.task_sync_source
  ADD COLUMN source_type varchar(32),
  ADD COLUMN source_id uuid,
  ADD CONSTRAINT ck_task_sync_source_reference
    CHECK (
      (source_type IS NULL AND source_id IS NULL)
      OR (source_type = 'MAINTENANCE_REPAIR' AND source_id IS NOT NULL)
    );

CREATE INDEX idx_task_sync_source_reference
  ON public.task_sync_source(source_type, source_id)
  WHERE source_type IS NOT NULL;
