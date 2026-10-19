CREATE TABLE public.company (
    id uuid NOT NULL,
    version integer NOT NULL DEFAULT 0,
    code varchar(32) NOT NULL,
    name varchar(200) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT company_pkey PRIMARY KEY (id),
    CONSTRAINT uk_company_code UNIQUE (code),
    CONSTRAINT ck_company_version CHECK (version >= 0),
    CONSTRAINT ck_company_code CHECK (
        code = upper(btrim(code))
        AND code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'
    ),
    CONSTRAINT ck_company_name CHECK (
        name = btrim(name)
        AND name <> ''
        AND name !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_company_audit_time CHECK (updated_at >= created_at)
);

CREATE INDEX idx_company_active_name
    ON public.company (active, name, id);

CREATE FUNCTION public.enforce_company_code_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        RAISE EXCEPTION 'company code is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_company_code_immutable
BEFORE UPDATE OF code ON public.company
FOR EACH ROW EXECUTE FUNCTION public.enforce_company_code_immutable();

WITH seeded_at AS (
    SELECT statement_timestamp() AS value
)
INSERT INTO public.company(
    id, version, code, name, active, created_at, updated_at)
SELECT
    'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid,
    0,
    'RWMS',
    'Первая компания',
    true,
    value,
    value
FROM seeded_at;

ALTER TABLE public.auth_subject
    ADD COLUMN company_id uuid;

UPDATE public.auth_subject
SET company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid;

ALTER TABLE public.auth_subject
    ALTER COLUMN company_id SET NOT NULL,
    ADD CONSTRAINT fk_auth_subject_company FOREIGN KEY (company_id)
        REFERENCES public.company (id) ON DELETE RESTRICT;

CREATE INDEX idx_auth_subject_company_principal_active
    ON public.auth_subject (company_id, principal_type, active);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.auth_subject subject
        LEFT JOIN public.auth_subject_pii pii
          ON pii.subject_id = subject.id
        LEFT JOIN public.auth_subject_credential credential
          ON credential.subject_id = subject.id
        LEFT JOIN public.event_stream_head head
          ON head.aggregate_type = CASE subject.principal_type
                WHEN 'USER' THEN 'USER_AUTHORIZATION'
                ELSE 'WORKER_ACCESS'
             END
         AND head.aggregate_id = subject.id::text
        WHERE pii.subject_id IS NULL
           OR credential.subject_id IS NULL
           OR head.aggregate_id IS NULL
           OR head.current_version <> subject.version
           OR (
                subject.principal_type = 'WORKER'
                AND credential.credential_status <> CASE
                    WHEN subject.active THEN 'ACTIVE'
                    ELSE 'DISABLED'
                END
           )
           OR (
                subject.principal_type = 'USER'
                AND EXISTS (
                    SELECT 1
                    FROM public.user_warehouse_access access
                    LEFT JOIN public.user_warehouse_access_note note
                      ON note.access_id = access.id
                    WHERE access.user_id = subject.id
                      AND note.access_id IS NULL
                )
           )
    ) THEN
        RAISE EXCEPTION
            'Cannot backfill company foundation: auth projection is incomplete or stale';
    END IF;
END
$$;

CREATE TEMPORARY TABLE company_foundation_backfill
ON COMMIT DROP
AS
WITH facts AS (
    SELECT
        subject.id AS subject_id,
        subject.principal_type,
        CASE subject.principal_type
            WHEN 'USER' THEN 'USER_AUTHORIZATION'
            ELSE 'WORKER_ACCESS'
        END AS aggregate_type,
        CASE subject.principal_type
            WHEN 'USER' THEN 'auth.user-authorization.changed.v1'
            ELSE 'auth.worker-access.configured.v1'
        END AS event_type,
        CASE subject.principal_type
            WHEN 'USER' THEN 'rwms.auth.user-authorization.v1'
            ELSE 'rwms.auth.worker-access.v1'
        END AS topic,
        subject.version::bigint AS previous_version,
        subject.version::bigint + 1 AS aggregate_version,
        md5(subject.id::text || ':company-foundation:v1')::uuid AS event_id,
        md5(subject.id::text || ':company-foundation:v1:correlation')::uuid
            AS correlation_id,
        clock_timestamp() AS recorded_at,
        CASE subject.principal_type
            WHEN 'USER' THEN jsonb_build_object(
                'subjectId', subject.id,
                'companyId', subject.company_id,
                'active', subject.active,
                'mobileAppAccess', subject.mobile_app_access,
                'rentalAccess', subject.rental_access,
                'globalRole', subject.global_role,
                'profileRevision', pii.profile_revision,
                'warehouseAccess', COALESCE((
                    SELECT jsonb_agg(jsonb_build_object(
                        'accessId', access.id,
                        'warehouseId', access.warehouse_id::uuid,
                        'level', access.access_level,
                        'active', access.active,
                        'noteRevision', note.note_revision)
                        ORDER BY access.warehouse_id, access.id)
                    FROM public.user_warehouse_access access
                    JOIN public.user_warehouse_access_note note
                      ON note.access_id = access.id
                    WHERE access.user_id = subject.id
                ), '[]'::jsonb)
            )
            ELSE jsonb_build_object(
                'subjectId', subject.id,
                'companyId', subject.company_id,
                'workerLink', subject.id,
                'warehouseId', subject.warehouse_id::uuid,
                'active', subject.active,
                'credentialStatus', credential.credential_status
            )
        END AS payload
    FROM public.auth_subject subject
    JOIN public.auth_subject_pii pii
      ON pii.subject_id = subject.id
    JOIN public.auth_subject_credential credential
      ON credential.subject_id = subject.id
)
SELECT
    subject_id,
    principal_type,
    aggregate_type,
    event_type,
    topic,
    previous_version,
    aggregate_version,
    event_id,
    correlation_id,
    recorded_at,
    payload,
    encode(sha256(convert_to(payload::text, 'UTF8')), 'hex') AS payload_sha256
