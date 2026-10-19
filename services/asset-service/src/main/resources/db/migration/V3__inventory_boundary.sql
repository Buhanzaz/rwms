-- Stage 7 inventory-service boundary owned by asset-service.
-- Existing stripped rental numbers are preserved verbatim: separators lost by
-- older code are never guessed during this migration.

ALTER TABLE public.rental_item DROP CONSTRAINT uk_rental_item_number;
ALTER TABLE public.rental_item DROP CONSTRAINT ck_rental_item_number;
ALTER TABLE public.rental_item RENAME COLUMN number TO display_canonical_number;
ALTER TABLE public.rental_item ADD COLUMN identity_match_key varchar(128);

UPDATE public.rental_item
SET identity_match_key = upper(regexp_replace(display_canonical_number, '[ -]', '', 'g'));

DO $$
BEGIN
  IF EXISTS (
    SELECT identity_match_key
    FROM public.rental_item
    GROUP BY identity_match_key
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'rental number identity collision requires explicit reconciliation';
  END IF;
END;
$$;

ALTER TABLE public.rental_item ALTER COLUMN identity_match_key SET NOT NULL;
ALTER TABLE public.rental_item
  ADD CONSTRAINT uk_rental_item_identity_match_key UNIQUE (identity_match_key),
  ADD CONSTRAINT ck_rental_item_display_canonical_number CHECK (
    display_canonical_number = btrim(display_canonical_number)
    AND display_canonical_number !~ '[[:space:]]{2,}'
    AND display_canonical_number ~ '^[[:alnum:]][[:alnum:] -]{0,127}$'),
  ADD CONSTRAINT ck_rental_item_identity_match_key CHECK (
    identity_match_key ~ '^[[:alnum:]]{1,128}$');

DROP INDEX public.idx_rental_item_warehouse_number;
CREATE INDEX idx_rental_item_warehouse_number
  ON public.rental_item(warehouse_id, display_canonical_number, id);
CREATE INDEX idx_rental_item_inventory_capture
  ON public.rental_item(warehouse_id, status, identity_match_key, id);

CREATE TABLE public.inventory_asset_capture_operation (
  operation_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  request_fingerprint varchar(64) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_asset_capture_operation_pkey PRIMARY KEY (operation_id),
  CONSTRAINT ck_inventory_asset_capture_operation_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_asset_capture_operation_hash CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.inventory_asset_capture (
  capture_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  technical_attempt bigint NOT NULL,
  warehouse_id uuid NOT NULL,
  request_fingerprint varchar(64) NOT NULL,
  membership_digest varchar(64) NOT NULL,
  total_count bigint NOT NULL,
  state varchar(16) NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  released_at timestamptz,
  CONSTRAINT inventory_asset_capture_pkey PRIMARY KEY (capture_id),
  CONSTRAINT uk_inventory_asset_capture_attempt UNIQUE (operation_id, technical_attempt),
  CONSTRAINT fk_inventory_asset_capture_operation FOREIGN KEY (operation_id)
    REFERENCES public.inventory_asset_capture_operation(operation_id),
  CONSTRAINT ck_inventory_asset_capture_attempt CHECK (technical_attempt > 0),
  CONSTRAINT ck_inventory_asset_capture_hashes CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$'
    AND membership_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_asset_capture_count CHECK (total_count >= 0),
  CONSTRAINT ck_inventory_asset_capture_state CHECK (state IN ('ACTIVE','RELEASED','EXPIRED')),
  CONSTRAINT ck_inventory_asset_capture_ttl CHECK (expires_at = created_at + interval '30 minutes'),
  CONSTRAINT ck_inventory_asset_capture_release CHECK (
    (state = 'ACTIVE' AND released_at IS NULL)
    OR (state IN ('RELEASED','EXPIRED') AND released_at IS NOT NULL))
);
CREATE INDEX idx_inventory_asset_capture_expiry
  ON public.inventory_asset_capture(expires_at, capture_id) WHERE state = 'ACTIVE';
CREATE INDEX idx_inventory_asset_capture_operation
  ON public.inventory_asset_capture(operation_id, technical_attempt DESC);

CREATE FUNCTION public.protect_inventory_asset_capture_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF (NEW.capture_id, NEW.operation_id, NEW.technical_attempt, NEW.warehouse_id,
      NEW.request_fingerprint, NEW.membership_digest, NEW.total_count,
      NEW.created_at, NEW.expires_at)
     IS DISTINCT FROM
     (OLD.capture_id, OLD.operation_id, OLD.technical_attempt, OLD.warehouse_id,
      OLD.request_fingerprint, OLD.membership_digest, OLD.total_count,
      OLD.created_at, OLD.expires_at) THEN
    RAISE EXCEPTION 'inventory asset capture identity and membership are immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_asset_capture_identity
BEFORE UPDATE ON public.inventory_asset_capture
FOR EACH ROW EXECUTE FUNCTION public.protect_inventory_asset_capture_identity();
CREATE TRIGGER trg_inventory_asset_capture_no_delete
BEFORE DELETE ON public.inventory_asset_capture
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TABLE public.inventory_asset_capture_member (
  capture_id uuid NOT NULL,
  sequence_no bigint NOT NULL,
  asset_id uuid NOT NULL,
  asset_version bigint NOT NULL,
  warehouse_id uuid NOT NULL,
  status varchar(64) NOT NULL,
  display_canonical_number varchar(128) NOT NULL,
  identity_match_key varchar(128) NOT NULL,
  passport_snapshot jsonb NOT NULL,
  contents_snapshot jsonb NOT NULL,
  CONSTRAINT inventory_asset_capture_member_pkey PRIMARY KEY (capture_id, sequence_no),
  CONSTRAINT uk_inventory_asset_capture_member UNIQUE (capture_id, asset_id),
  CONSTRAINT fk_inventory_asset_capture_member_capture FOREIGN KEY (capture_id)
    REFERENCES public.inventory_asset_capture(capture_id),
  CONSTRAINT fk_inventory_asset_capture_member_asset FOREIGN KEY (asset_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT ck_inventory_asset_capture_member_sequence CHECK (sequence_no >= 0),
  CONSTRAINT ck_inventory_asset_capture_member_version CHECK (asset_version >= 0),
  CONSTRAINT ck_inventory_asset_capture_member_status CHECK (status IN (
    'NEW','BOOKED','REPAIR','WAITING_REPAIR_CHECK','CAPITAL_REPAIR','AFTER_RENT',
    'SALE','USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS')),
  CONSTRAINT ck_inventory_asset_capture_member_number CHECK (
    display_canonical_number = btrim(display_canonical_number)
    AND display_canonical_number !~ '[[:space:]]{2,}'
    AND display_canonical_number ~ '^[[:alnum:]][[:alnum:] -]{0,127}$'
    AND identity_match_key ~ '^[[:alnum:]]{1,128}$'),
  CONSTRAINT ck_inventory_asset_capture_member_snapshots CHECK (
    jsonb_typeof(passport_snapshot) = 'object'
    AND jsonb_typeof(contents_snapshot) = 'array')
);

CREATE TABLE public.inventory_asset_source_operation (
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_fingerprint varchar(64) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_asset_source_operation_pkey PRIMARY KEY (inventory_id, finding_id),
  CONSTRAINT ck_inventory_asset_source_operation_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_asset_source_operation_hash CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.inventory_asset_number_claim (
  identity_match_key varchar(128) NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_asset_number_claim_pkey PRIMARY KEY (identity_match_key),
  CONSTRAINT uk_inventory_asset_number_claim_source UNIQUE (inventory_id, finding_id),
  CONSTRAINT fk_inventory_asset_number_claim_source FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_asset_source_operation(inventory_id, finding_id),
  CONSTRAINT ck_inventory_asset_number_claim_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_asset_number_claim_key CHECK (
    identity_match_key ~ '^[[:alnum:]]{1,128}$')
);

CREATE TABLE public.inventory_asset_source (
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  request_fingerprint varchar(64) NOT NULL,
  rental_item_id uuid NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_asset_source_pkey PRIMARY KEY (inventory_id, finding_id),
  CONSTRAINT uk_inventory_asset_source_item UNIQUE (rental_item_id),
  CONSTRAINT fk_inventory_asset_source_item FOREIGN KEY (rental_item_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT fk_inventory_asset_source_operation FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_asset_source_operation(inventory_id, finding_id),
  CONSTRAINT ck_inventory_asset_source_hash CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_asset_source_response CHECK (jsonb_typeof(response_body) = 'object')
);

CREATE TRIGGER trg_inventory_asset_capture_member_no_mutation
BEFORE UPDATE OR DELETE ON public.inventory_asset_capture_member
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_inventory_asset_source_no_update
BEFORE UPDATE OR DELETE ON public.inventory_asset_source
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
