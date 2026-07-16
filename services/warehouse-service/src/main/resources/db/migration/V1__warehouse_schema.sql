-- RWMS warehouse-service initial owned schema.
-- This service deliberately owns only warehouse identity/metadata and a transactional
-- Kafka outbox. It has no event store, snapshot, inbox, topology, or location tables.

CREATE TABLE public.warehouse (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  code varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  city varchar(255) NOT NULL,
  address varchar(1000),
  time_zone varchar(64) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  sort_order integer,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT warehouse_pkey PRIMARY KEY (id),
  CONSTRAINT uk_warehouse_code UNIQUE (code),
  CONSTRAINT ck_warehouse_version CHECK (version >= 0),
  CONSTRAINT ck_warehouse_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_warehouse_name CHECK (length(btrim(name)) BETWEEN 1 AND 255),
  CONSTRAINT ck_warehouse_city CHECK (length(btrim(city)) BETWEEN 1 AND 255),
  CONSTRAINT ck_warehouse_address CHECK (address IS NULL OR length(btrim(address)) BETWEEN 1 AND 1000),
  CONSTRAINT ck_warehouse_time_zone CHECK (length(btrim(time_zone)) BETWEEN 1 AND 64),
  CONSTRAINT ck_warehouse_sort_order CHECK (sort_order IS NULL OR sort_order >= 0)
);

CREATE INDEX idx_warehouse_active_order
  ON public.warehouse(active, sort_order, code);

CREATE TABLE public.outbox_event (
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  event_version integer NOT NULL,
  topic varchar(192) NOT NULL,
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL,
  envelope_body jsonb NOT NULL,
  envelope_sha256 char(64) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_owner varchar(128),
  lease_token uuid,
  lease_until timestamptz,
  published_at timestamptz,
  dlt_at timestamptz,
  last_error_code varchar(64),
  created_at timestamptz NOT NULL,
  CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_outbox_event_stream_version UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT ck_outbox_event_aggregate CHECK (
    aggregate_type = 'WAREHOUSE'
    AND aggregate_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND aggregate_version >= 0
  ),
  CONSTRAINT ck_outbox_event_type CHECK (
    event_version = 1
    AND event_type IN (
      'warehouse.warehouse.created.v1',
      'warehouse.warehouse.changed.v1',
      'warehouse.warehouse.deactivated.v1'
    )
  ),
  CONSTRAINT ck_outbox_event_body CHECK (
    jsonb_typeof(envelope_body) = 'object'
    AND envelope_sha256 ~ '^[0-9a-f]{64}$'
    AND envelope_sha256 = encode(sha256(convert_to(envelope_body::text, 'UTF8')), 'hex')
  ),
  CONSTRAINT ck_outbox_event_status CHECK (
    status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DLT', 'QUARANTINED')
  ),
  CONSTRAINT ck_outbox_event_topic CHECK (topic = 'rwms.warehouse.warehouse.v1'),
  CONSTRAINT ck_outbox_event_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL
      AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR
    (status <> 'IN_FLIGHT' AND lease_owner IS NULL
      AND lease_token IS NULL AND lease_until IS NULL)
  ),
  CONSTRAINT ck_outbox_event_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT ck_outbox_event_dlt CHECK ((status = 'DLT') = (dlt_at IS NOT NULL)),
  CONSTRAINT ck_outbox_event_error_code CHECK (
    last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
  )
);

CREATE INDEX idx_outbox_event_pending
  ON public.outbox_event(next_attempt_at, created_at, event_id)
  WHERE status = 'PENDING';
CREATE INDEX idx_outbox_event_expired_lease
  ON public.outbox_event(lease_until, created_at, event_id)
  WHERE status = 'IN_FLIGHT';

CREATE FUNCTION public.prevent_warehouse_outbox_envelope_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.event_id IS DISTINCT FROM OLD.event_id
      OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
      OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
      OR NEW.aggregate_version IS DISTINCT FROM OLD.aggregate_version
      OR NEW.event_type IS DISTINCT FROM OLD.event_type
      OR NEW.event_version IS DISTINCT FROM OLD.event_version
      OR NEW.topic IS DISTINCT FROM OLD.topic
      OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
      OR NEW.recorded_at IS DISTINCT FROM OLD.recorded_at
      OR NEW.envelope_body IS DISTINCT FROM OLD.envelope_body
      OR NEW.envelope_sha256 IS DISTINCT FROM OLD.envelope_sha256
      OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'warehouse outbox envelope is immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_outbox_event_immutable
BEFORE UPDATE ON public.outbox_event
FOR EACH ROW
EXECUTE FUNCTION public.prevent_warehouse_outbox_envelope_mutation();

CREATE TABLE public.idempotency_record (
  subject_id uuid NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 char(64) NOT NULL,
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  warehouse_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT idempotency_record_pkey PRIMARY KEY (subject_id, idempotency_key),
  CONSTRAINT ck_idempotency_record_sha256 CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_idempotency_record_response CHECK (
    response_status = 201 AND jsonb_typeof(response_body) = 'object'
  ),
  CONSTRAINT ck_idempotency_record_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_idempotency_record_expiry
  ON public.idempotency_record(expires_at);

INSERT INTO public.warehouse (
  id, version, code, name, city, address, time_zone, active, sort_order, created_at, updated_at
) VALUES
  (
    '00000000-0000-0000-0000-000000000001',
    0,
    'WH_00000000000000000000000000000001',
    'СПБ',
    'Санкт-Петербург',
    NULL,
    'Europe/Moscow',
    true,
    NULL,
    clock_timestamp(),
    clock_timestamp()
  ),
  (
    '00000000-0000-0000-0000-000000000002',
    0,
    'WH_00000000000000000000000000000002',
    'Москва',
    'Москва',
    NULL,
    'Europe/Moscow',
    true,
    NULL,
    clock_timestamp(),
    clock_timestamp()
  );