FROM facts;

UPDATE public.auth_subject subject
SET version = backfill.aggregate_version::integer,
    updated_at = backfill.recorded_at
FROM company_foundation_backfill backfill
WHERE subject.id = backfill.subject_id
  AND subject.version = backfill.previous_version;

UPDATE public.event_stream_head head
SET current_version = backfill.aggregate_version,
    last_event_id = backfill.event_id,
    updated_at = backfill.recorded_at
FROM company_foundation_backfill backfill
WHERE head.aggregate_type = backfill.aggregate_type
  AND head.aggregate_id = backfill.subject_id::text
  AND head.current_version = backfill.previous_version;

INSERT INTO public.domain_event(
    event_id,
    aggregate_type,
    aggregate_id,
    aggregate_version,
    event_type,
    event_version,
    occurred_at,
    recorded_at,
    correlation_id,
    causation_id,
    actor_ref,
    payload,
    payload_sha256,
    baseline)
SELECT
    event_id,
    aggregate_type,
    subject_id::text,
    aggregate_version,
    event_type,
    1,
    recorded_at,
    recorded_at,
    correlation_id,
    NULL,
    NULL,
    payload,
    payload_sha256,
    false
FROM company_foundation_backfill;

WITH envelopes AS (
    SELECT
        backfill.*,
        jsonb_build_object(
            'envelopeVersion', 2,
            'eventId', backfill.event_id,
            'eventType', backfill.event_type,
            'eventVersion', 1,
            'occurredAt', backfill.recorded_at,
            'recordedAt', backfill.recorded_at,
            'producer', 'auth-service',
            'aggregateType', backfill.aggregate_type,
            'aggregateId', backfill.subject_id::text,
            'aggregateVersion', backfill.aggregate_version,
            'correlation', jsonb_build_object(
                'correlationId', backfill.correlation_id,
                'causationId', NULL),
            'actorRef', NULL,
            'payload', backfill.payload
        ) AS envelope_body
    FROM company_foundation_backfill backfill
)
INSERT INTO public.outbox_event(
    event_id,
    aggregate_type,
    aggregate_id,
    aggregate_version,
    event_type,
    topic,
    envelope_body,
    envelope_sha256,
    status,
    attempt_count,
    next_attempt_at,
    created_at)
SELECT
    event_id,
    aggregate_type,
    subject_id::text,
    aggregate_version,
    event_type,
    topic,
    envelope_body,
    encode(sha256(convert_to(envelope_body::text, 'UTF8')), 'hex'),
    'PENDING',
    0,
    recorded_at,
    recorded_at
FROM envelopes;

INSERT INTO public.projection_checkpoint(
    projection_name,
    aggregate_type,
    aggregate_id,
    aggregate_version,
    projection_sha256,
    updated_at)
SELECT
    'auth-live-v1',
    aggregate_type,
    subject_id::text,
    aggregate_version,
    payload_sha256,
    recorded_at
FROM company_foundation_backfill
ON CONFLICT (projection_name, aggregate_type, aggregate_id)
DO UPDATE SET
    aggregate_version = EXCLUDED.aggregate_version,
    projection_sha256 = EXCLUDED.projection_sha256,
    updated_at = EXCLUDED.updated_at;
