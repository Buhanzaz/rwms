-- Explicit completed-inventory dispositions retain their own immutable facts. Historical return
-- and shipment documents remain ordinary public read rows but never enter driver/hold workflows.

ALTER TABLE public.logistics_document
    ADD COLUMN inventory_source_id uuid,
    ADD COLUMN inventory_source_finding_id uuid,
    ADD COLUMN inventory_source_disposition_kind varchar(24),
    ADD COLUMN inventory_source_completed_at timestamptz,
    ADD COLUMN inventory_source_final_plan_version bigint,
    ADD COLUMN inventory_source_final_plan_sha256 char(64),
    ADD CONSTRAINT ck_logistics_document_inventory_source CHECK (
        (inventory_source_id IS NULL
            AND inventory_source_finding_id IS NULL
            AND inventory_source_disposition_kind IS NULL
            AND inventory_source_completed_at IS NULL
            AND inventory_source_final_plan_version IS NULL
            AND inventory_source_final_plan_sha256 IS NULL)
        OR
        (inventory_source_id IS NOT NULL
            AND inventory_source_finding_id IS NOT NULL
            AND inventory_source_disposition_kind IN ('LOCAL', 'SHIPMENT')
            AND inventory_source_completed_at IS NOT NULL
            AND inventory_source_final_plan_version > 0
            AND inventory_source_final_plan_sha256 ~ '^[0-9a-f]{64}$')
    );

CREATE UNIQUE INDEX uk_logistics_document_inventory_source
    ON public.logistics_document(
        inventory_source_id,
        inventory_source_final_plan_version,
        inventory_source_finding_id)
    WHERE inventory_source_id IS NOT NULL;

ALTER TABLE public.logistics_document_line
    DROP CONSTRAINT ck_logistics_document_line_inventory_status,
    ADD CONSTRAINT ck_logistics_document_line_inventory_status CHECK (
        inventory_desired_status IS NULL
        OR inventory_desired_status IN (
            'FREE', 'REPAIR', 'CAPITAL_REPAIR', 'RENTED', 'WRITE_OFF_PENDING')),
    ADD COLUMN inventory_source_id uuid,
    ADD COLUMN inventory_source_finding_id uuid,
    ADD COLUMN inventory_source_disposition_kind varchar(24),
    ADD COLUMN inventory_shipment_furniture jsonb,
    ADD CONSTRAINT ck_logistics_document_line_inventory_source CHECK (
        (inventory_source_id IS NULL
            AND inventory_source_finding_id IS NULL
            AND inventory_source_disposition_kind IS NULL
            AND inventory_shipment_furniture IS NULL)
        OR
        (inventory_source_id IS NOT NULL
            AND inventory_source_finding_id IS NOT NULL
            AND inventory_source_disposition_kind = 'LOCAL'
            AND inventory_shipment_furniture IS NULL)
        OR
        (inventory_source_id IS NOT NULL
            AND inventory_source_finding_id IS NOT NULL
            AND inventory_source_disposition_kind = 'SHIPMENT'
            AND jsonb_typeof(inventory_shipment_furniture) = 'array')
    );

ALTER TABLE public.rental_order_unit_term
    DROP CONSTRAINT ck_rental_order_unit_term_inventory_status,
    ADD CONSTRAINT ck_rental_order_unit_term_inventory_status CHECK (
        inventory_desired_status IS NULL
        OR inventory_desired_status IN (
            'FREE', 'REPAIR', 'CAPITAL_REPAIR', 'RENTED', 'WRITE_OFF_PENDING'));

ALTER TABLE public.inventory_outcome_receipt_asset
    DROP CONSTRAINT ck_inventory_outcome_receipt_asset_status,
    ADD COLUMN disposition_kind varchar(24) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN created_document_id uuid,
    ADD COLUMN created_line_id uuid,
    ADD CONSTRAINT ck_inventory_outcome_receipt_asset_status CHECK (
        desired_status IN (
            'FREE', 'REPAIR', 'CAPITAL_REPAIR', 'RENTED', 'WRITE_OFF_PENDING')),
    ADD CONSTRAINT ck_inventory_outcome_receipt_asset_disposition CHECK (
        (disposition_kind = 'LOCAL'
            AND desired_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR'))
        OR (disposition_kind = 'SHIPMENT' AND desired_status = 'RENTED')
        OR (disposition_kind = 'WRITE_OFF' AND desired_status = 'WRITE_OFF_PENDING')
    ),
    ADD CONSTRAINT ck_inventory_outcome_receipt_asset_document CHECK (
        (disposition_kind = 'WRITE_OFF'
            AND created_document_id IS NULL
            AND created_line_id IS NULL)
        OR (disposition_kind = 'SHIPMENT'
            AND created_document_id IS NOT NULL
            AND created_line_id IS NOT NULL)
        OR (disposition_kind = 'LOCAL'
            AND ((created_document_id IS NULL AND created_line_id IS NULL)
                OR (created_document_id IS NOT NULL AND created_line_id IS NOT NULL)))
    ),
    ADD CONSTRAINT fk_inventory_outcome_receipt_asset_created_document
        FOREIGN KEY(created_document_id) REFERENCES public.logistics_document(id),
    ADD CONSTRAINT fk_inventory_outcome_receipt_asset_created_line
        FOREIGN KEY(created_line_id) REFERENCES public.logistics_document_line(id);

ALTER TABLE public.inventory_outcome_receipt_asset
    ALTER COLUMN disposition_kind DROP DEFAULT;
