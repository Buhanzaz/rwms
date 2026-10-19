-- Business identities are UUID-only.  V4..V16 remain immutable migration
-- evidence; this migration removes their operational code columns and rewrites
-- the corresponding event facts in one controlled, hash-preserving step.

ALTER TABLE public.queue_entry
  DROP CONSTRAINT IF EXISTS ck_queue_entry_code_not_unassigned;

ALTER TABLE public.work_queue
  DROP CONSTRAINT IF EXISTS ck_work_queue_code_not_unassigned,
  DROP CONSTRAINT IF EXISTS uk_work_queue_code;

ALTER TABLE public.worker_class
  DROP CONSTRAINT IF EXISTS uk_worker_class_code;

DROP INDEX IF EXISTS public.uk_worker_class_code_ci;
DROP INDEX IF EXISTS public.uk_work_queue_code_ci;
DROP INDEX IF EXISTS public.idx_work_queue_order;

ALTER TABLE public.queue_entry
  DROP COLUMN IF EXISTS queue_code;

ALTER TABLE public.work_queue
  DROP COLUMN IF EXISTS code;

ALTER TABLE public.worker_class
  DROP COLUMN IF EXISTS code;

CREATE INDEX idx_work_queue_order
  ON public.work_queue(warehouse_id, sort_order, name, id);

-- Existing task content written before UUID snapshots cannot be resolved
-- honestly: retain only objects that already carry a canonical UUID and strip
-- the old identifier from those objects.  A subsequent server projection can
-- refill any discarded legacy snapshot; no UUID is synthesized from a code.
UPDATE public.queue_entry entry
SET worker_works = COALESCE((
      SELECT jsonb_agg(item - 'code' ORDER BY ordinal)
      FROM jsonb_array_elements(entry.worker_works) WITH ORDINALITY AS value(item, ordinal)
      WHERE jsonb_typeof(item) = 'object'
        AND item->>'id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ), '[]'::jsonb),
    worker_materials = COALESCE((
      SELECT jsonb_agg(item - 'code' ORDER BY ordinal)
      FROM jsonb_array_elements(entry.worker_materials) WITH ORDINALITY AS value(item, ordinal)
      WHERE jsonb_typeof(item) = 'object'
        AND item->>'id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ), '[]'::jsonb);

-- The event store is deliberately append-only at runtime.  This schema change
-- is the single audited exception: user triggers are disabled only for the
-- atomic canonical rewrite and immediately restored below.  The payloads,
-- snapshots, outbox envelopes and every dependent hash are updated together.
ALTER TABLE public.domain_event DISABLE TRIGGER USER;
ALTER TABLE public.outbox_event DISABLE TRIGGER USER;

WITH sanitized AS (
  SELECT
    event_id,
    CASE aggregate_type
      WHEN 'WORKER_CLASS' THEN payload - 'code'
      WHEN 'WORK_QUEUE' THEN payload - 'code'
      WHEN 'QUEUE_ENTRY' THEN payload - 'queueCode'
      ELSE payload
    END AS payload
  FROM public.domain_event
  WHERE (aggregate_type IN ('WORKER_CLASS', 'WORK_QUEUE') AND payload ? 'code')
     OR (aggregate_type = 'QUEUE_ENTRY' AND payload ? 'queueCode')
)
UPDATE public.domain_event event
SET payload = sanitized.payload,
    payload_sha256 = encode(sha256(convert_to(sanitized.payload::text, 'UTF8')), 'hex')
FROM sanitized
WHERE event.event_id = sanitized.event_id;

WITH sanitized AS (
  SELECT
    aggregate_type,
    aggregate_id,
    aggregate_version,
    CASE aggregate_type
      WHEN 'WORKER_CLASS' THEN state - 'code'
      WHEN 'WORK_QUEUE' THEN state - 'code'
      WHEN 'QUEUE_ENTRY' THEN state - 'queueCode'
      ELSE state
    END AS state
  FROM public.aggregate_snapshot
  WHERE (aggregate_type IN ('WORKER_CLASS', 'WORK_QUEUE') AND state ? 'code')
     OR (aggregate_type = 'QUEUE_ENTRY' AND state ? 'queueCode')
)
UPDATE public.aggregate_snapshot snapshot
SET state = sanitized.state,
    state_sha256 = encode(sha256(convert_to(sanitized.state::text, 'UTF8')), 'hex')
FROM sanitized
WHERE snapshot.aggregate_type = sanitized.aggregate_type
  AND snapshot.aggregate_id = sanitized.aggregate_id
  AND snapshot.aggregate_version = sanitized.aggregate_version;

UPDATE public.outbox_event outbox
SET envelope_body = jsonb_set(outbox.envelope_body, '{payload}', event.payload, false),
    envelope_sha256 = encode(
      sha256(convert_to(jsonb_set(outbox.envelope_body, '{payload}', event.payload, false)::text, 'UTF8')),
      'hex')
FROM public.domain_event event
WHERE outbox.event_id = event.event_id
  AND event.aggregate_type IN ('WORKER_CLASS', 'WORK_QUEUE', 'QUEUE_ENTRY')
  AND outbox.envelope_body->'payload' IS DISTINCT FROM event.payload;

UPDATE public.projection_checkpoint checkpoint
SET projection_sha256 = event.payload_sha256
FROM public.domain_event event
WHERE checkpoint.aggregate_type = event.aggregate_type
  AND checkpoint.aggregate_id = event.aggregate_id
  AND checkpoint.aggregate_version = event.aggregate_version
  AND checkpoint.projection_sha256 IS DISTINCT FROM event.payload_sha256;

UPDATE public.inbox_message inbox
SET payload_sha256 = event.payload_sha256
FROM public.domain_event event
WHERE inbox.event_id = event.event_id
  AND inbox.payload_sha256 IS DISTINCT FROM event.payload_sha256;

UPDATE public.version_gap_quarantine quarantine
SET payload_sha256 = event.payload_sha256
FROM public.domain_event event
WHERE quarantine.received_event_id = event.event_id
  AND quarantine.payload_sha256 IS DISTINCT FROM event.payload_sha256;

ALTER TABLE public.outbox_event ENABLE TRIGGER USER;
ALTER TABLE public.domain_event ENABLE TRIGGER USER;
