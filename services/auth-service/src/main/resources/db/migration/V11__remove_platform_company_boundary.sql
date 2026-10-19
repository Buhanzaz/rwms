DO $$
DECLARE
  subject_company_count bigint;
BEGIN
  SELECT count(DISTINCT company_id) INTO subject_company_count
    FROM public.auth_subject
   WHERE company_id IS NOT NULL;

  IF subject_company_count > 1 THEN
    RAISE EXCEPTION
      'V11 cannot remove the platform company boundary from a multi-company auth database';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM public.auth_subject subject
      LEFT JOIN public.company company ON company.id = subject.company_id
     WHERE subject.company_id IS NULL OR company.id IS NULL
  ) THEN
    RAISE EXCEPTION
      'V11 cannot remove the platform company boundary while auth subjects are inconsistent';
  END IF;
END;
$$;

CREATE TEMPORARY TABLE auth_company_payload_normalization (
    event_id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL
) ON COMMIT DROP;

INSERT INTO auth_company_payload_normalization(
    event_id,
    aggregate_type,
    aggregate_id,
    aggregate_version)
SELECT
    event.event_id,
    event.aggregate_type,
    event.aggregate_id,
    event.aggregate_version
FROM public.domain_event event
WHERE event.payload ? 'companyId';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.domain_event event
     WHERE jsonb_path_exists(event.payload, '$.**.companyId')
       AND NOT (event.payload ? 'companyId')
  ) OR EXISTS (
    SELECT 1
      FROM public.outbox_event outbox
     WHERE jsonb_path_exists(outbox.envelope_body, '$.**.companyId')
       AND NOT (outbox.envelope_body->'payload' ? 'companyId')
  ) THEN
    RAISE EXCEPTION
      'V11 found a nested platform company claim outside the V8 auth event payload';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM auth_company_payload_normalization normalized
      JOIN public.domain_event event ON event.event_id = normalized.event_id
      LEFT JOIN public.auth_subject subject ON subject.id::text = event.aggregate_id
      LEFT JOIN public.outbox_event outbox ON outbox.event_id = event.event_id
     WHERE event.event_id <> md5(event.aggregate_id || ':company-foundation:v1')::uuid
        OR event.baseline
        OR NOT (
          (event.aggregate_type = 'USER_AUTHORIZATION'
            AND event.event_type = 'auth.user-authorization.changed.v1')
          OR
          (event.aggregate_type = 'WORKER_ACCESS'
            AND event.event_type = 'auth.worker-access.configured.v1')
        )
        OR subject.id IS NULL
        OR event.payload->>'companyId' IS DISTINCT FROM subject.company_id::text
        OR outbox.event_id IS NULL
        OR outbox.status <> 'PENDING'
        OR outbox.envelope_body->'payload' IS DISTINCT FROM event.payload
        OR outbox.envelope_body#>>'{payload,companyId}'
             IS DISTINCT FROM event.payload->>'companyId'
  ) THEN
    RAISE EXCEPTION
      'V11 found an auth event with an unsupported platform company claim';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM public.outbox_event outbox
     WHERE outbox.envelope_body->'payload' ? 'companyId'
       AND NOT EXISTS (
         SELECT 1
           FROM auth_company_payload_normalization normalized
          WHERE normalized.event_id = outbox.event_id
       )
  ) THEN
    RAISE EXCEPTION
      'V11 found an outbox company claim without its V8 auth event';
  END IF;
END;
$$;

ALTER TABLE public.domain_event
    DISABLE TRIGGER trg_domain_event_append_only;
ALTER TABLE public.outbox_event
    DISABLE TRIGGER trg_outbox_event_immutable_metadata;
ALTER TABLE public.outbox_event
    DISABLE TRIGGER trg_outbox_event_domain_parity;

UPDATE public.domain_event event
SET payload = event.payload - 'companyId',
    payload_sha256 = encode(
        sha256(convert_to((event.payload - 'companyId')::text, 'UTF8')),
        'hex')
FROM auth_company_payload_normalization normalized
WHERE event.event_id = normalized.event_id;

WITH normalized_envelopes AS (
    SELECT
        outbox.event_id,
        jsonb_set(
            outbox.envelope_body,
            '{payload}',
            (outbox.envelope_body->'payload') - 'companyId') AS envelope_body
    FROM public.outbox_event outbox
    JOIN auth_company_payload_normalization normalized
      ON normalized.event_id = outbox.event_id
)
UPDATE public.outbox_event outbox
SET envelope_body = normalized_envelopes.envelope_body,
    envelope_sha256 = encode(
        sha256(convert_to(normalized_envelopes.envelope_body::text, 'UTF8')),
        'hex')
FROM normalized_envelopes
WHERE outbox.event_id = normalized_envelopes.event_id;

UPDATE public.projection_checkpoint checkpoint
SET projection_sha256 = event.payload_sha256
FROM auth_company_payload_normalization normalized
JOIN public.domain_event event ON event.event_id = normalized.event_id
WHERE checkpoint.projection_name = 'auth-live-v1'
  AND checkpoint.aggregate_type = normalized.aggregate_type
  AND checkpoint.aggregate_id = normalized.aggregate_id
  AND checkpoint.aggregate_version = normalized.aggregate_version;

ALTER TABLE public.domain_event
    ENABLE TRIGGER trg_domain_event_append_only;
ALTER TABLE public.outbox_event
    ENABLE TRIGGER trg_outbox_event_immutable_metadata;
ALTER TABLE public.outbox_event
    ENABLE TRIGGER trg_outbox_event_domain_parity;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.domain_event event
     WHERE jsonb_path_exists(event.payload, '$.**.companyId')
  ) OR EXISTS (
    SELECT 1
      FROM public.outbox_event outbox
     WHERE jsonb_path_exists(outbox.envelope_body, '$.**.companyId')
  ) THEN
    RAISE EXCEPTION
      'V11 did not remove every platform company claim from auth event history';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM auth_company_payload_normalization normalized
      JOIN public.domain_event event ON event.event_id = normalized.event_id
      JOIN public.outbox_event outbox ON outbox.event_id = event.event_id
     WHERE event.payload_sha256 <> encode(
             sha256(convert_to(event.payload::text, 'UTF8')),
             'hex')
        OR outbox.envelope_sha256 <> encode(
             sha256(convert_to(outbox.envelope_body::text, 'UTF8')),
             'hex')
        OR outbox.envelope_body->'payload' IS DISTINCT FROM event.payload
  ) THEN
    RAISE EXCEPTION
      'V11 could not preserve auth event and outbox hash parity';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM auth_company_payload_normalization normalized
      JOIN public.domain_event event ON event.event_id = normalized.event_id
      JOIN public.projection_checkpoint checkpoint
        ON checkpoint.projection_name = 'auth-live-v1'
       AND checkpoint.aggregate_type = normalized.aggregate_type
       AND checkpoint.aggregate_id = normalized.aggregate_id
       AND checkpoint.aggregate_version = normalized.aggregate_version
     WHERE checkpoint.projection_sha256 <> event.payload_sha256
  ) THEN
    RAISE EXCEPTION
      'V11 could not preserve the auth live projection checkpoint';
  END IF;
END;
$$;

ALTER TABLE public.auth_subject
    DROP CONSTRAINT fk_auth_subject_company;

DROP INDEX public.idx_auth_subject_company_principal_active;

ALTER TABLE public.auth_subject
    DROP COLUMN company_id;

DROP TRIGGER trg_company_code_immutable ON public.company;
DROP FUNCTION public.enforce_company_code_immutable();
DROP INDEX public.idx_company_active_name;
DROP TABLE public.company;
