ALTER TABLE public.logistics_document_line
    ADD COLUMN transfer_asset_status varchar(16),
    ADD COLUMN active_repair_id uuid,
    ADD COLUMN active_repair_version bigint,
    ADD COLUMN repair_continuation_priority integer,
    ADD COLUMN movement_to_shipment boolean,
    ADD COLUMN maintenance_prepared_at timestamptz,
    ADD COLUMN maintenance_arrival_completed_at timestamptz;

ALTER TABLE public.logistics_document_line
    ADD CONSTRAINT ck_logistics_document_line_transfer_asset_status
        CHECK (transfer_asset_status IS NULL OR transfer_asset_status IN ('FREE', 'REPAIR')),
    ADD CONSTRAINT ck_logistics_document_line_active_repair_version
        CHECK (active_repair_version IS NULL OR active_repair_version >= 0),
    ADD CONSTRAINT ck_logistics_document_line_repair_continuation_priority
        CHECK (
            repair_continuation_priority IS NULL
            OR repair_continuation_priority BETWEEN 1 AND 5
        ),
    ADD CONSTRAINT ck_logistics_document_line_repair_truth
        CHECK (
            (maintenance_prepared_at IS NULL
                AND transfer_asset_status IS NULL
                AND active_repair_id IS NULL
                AND active_repair_version IS NULL)
            OR
            (maintenance_prepared_at IS NOT NULL
                AND transfer_asset_status = 'FREE'
                AND active_repair_id IS NULL
                AND active_repair_version IS NULL)
            OR
            (maintenance_prepared_at IS NOT NULL
                AND transfer_asset_status = 'REPAIR'
                AND active_repair_id IS NOT NULL
                AND active_repair_version IS NOT NULL)
        ),
    ADD CONSTRAINT ck_logistics_document_line_repair_continuation
        CHECK (
            (active_repair_id IS NULL
                AND repair_continuation_priority IS NULL
                AND COALESCE(movement_to_shipment, false) = false
                AND maintenance_arrival_completed_at IS NULL)
            OR
            (active_repair_id IS NOT NULL
                AND (
                    (repair_continuation_priority IS NULL
                        AND movement_to_shipment IS NULL
                        AND maintenance_arrival_completed_at IS NULL)
                    OR
                    (repair_continuation_priority IS NOT NULL
                        AND movement_to_shipment IS NOT NULL)
                ))
        );
