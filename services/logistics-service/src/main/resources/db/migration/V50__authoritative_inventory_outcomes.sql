-- A completed inventory is the latest physical truth for the selected cabins. Logistics keeps
-- every historical row, but marks displaced documents, order terms and local workflow guards so
-- they cannot remain active after inventory publication.

ALTER TABLE public.logistics_document
    ADD COLUMN inventory_superseded_by uuid,
    ADD COLUMN inventory_superseded_at timestamptz,
    ADD COLUMN inventory_completed_at timestamptz,
    ADD COLUMN inventory_final_plan_version bigint,
    ADD COLUMN inventory_final_plan_sha256 char(64),
    ADD CONSTRAINT ck_logistics_document_inventory_supersession CHECK (
        (inventory_superseded_by IS NULL
            AND inventory_superseded_at IS NULL
            AND inventory_completed_at IS NULL
            AND inventory_final_plan_version IS NULL
            AND inventory_final_plan_sha256 IS NULL)
        OR
        (inventory_superseded_by IS NOT NULL
            AND inventory_superseded_at IS NOT NULL
            AND inventory_completed_at IS NOT NULL
            AND inventory_final_plan_version > 0
            AND inventory_final_plan_sha256 ~ '^[0-9a-f]{64}$')
    );

ALTER TABLE public.logistics_document_line
    ADD COLUMN inventory_superseded_by uuid,
    ADD COLUMN inventory_finding_id uuid,
    ADD COLUMN inventory_desired_status varchar(24),
    ADD COLUMN inventory_superseded_at timestamptz,
    ADD COLUMN inventory_completed_at timestamptz,
    ADD COLUMN inventory_final_plan_version bigint,
    ADD COLUMN inventory_final_plan_sha256 char(64),
    ADD CONSTRAINT ck_logistics_document_line_inventory_status CHECK (
        inventory_desired_status IS NULL
        OR inventory_desired_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR')
    ),
    ADD CONSTRAINT ck_logistics_document_line_inventory_supersession CHECK (
        (inventory_superseded_by IS NULL
            AND inventory_finding_id IS NULL
            AND inventory_desired_status IS NULL
            AND inventory_superseded_at IS NULL
            AND inventory_completed_at IS NULL
            AND inventory_final_plan_version IS NULL
            AND inventory_final_plan_sha256 IS NULL)
        OR
        (inventory_superseded_by IS NOT NULL
            AND inventory_finding_id IS NOT NULL
            AND inventory_desired_status IS NOT NULL
            AND inventory_superseded_at IS NOT NULL
            AND inventory_completed_at IS NOT NULL
            AND inventory_final_plan_version > 0
            AND inventory_final_plan_sha256 ~ '^[0-9a-f]{64}$')
    );

CREATE INDEX idx_logistics_document_line_active_asset
    ON public.logistics_document_line(asset_id, document_id)
    WHERE inventory_superseded_by IS NULL;

ALTER TABLE public.logistics_guard
    ADD COLUMN inventory_superseded_by uuid,
    ADD COLUMN inventory_superseded_at timestamptz,
    ADD CONSTRAINT ck_logistics_guard_inventory_supersession CHECK (
        (inventory_superseded_by IS NULL AND inventory_superseded_at IS NULL)
        OR (inventory_superseded_by IS NOT NULL AND inventory_superseded_at IS NOT NULL)
    );

ALTER TABLE public.rental_order
    ADD COLUMN inventory_superseded_by uuid,
    ADD COLUMN inventory_superseded_at timestamptz,
    ADD COLUMN inventory_completed_at timestamptz,
    ADD CONSTRAINT ck_rental_order_inventory_supersession CHECK (
        (inventory_superseded_by IS NULL
            AND inventory_superseded_at IS NULL
            AND inventory_completed_at IS NULL)
        OR
        (inventory_superseded_by IS NOT NULL
            AND inventory_superseded_at IS NOT NULL
            AND inventory_completed_at IS NOT NULL)
    );

ALTER TABLE public.rental_order_unit_term
    ADD COLUMN inventory_superseded_by uuid,
    ADD COLUMN inventory_finding_id uuid,
    ADD COLUMN inventory_desired_status varchar(24),
    ADD COLUMN inventory_superseded_at timestamptz,
    ADD COLUMN inventory_completed_at timestamptz,
    ADD COLUMN inventory_final_plan_version bigint,
    ADD COLUMN inventory_final_plan_sha256 char(64),
    ADD CONSTRAINT ck_rental_order_unit_term_inventory_status CHECK (
        inventory_desired_status IS NULL
        OR inventory_desired_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR')
    ),
    ADD CONSTRAINT ck_rental_order_unit_term_inventory_supersession CHECK (
        (inventory_superseded_by IS NULL
            AND inventory_finding_id IS NULL
            AND inventory_desired_status IS NULL
            AND inventory_superseded_at IS NULL
            AND inventory_completed_at IS NULL
            AND inventory_final_plan_version IS NULL
            AND inventory_final_plan_sha256 IS NULL)
        OR
        (inventory_superseded_by IS NOT NULL
            AND inventory_finding_id IS NOT NULL
            AND inventory_desired_status IS NOT NULL
            AND inventory_superseded_at IS NOT NULL
            AND inventory_completed_at IS NOT NULL
            AND inventory_final_plan_version > 0
            AND inventory_final_plan_sha256 ~ '^[0-9a-f]{64}$')
    );

