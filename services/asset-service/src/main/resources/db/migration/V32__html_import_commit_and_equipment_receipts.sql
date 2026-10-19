-- HTML import remains a bounded legacy intake path.  Its commit intent must
-- survive a lost media response, while initial furniture is evidence rather
-- than a mutable balance snapshot.

ALTER TABLE public.rental_item_html_import
  ADD COLUMN commit_actor_subject_id uuid,
  ADD COLUMN commit_idempotency_key uuid,
  ADD COLUMN commit_request_sha256 varchar(64),
  ADD CONSTRAINT ck_rental_item_html_import_commit_request_sha256 CHECK (
    commit_request_sha256 IS NULL
    OR commit_request_sha256 ~ '^[0-9a-f]{64}$'
  );

CREATE TABLE public.rental_item_html_import_equipment_receipt (
  id uuid NOT NULL,
  import_id uuid NOT NULL,
  source_row_id varchar(64) NOT NULL,
  actor_subject_id uuid NOT NULL,
  reason varchar(64) NOT NULL,
  rental_item_id uuid NOT NULL,
  equipment_id uuid NOT NULL,
  target_balance_id uuid NOT NULL,
  quantity bigint NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT rental_item_html_import_equipment_receipt_pkey PRIMARY KEY (id),
  CONSTRAINT uk_html_import_equipment_receipt_source_equipment
    UNIQUE (import_id, source_row_id, equipment_id),
  CONSTRAINT ck_html_import_equipment_receipt_reason CHECK (
    reason = 'HTML_IMPORT_INITIAL_CONTENTS'),
  CONSTRAINT ck_html_import_equipment_receipt_quantity CHECK (quantity > 0),
  CONSTRAINT fk_html_import_equipment_receipt_import FOREIGN KEY (import_id)
    REFERENCES public.rental_item_html_import(id),
  CONSTRAINT fk_html_import_equipment_receipt_source_row
    FOREIGN KEY (import_id, source_row_id)
    REFERENCES public.rental_item_html_import_row(import_id, source_row_id),
  CONSTRAINT fk_html_import_equipment_receipt_rental_item FOREIGN KEY (rental_item_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT fk_html_import_equipment_receipt_equipment FOREIGN KEY (equipment_id)
    REFERENCES public.equipment_catalog_item(id),
  CONSTRAINT fk_html_import_equipment_receipt_target_balance FOREIGN KEY (target_balance_id)
    REFERENCES public.equipment_balance(id)
);

CREATE INDEX idx_html_import_equipment_receipt_rental_item
  ON public.rental_item_html_import_equipment_receipt(rental_item_id, recorded_at, id);

CREATE FUNCTION public.prevent_html_import_equipment_receipt_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'HTML import equipment receipt audit cannot be truncated';
END;
$$;

CREATE TRIGGER trg_html_import_equipment_receipt_immutable
BEFORE UPDATE OR DELETE ON public.rental_item_html_import_equipment_receipt
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_html_import_equipment_receipt_no_truncate
BEFORE TRUNCATE ON public.rental_item_html_import_equipment_receipt
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_html_import_equipment_receipt_truncate();
