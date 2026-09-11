-- Recover only workers that have no event stream. Preserve credentials, identity,
-- versions and existing history; a baseline describes current state, not past events.
LOCK TABLE public.auth_subject, public.event_stream_head IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.auth_subject s
        LEFT JOIN public.event_stream_head h
          ON h.aggregate_type = 'WORKER_ACCESS' AND h.aggregate_id = s.id::text
        LEFT JOIN public.auth_subject_pii p ON p.subject_id = s.id
        LEFT JOIN public.auth_subject_credential c ON c.subject_id = s.id
        WHERE s.principal_type = 'WORKER' AND h.aggregate_id IS NULL
          AND (p.subject_id IS NULL OR c.subject_id IS NULL
               OR p.external_worker_id IS NULL OR s.warehouse_id IS NULL
               OR s.active <> (c.credential_status = 'ACTIVE')
               OR EXISTS (SELECT 1 FROM public.projection_checkpoint checkpoint
                          WHERE checkpoint.aggregate_type = 'WORKER_ACCESS'
                            AND checkpoint.aggregate_id = s.id::text))
    ) THEN
        RAISE EXCEPTION 'Cannot recover inconsistent worker access state';
    END IF;
END;
$$;

WITH missing AS (
    SELECT s.*, gen_random_uuid() AS event_id, clock_timestamp() AS recorded_at
    FROM public.auth_subject s
    WHERE s.principal_type = 'WORKER'
      AND NOT EXISTS (SELECT 1 FROM public.event_stream_head h
                      WHERE h.aggregate_type = 'WORKER_ACCESS' AND h.aggregate_id = s.id::text)
), heads AS (
    INSERT INTO public.event_stream_head(
        aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
    SELECT 'WORKER_ACCESS', id::text, version, event_id, recorded_at FROM missing
    RETURNING *
), facts AS (
    SELECT h.*, jsonb_build_object(
        'subjectId', s.id, 'workerLink', s.id, 'warehouseId', s.warehouse_id,
        'active', s.active, 'credentialStatus', c.credential_status) AS payload
    FROM heads h
    JOIN public.auth_subject s ON s.id::text = h.aggregate_id
    JOIN public.auth_subject_credential c ON c.subject_id = s.id
), events AS (
    INSERT INTO public.domain_event(
        event_id, aggregate_type, aggregate_id, aggregate_version,
        event_type, event_version, occurred_at, recorded_at, correlation_id,
        causation_id, actor_ref, payload, payload_sha256, baseline)
    SELECT last_event_id, aggregate_type, aggregate_id, current_version,
        'auth.worker-access.baseline.v1', 1, NULL, updated_at, gen_random_uuid(),
        NULL, NULL, payload, encode(sha256(convert_to(payload::text, 'UTF8')), 'hex'), true
    FROM facts
    RETURNING *
)
INSERT INTO public.projection_checkpoint(
    projection_name, aggregate_type, aggregate_id, aggregate_version, projection_sha256, updated_at)
SELECT 'auth-live-v1', aggregate_type, aggregate_id, aggregate_version, payload_sha256, recorded_at
FROM events;

-- Creation and mutation write the projection and event within one transaction.
-- A deferred check permits that ordering but rejects committing another orphan
-- worker (including a direct import that bypasses the owning service).
CREATE FUNCTION public.require_worker_access_stream()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.auth_subject s
        WHERE s.id = NEW.id AND s.principal_type = 'WORKER'
          AND NOT EXISTS (
              SELECT 1 FROM public.event_stream_head h
              JOIN public.domain_event e ON e.event_id = h.last_event_id
                AND e.aggregate_type = h.aggregate_type AND e.aggregate_id = h.aggregate_id
                AND e.aggregate_version = h.current_version
              JOIN public.projection_checkpoint c ON c.projection_name = 'auth-live-v1'
                AND c.aggregate_type = h.aggregate_type AND c.aggregate_id = h.aggregate_id
                AND c.aggregate_version = h.current_version AND c.projection_sha256 = e.payload_sha256
              WHERE h.aggregate_type = 'WORKER_ACCESS' AND h.aggregate_id = s.id::text
                AND h.current_version = s.version)
    ) THEN
        RAISE EXCEPTION 'Worker access projection requires a consistent event stream'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER worker_access_stream_required
AFTER INSERT OR UPDATE ON public.auth_subject
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.require_worker_access_stream();
