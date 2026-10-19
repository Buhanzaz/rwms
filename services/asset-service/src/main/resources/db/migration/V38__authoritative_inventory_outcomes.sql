CREATE TABLE public.inventory_asset_outcome_watermark (
  asset_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  inventory_completed_at timestamptz NOT NULL,
  final_plan_version bigint NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  finding_revision bigint NOT NULL,
  desired_status varchar(32) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_asset_outcome_watermark_pkey PRIMARY KEY (asset_id),
  CONSTRAINT fk_inventory_outcome_watermark_asset FOREIGN KEY (asset_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT ck_inventory_outcome_watermark_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_outcome_watermark_plan_version CHECK (final_plan_version >= 1),
  CONSTRAINT ck_inventory_outcome_watermark_finding_revision CHECK (finding_revision >= 1),
  CONSTRAINT ck_inventory_outcome_watermark_plan_sha CHECK (
    final_plan_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_outcome_watermark_status CHECK (
    desired_status IN ('FREE','REPAIR','CAPITAL_REPAIR'))
);

CREATE INDEX ix_inventory_outcome_watermark_source
  ON public.inventory_asset_outcome_watermark(inventory_id, finding_id);

CREATE TABLE public.inventory_asset_outcome_receipt (
  idempotency_key uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  inventory_completed_at timestamptz NOT NULL,
  final_plan_version bigint NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  finding_revision bigint NOT NULL,
  desired_status varchar(32) NOT NULL,
  response_asset_version bigint NOT NULL,
  response_status varchar(32) NOT NULL,
  released_operation_lease_ids varchar(16000) NOT NULL,
  released_order_unit_reservation_ids varchar(16000) NOT NULL,
  released_presentation_hold_ids varchar(16000) NOT NULL,
  transfer_superseded boolean NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_asset_outcome_receipt_pkey PRIMARY KEY (idempotency_key),
  CONSTRAINT fk_inventory_outcome_receipt_asset FOREIGN KEY (asset_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT ck_inventory_outcome_receipt_request_sha CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_outcome_receipt_plan_version CHECK (final_plan_version >= 1),
  CONSTRAINT ck_inventory_outcome_receipt_finding_revision CHECK (finding_revision >= 1),
  CONSTRAINT ck_inventory_outcome_receipt_plan_sha CHECK (
    final_plan_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_outcome_receipt_status CHECK (
    desired_status IN ('FREE','REPAIR','CAPITAL_REPAIR')),
  CONSTRAINT ck_inventory_outcome_receipt_response_version CHECK (response_asset_version >= 0),
  CONSTRAINT ck_inventory_outcome_receipt_response_status CHECK (
    response_status IN ('FREE','REPAIR','CAPITAL_REPAIR'))
);

CREATE INDEX ix_inventory_outcome_receipt_source
  ON public.inventory_asset_outcome_receipt(inventory_id, finding_id, created_at);
