-- Server-owned interactive cabin creation remains resumable after a client
-- crash. The existing operation lease is the availability fence; this table
-- owns only creation workflow state and opaque media proof identities.

CREATE TABLE public.rental_item_creation_intent (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  rental_item_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  expected_photo_count integer NOT NULL,
  media_folder_id uuid NOT NULL,
  media_command_id uuid NOT NULL,
  photo_manifest_sha256 varchar(64) NOT NULL,
  creation_lease_id uuid NOT NULL,
  creation_lease_fencing_token bigint NOT NULL,
  state varchar(16) NOT NULL,
  created_by_subject_id uuid NOT NULL,
  cover_media_id uuid,
  media_proof_sha256 varchar(64),
  created_at timestamptz NOT NULL,
  completed_at timestamptz,
  abandoned_at timestamptz,
  updated_at timestamptz NOT NULL,
  CONSTRAINT rental_item_creation_intent_pkey PRIMARY KEY (id),
  CONSTRAINT uk_rental_item_creation_intent_item UNIQUE (rental_item_id),
  CONSTRAINT uk_rental_item_creation_intent_folder UNIQUE (media_folder_id),
  CONSTRAINT uk_rental_item_creation_intent_command UNIQUE (media_command_id),
  CONSTRAINT uk_rental_item_creation_intent_lease UNIQUE (creation_lease_id),
  CONSTRAINT ck_rental_item_creation_intent_version CHECK (version >= 0),
  CONSTRAINT ck_rental_item_creation_intent_photo_count CHECK (expected_photo_count BETWEEN 1 AND 20),
  CONSTRAINT ck_rental_item_creation_intent_lease_fence CHECK (creation_lease_fencing_token > 0),
  CONSTRAINT ck_rental_item_creation_intent_state CHECK (state IN ('PENDING','COMPLETED','ABANDONED')),
  CONSTRAINT ck_rental_item_creation_intent_proof CHECK (
    media_proof_sha256 IS NULL OR media_proof_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_rental_item_creation_intent_manifest_hash CHECK (
    photo_manifest_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_rental_item_creation_intent_terminal CHECK (
    (state = 'PENDING'
      AND cover_media_id IS NULL
      AND media_proof_sha256 IS NULL
      AND completed_at IS NULL
      AND abandoned_at IS NULL)
    OR
    (state = 'COMPLETED'
      AND cover_media_id IS NOT NULL
      AND media_proof_sha256 IS NOT NULL
      AND completed_at IS NOT NULL
      AND abandoned_at IS NULL)
    OR
    (state = 'ABANDONED'
      AND cover_media_id IS NULL
      AND media_proof_sha256 IS NULL
      AND completed_at IS NULL
      AND abandoned_at IS NOT NULL)),
  CONSTRAINT ck_rental_item_creation_intent_timestamps CHECK (
    updated_at >= created_at
    AND (completed_at IS NULL OR completed_at >= created_at)
    AND (abandoned_at IS NULL OR abandoned_at >= created_at)),
  CONSTRAINT fk_rental_item_creation_intent_item FOREIGN KEY (rental_item_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT fk_rental_item_creation_intent_lease FOREIGN KEY (creation_lease_id)
    REFERENCES public.operation_lease(id)
);

CREATE TABLE public.rental_item_creation_photo (
  intent_id uuid NOT NULL,
  photo_index integer NOT NULL,
  media_command_id uuid NOT NULL,
  checksum_sha256 varchar(64) NOT NULL,
  content_type varchar(64) NOT NULL,
  content_length bigint NOT NULL,
  CONSTRAINT rental_item_creation_photo_pkey PRIMARY KEY (intent_id, photo_index),
  CONSTRAINT uk_rental_item_creation_photo_command UNIQUE (media_command_id),
  CONSTRAINT ck_rental_item_creation_photo_index CHECK (photo_index BETWEEN 0 AND 19),
  CONSTRAINT ck_rental_item_creation_photo_checksum CHECK (
    checksum_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_rental_item_creation_photo_content_type CHECK (
    content_type IN ('image/jpeg','image/png','image/webp')),
  CONSTRAINT ck_rental_item_creation_photo_content_length CHECK (content_length > 0),
  CONSTRAINT fk_rental_item_creation_photo_intent FOREIGN KEY (intent_id)
    REFERENCES public.rental_item_creation_intent(id) ON DELETE CASCADE
);

CREATE OR REPLACE FUNCTION public.assert_rental_item_creation_photo_manifest()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  target_intent_id uuid;
  expected_count integer;
  actual_count integer;
  first_index integer;
  last_index integer;
BEGIN
  IF TG_TABLE_NAME = 'rental_item_creation_intent' THEN
    target_intent_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
  ELSE
    target_intent_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.intent_id ELSE NEW.intent_id END;
  END IF;

  SELECT expected_photo_count INTO expected_count
  FROM public.rental_item_creation_intent
  WHERE id = target_intent_id;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  SELECT count(*)::integer, min(photo_index), max(photo_index)
  INTO actual_count, first_index, last_index
  FROM public.rental_item_creation_photo
  WHERE intent_id = target_intent_id;
  IF actual_count <> expected_count OR first_index <> 0 OR last_index <> expected_count - 1 THEN
    RAISE EXCEPTION 'rental item creation photo manifest must be contiguous and match expected count'
      USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ck_rental_item_creation_intent_manifest
AFTER INSERT OR UPDATE OF expected_photo_count ON public.rental_item_creation_intent
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.assert_rental_item_creation_photo_manifest();

CREATE CONSTRAINT TRIGGER ck_rental_item_creation_photo_manifest
AFTER INSERT OR UPDATE OR DELETE ON public.rental_item_creation_photo
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.assert_rental_item_creation_photo_manifest();

CREATE INDEX idx_rental_item_creation_intent_pending
  ON public.rental_item_creation_intent(warehouse_id, created_at, id)
  WHERE state = 'PENDING';
