-- Restore the operational pointer after an empty reset without inventing facts.
-- Source-journal recovery and ingestion remain separate, verified operations.
LOCK TABLE public.dossier_projection_generation, public.dossier_active_generation
    IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO public.dossier_projection_generation(
    id, row_version, state, created_at, activated_at, retired_at)
SELECT gen_random_uuid(), 0, 'ACTIVE', now(), now(), NULL
WHERE NOT EXISTS (SELECT 1 FROM public.dossier_projection_generation)
  AND NOT EXISTS (SELECT 1 FROM public.dossier_active_generation);

INSERT INTO public.dossier_active_generation(
    id, row_version, pointer_name, generation_id, updated_at)
SELECT gen_random_uuid(), 0, 'DOSSIER', id, now()
FROM public.dossier_projection_generation
WHERE state = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM public.dossier_active_generation);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.dossier_active_generation pointer
        JOIN public.dossier_projection_generation generation ON generation.id = pointer.generation_id
        WHERE pointer.pointer_name = 'DOSSIER' AND generation.state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Cannot recover ambiguous dossier active generation';
    END IF;
END;
$$;