CREATE INDEX idx_rental_order_unit_term_active_item
    ON public.rental_order_unit_term(rental_item_id, order_id)
    WHERE inventory_superseded_by IS NULL;

ALTER TABLE public.driver_logistics_task
    ADD COLUMN inventory_cancelled_by uuid,
    ADD COLUMN inventory_cancelled_at timestamptz,
    ADD CONSTRAINT ck_driver_logistics_task_inventory_cancellation CHECK (
        (inventory_cancelled_by IS NULL AND inventory_cancelled_at IS NULL)
        OR (inventory_cancelled_by IS NOT NULL AND inventory_cancelled_at IS NOT NULL)
    );

CREATE TABLE public.inventory_outcome_receipt (
    id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL,
    inventory_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    inventory_completed_at timestamptz NOT NULL,
    final_plan_version bigint NOT NULL,
    final_plan_sha256 char(64) NOT NULL,
    request_sha256 char(64) NOT NULL,
    request_json jsonb NOT NULL,
    superseded_document_ids jsonb NOT NULL,
    superseded_rental_order_ids jsonb NOT NULL,
    response_json jsonb,
    state varchar(24) NOT NULL,
    superseded_line_count bigint NOT NULL DEFAULT 0,
    superseded_rental_unit_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT uk_inventory_outcome_receipt_key UNIQUE(idempotency_key),
    CONSTRAINT ck_inventory_outcome_receipt_versions CHECK (
        final_plan_version > 0
        AND superseded_line_count >= 0
        AND superseded_rental_unit_count >= 0
    ),
    CONSTRAINT ck_inventory_outcome_receipt_hashes CHECK (
        final_plan_sha256 ~ '^[0-9a-f]{64}$'
        AND request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_inventory_outcome_receipt_state CHECK (state IN ('PREPARED', 'COMPLETED')),
    CONSTRAINT ck_inventory_outcome_receipt_completion CHECK (
        (state='PREPARED' AND response_json IS NULL AND completed_at IS NULL)
        OR (state='COMPLETED' AND response_json IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_inventory_outcome_receipt_source
    ON public.inventory_outcome_receipt(inventory_id, final_plan_version, created_at, id);

CREATE TABLE public.inventory_outcome_receipt_asset (
    id uuid PRIMARY KEY,
    receipt_id uuid NOT NULL,
    finding_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    desired_status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_inventory_outcome_receipt_asset_receipt
        FOREIGN KEY(receipt_id) REFERENCES public.inventory_outcome_receipt(id),
    CONSTRAINT uk_inventory_outcome_receipt_asset_finding UNIQUE(receipt_id, finding_id),
    CONSTRAINT uk_inventory_outcome_receipt_asset_asset UNIQUE(receipt_id, asset_id),
    CONSTRAINT ck_inventory_outcome_receipt_asset_status
        CHECK (desired_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR'))
);

CREATE INDEX idx_inventory_outcome_receipt_asset_lookup
    ON public.inventory_outcome_receipt_asset(receipt_id, asset_id);

CREATE TABLE public.inventory_asset_outcome_watermark (
    asset_id uuid PRIMARY KEY,
    warehouse_id uuid NOT NULL,
    inventory_completed_at timestamptz NOT NULL,
    inventory_id uuid NOT NULL,
    final_plan_version bigint NOT NULL,
    final_plan_sha256 char(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_inventory_asset_outcome_watermark_version CHECK (final_plan_version > 0),
    CONSTRAINT ck_inventory_asset_outcome_watermark_hash
        CHECK (final_plan_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_inventory_asset_outcome_watermark_source
    ON public.inventory_asset_outcome_watermark(inventory_completed_at, inventory_id);

CREATE TABLE public.inventory_outcome_task_action (
    id uuid PRIMARY KEY,
    receipt_id uuid NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    state varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT fk_inventory_outcome_task_action_receipt
        FOREIGN KEY(receipt_id) REFERENCES public.inventory_outcome_receipt(id),
    CONSTRAINT uk_inventory_outcome_task_action UNIQUE(receipt_id, target_type, target_id),
    CONSTRAINT ck_inventory_outcome_task_action_type CHECK (
        target_type IN ('DRIVER_TASK', 'DOCUMENT_TASK', 'EQUIPMENT_TASK', 'GUARD_LEASE')
    ),
    CONSTRAINT ck_inventory_outcome_task_action_state CHECK (
        state IN ('PENDING', 'CANCELLED', 'PRESERVED', 'DURABLE_CANCELLING', 'RELEASED')
    ),
    CONSTRAINT ck_inventory_outcome_task_action_completion CHECK (
        (state='PENDING' AND completed_at IS NULL)
        OR (state<>'PENDING' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_inventory_outcome_task_action_pending
    ON public.inventory_outcome_task_action(receipt_id, state, target_type, target_id);
