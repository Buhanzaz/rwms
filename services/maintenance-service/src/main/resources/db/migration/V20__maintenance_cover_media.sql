ALTER TABLE public.maintenance_estimate
  ADD COLUMN cover_media_id uuid;

ALTER TABLE public.maintenance_repair
  ADD COLUMN cover_media_id uuid;
