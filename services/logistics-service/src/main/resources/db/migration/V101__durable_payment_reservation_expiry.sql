ALTER TABLE public.rental_order_mutation_command
    ALTER COLUMN intent_json DROP NOT NULL,
    DROP CONSTRAINT ck_rental_order_mutation_command_operation,
    DROP CONSTRAINT ck_rental_order_mutation_command_step,
    DROP CONSTRAINT ck_rental_order_mutation_command_target,
    DROP CONSTRAINT ck_rental_order_mutation_command_intent,
    DROP CONSTRAINT ck_rental_order_mutation_command_receipts;

ALTER TABLE public.rental_order_mutation_command
    ADD CONSTRAINT ck_rental_order_mutation_command_operation CHECK (
        operation IN ('CANCEL_ORDER', 'REMOVE_UNIT', 'EXPIRE_UNPAID_ORDER')
    ),
    ADD CONSTRAINT ck_rental_order_mutation_command_step CHECK (
        step IN ('READ_UNITS', 'RELEASE_UNITS', 'RELEASE_EQUIPMENT', 'FINALIZE_LOCAL', 'COMPLETED')
    ),
    ADD CONSTRAINT ck_rental_order_mutation_command_target CHECK (
        (operation = 'CANCEL_ORDER' AND target_unit_id IS NULL)
        OR (operation = 'EXPIRE_UNPAID_ORDER' AND target_unit_id IS NULL
            AND warehouse_id IS NOT NULL AND actor_role = 'LOGISTICS_SERVICE')
        OR (operation = 'REMOVE_UNIT' AND target_unit_id IS NOT NULL AND warehouse_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_rental_order_mutation_command_intent CHECK (
        (
            (step = 'READ_UNITS' AND operation = 'EXPIRE_UNPAID_ORDER' AND intent_json IS NULL)
            OR (step <> 'READ_UNITS' AND intent_json IS NOT NULL AND length(btrim(intent_json)) > 0)
        )
        AND (released_units_receipt_json IS NULL OR length(btrim(released_units_receipt_json)) > 0)
        AND (equipment_receipt_json IS NULL OR length(btrim(equipment_receipt_json)) > 0)
    ),
    ADD CONSTRAINT ck_rental_order_mutation_command_receipts CHECK (
        (step IN ('READ_UNITS', 'RELEASE_UNITS')
            AND released_units_receipt_json IS NULL AND equipment_receipt_json IS NULL)
        OR (step = 'RELEASE_EQUIPMENT' AND equipment_release_required
            AND released_units_receipt_json IS NOT NULL AND equipment_receipt_json IS NULL)
        OR (step IN ('FINALIZE_LOCAL', 'COMPLETED') AND released_units_receipt_json IS NOT NULL
            AND ((equipment_release_required AND equipment_receipt_json IS NOT NULL)
                OR (NOT equipment_release_required AND equipment_receipt_json IS NULL)))
    );

-- Provenance only: the technical role is never granted public order access or creation rights.
ALTER TABLE public.rental_order_audit_event
    DROP CONSTRAINT ck_rental_order_audit_actor_role,
    ADD CONSTRAINT ck_rental_order_audit_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER', 'CUSTOMER', 'LOGISTICS_SERVICE'
        )
    );
