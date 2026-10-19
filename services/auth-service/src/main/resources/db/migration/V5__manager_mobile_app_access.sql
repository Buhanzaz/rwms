ALTER TABLE public.auth_subject
    ADD COLUMN mobile_app_access boolean NOT NULL DEFAULT false;

UPDATE public.auth_subject
SET mobile_app_access = true
WHERE principal_type = 'USER'
  AND active
  AND global_role IN ('SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER');

ALTER TABLE public.auth_subject
    ADD CONSTRAINT ck_auth_subject_mobile_app_access CHECK (
        NOT mobile_app_access
        OR (
            principal_type = 'USER'
            AND global_role IN ('SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER')
        )
    );

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
          ON head.aggregate_type = 'USER_AUTHORIZATION'
         AND head.aggregate_id = subject.id::text
        WHERE subject.principal_type = 'USER'
          AND (
              pii.subject_id IS NULL
              OR credential.subject_id IS NULL
              OR head.aggregate_id IS NULL
              OR head.current_version <> subject.version
          )
    ) THEN
        RAISE EXCEPTION
            'Cannot backfill manager mobile access: USER authorization projection is incomplete or stale';
    END IF;
END
$$;

CREATE TEMPORARY TABLE manager_mobile_access_backfill
ON COMMIT DROP
AS
WITH facts AS (
    SELECT
        subject.id AS subject_id,
        subject.version::bigint AS previous_version,
        subject.version::bigint + 1 AS aggregate_version,
        md5(subject.id::text || ':manager-mobile-access:v1')::uuid AS event_id,
        md5(subject.id::text || ':manager-mobile-access:v1:correlation')::uuid
            AS correlation_id,
        clock_timestamp() AS recorded_at,
        jsonb_build_object(
            'subjectId', subject.id,
            'active', subject.active,
            'mobileAppAccess', subject.mobile_app_access,
            'globalRole', subject.global_role,
            'profileRevision', pii.profile_revision,
            'warehouseAccess', COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'accessId', access.id,
                    'warehouseId', access.warehouse_id,
                    'level', access.access_level,
                    'active', access.active,
                    'noteRevision', note.note_revision)
                    ORDER BY access.warehouse_id, access.id)
                FROM public.user_warehouse_access access
                JOIN public.user_warehouse_access_note note
                  ON note.access_id = access.id
                WHERE access.user_id = subject.id
            ), '[]'::jsonb)
        ) AS payload
    FROM public.auth_subject subject
    JOIN public.auth_subject_pii pii
      ON pii.subject_id = subject.id
    WHERE subject.principal_type = 'USER'
)
SELECT
    subject_id,
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
FROM manager_mobile_access_backfill backfill
WHERE subject.id = backfill.subject_id
  AND subject.version = backfill.previous_version;

UPDATE public.event_stream_head head
SET current_version = backfill.aggregate_version,
    last_event_id = backfill.event_id,
    updated_at = backfill.recorded_at
FROM manager_mobile_access_backfill backfill
WHERE head.aggregate_type = 'USER_AUTHORIZATION'
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
    'USER_AUTHORIZATION',
    subject_id::text,
    aggregate_version,
    'auth.user-authorization.changed.v1',
    1,
    recorded_at,
    recorded_at,
    correlation_id,
    NULL,
    NULL,
    payload,
    payload_sha256,
    false
FROM manager_mobile_access_backfill;

WITH envelopes AS (
    SELECT
        backfill.*,
        jsonb_build_object(
            'envelopeVersion', 2,
            'eventId', backfill.event_id,
            'eventType', 'auth.user-authorization.changed.v1',
            'eventVersion', 1,
            'occurredAt', backfill.recorded_at,
            'recordedAt', backfill.recorded_at,
            'producer', 'auth-service',
            'aggregateType', 'USER_AUTHORIZATION',
            'aggregateId', backfill.subject_id::text,
            'aggregateVersion', backfill.aggregate_version,
            'correlation', jsonb_build_object(
                'correlationId', backfill.correlation_id,
                'causationId', NULL),
            'actorRef', NULL,
            'payload', backfill.payload
        ) AS envelope_body
    FROM manager_mobile_access_backfill backfill
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
    'USER_AUTHORIZATION',
    subject_id::text,
    aggregate_version,
    'auth.user-authorization.changed.v1',
    'rwms.auth.user-authorization.v1',
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
    'USER_AUTHORIZATION',
    subject_id::text,
    aggregate_version,
    payload_sha256,
    recorded_at
FROM manager_mobile_access_backfill
ON CONFLICT (projection_name, aggregate_type, aggregate_id)
DO UPDATE SET
    aggregate_version = EXCLUDED.aggregate_version,
    projection_sha256 = EXCLUDED.projection_sha256,
    updated_at = EXCLUDED.updated_at;
