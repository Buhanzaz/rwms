ALTER TABLE public.queue_entry
  ADD COLUMN IF NOT EXISTS worker_materials jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS worker_comments jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS source_media_references jsonb NOT NULL DEFAULT '[]'::jsonb;

DO $rwms$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM pg_constraint
     WHERE conname = 'ck_queue_entry_worker_content'
       AND conrelid = 'public.queue_entry'::regclass
  ) THEN
    ALTER TABLE public.queue_entry
      ADD CONSTRAINT ck_queue_entry_worker_content
      CHECK (
        jsonb_typeof(worker_materials) = 'array'
        AND jsonb_array_length(worker_materials) <= 100
        AND jsonb_typeof(worker_comments) = 'array'
        AND jsonb_array_length(worker_comments) <= 100
        AND jsonb_typeof(source_media_references) = 'array'
        AND jsonb_array_length(source_media_references) <= 100
      );
  END IF;
END $rwms$;
