-- Asset owns the hard reservation of physical cabins selected for a confirmed
-- inter-warehouse transfer.  Rows remain as immutable-owner history after
-- release or departure consumption; rental_item.status is the availability fence.

CREATE TABLE public.transfer_unit_reservation (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  transfer_id uuid NOT NULL,
  line_id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  source_warehouse_id uuid NOT NULL,
  reserved_rental_item_version bigint NOT NULL,
  terminal_rental_item_version bigint,
  state varchar(16) NOT NULL,
  confirmed_by_subject_id uuid NOT NULL,
  confirmed_idempotency_key uuid NOT NULL,
  terminal_by_subject_id uuid,
  terminal_idempotency_key uuid,
  confirmed_at timestamptz NOT NULL,
  terminal_at timestamptz,
  updated_at timestamptz NOT NULL,
  CONSTRAINT transfer_unit_reservation_pkey PRIMARY KEY (id),
  CONSTRAINT fk_transfer_unit_reservation_rental_item
    FOREIGN KEY (rental_item_id) REFERENCES public.rental_item(id),
  CONSTRAINT uk_transfer_unit_reservation_line UNIQUE (transfer_id, line_id),
  CONSTRAINT ck_transfer_unit_reservation_version CHECK (version >= 0),
  CONSTRAINT ck_transfer_unit_reservation_rental_versions CHECK (
    reserved_rental_item_version >= 0
    AND (terminal_rental_item_version IS NULL OR terminal_rental_item_version >= 0)),
  CONSTRAINT ck_transfer_unit_reservation_state CHECK (
    state IN ('ACTIVE','RELEASED','CONSUMED')),
  CONSTRAINT ck_transfer_unit_reservation_terminal CHECK (
    (
      state = 'ACTIVE'
      AND terminal_rental_item_version IS NULL
      AND terminal_by_subject_id IS NULL
      AND terminal_idempotency_key IS NULL
      AND terminal_at IS NULL
    )
    OR
    (
      state IN ('RELEASED','CONSUMED')
      AND terminal_rental_item_version IS NOT NULL
      AND terminal_by_subject_id IS NOT NULL
      AND terminal_idempotency_key IS NOT NULL
      AND terminal_at IS NOT NULL
    )
  ),
  CONSTRAINT ck_transfer_unit_reservation_timestamps CHECK (
    updated_at >= confirmed_at
    AND (terminal_at IS NULL OR terminal_at >= confirmed_at))
);

CREATE UNIQUE INDEX uk_transfer_unit_reservation_active_item
  ON public.transfer_unit_reservation(rental_item_id)
  WHERE state = 'ACTIVE';

CREATE INDEX idx_transfer_unit_reservation_transfer_state
  ON public.transfer_unit_reservation(transfer_id, state, line_id);

CREATE FUNCTION public.protect_transfer_unit_reservation_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF (NEW.id, NEW.transfer_id, NEW.line_id, NEW.rental_item_id,
      NEW.source_warehouse_id, NEW.reserved_rental_item_version,
      NEW.confirmed_by_subject_id, NEW.confirmed_idempotency_key, NEW.confirmed_at)
     IS DISTINCT FROM
     (OLD.id, OLD.transfer_id, OLD.line_id, OLD.rental_item_id,
      OLD.source_warehouse_id, OLD.reserved_rental_item_version,
      OLD.confirmed_by_subject_id, OLD.confirmed_idempotency_key, OLD.confirmed_at) THEN
    RAISE EXCEPTION 'transfer unit reservation ownership history is immutable';
  END IF;
  IF OLD.state <> 'ACTIVE' THEN
    RAISE EXCEPTION 'terminal transfer unit reservation history is immutable';
  END IF;
  IF NEW.state NOT IN ('RELEASED','CONSUMED') THEN
    RAISE EXCEPTION 'transfer unit reservation must make one terminal transition';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transfer_unit_reservation_history
BEFORE UPDATE ON public.transfer_unit_reservation
FOR EACH ROW EXECUTE FUNCTION public.protect_transfer_unit_reservation_history();

CREATE TRIGGER trg_transfer_unit_reservation_no_delete
BEFORE DELETE ON public.transfer_unit_reservation
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
