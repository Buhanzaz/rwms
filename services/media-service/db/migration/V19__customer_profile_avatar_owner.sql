-- CustomerApp profile avatars are media-owned bytes under a non-structured
-- logistics customer-profile owner. Existing media, proofs and objects remain
-- unchanged; the new owner always requires an exact authorized subject.

alter table media_owner_binding
    drop constraint media_owner_binding_owner_proof_scope_check;
alter table media_owner_binding
    drop constraint media_owner_binding_owner_identity_check;
alter table media_owner_binding
    add constraint media_owner_binding_owner_proof_scope_check check (
        (owner_type = 'INVENTORY_FINDING' and proof_aggregate_type = 'FINDING')
        or (owner_type = 'CABIN' and proof_aggregate_type = 'RENTAL_ITEM')
        or (owner_type = 'MAINTENANCE_ESTIMATE' and proof_aggregate_type = 'ESTIMATE')
        or (owner_type = 'MAINTENANCE_REPAIR' and proof_aggregate_type = 'REPAIR')
        or (owner_type = 'MAINTENANCE_ACCEPTANCE' and proof_aggregate_type = 'ACCEPTANCE')
        or (owner_type = 'MAINTENANCE_CATALOG_NODE' and proof_aggregate_type = 'CATALOG_NODE')
        or (owner_type = 'LOGISTICS_RETURN' and proof_aggregate_type = 'RETURN')
        or (owner_type = 'LOGISTICS_SHIPMENT' and proof_aggregate_type = 'SHIPMENT')
        or (owner_type = 'LOGISTICS_TRANSFER' and proof_aggregate_type = 'TRANSFER')
        or (owner_type = 'LOGISTICS_CUSTOMER_PROFILE' and proof_aggregate_type = 'CUSTOMER_PROFILE')
        or (owner_type = 'TASK_BOARD_ENTRY' and proof_aggregate_type = 'TASK_BOARD_ENTRY_OWNER_PROOF')
    );
alter table media_owner_binding
    add constraint media_owner_binding_owner_identity_check check (
        (owner_type in (
            'INVENTORY_FINDING','CABIN','MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
            'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE','LOGISTICS_CUSTOMER_PROFILE',
            'TASK_BOARD_ENTRY'
        ) and owner_id = proof_aggregate_id::text)
        or (owner_type in (
            'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
        ) and owner_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    );

alter table media_service_owner_proof_checkpoint
    drop constraint media_service_owner_proof_checkpoint_check;
alter table media_service_owner_proof_checkpoint
    drop constraint media_service_owner_proof_checkpoint_check1;
alter table media_service_owner_proof_checkpoint
    add constraint media_service_owner_proof_checkpoint_check check (
        (source_service = 'maintenance-service'
            and consumer_name = 'media-service-maintenance-owner-proof-v1'
            and owner_type in (
                'MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
                'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE'
            )
            and document_id is null and line_id is null)
        or (source_service = 'logistics-service'
            and consumer_name = 'media-service-logistics-owner-proof-v1'
            and owner_type in (
                'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
            )
            and document_id is not null and line_id is not null
            and owner_id = document_id::text || ':' || line_id::text)
        or (source_service = 'logistics-service'
            and consumer_name = 'media-service-logistics-owner-proof-v1'
            and owner_type = 'LOGISTICS_CUSTOMER_PROFILE'
            and document_id is null and line_id is null
            and owner_id = aggregate_id::text)
    );
alter table media_service_owner_proof_checkpoint
    add constraint media_service_owner_proof_checkpoint_check1 check (
        (owner_type = 'MAINTENANCE_ESTIMATE' and aggregate_type = 'ESTIMATE')
        or (owner_type = 'MAINTENANCE_REPAIR' and aggregate_type = 'REPAIR')
        or (owner_type = 'MAINTENANCE_ACCEPTANCE' and aggregate_type = 'ACCEPTANCE')
        or (owner_type = 'MAINTENANCE_CATALOG_NODE' and aggregate_type = 'CATALOG_NODE')
        or (owner_type = 'LOGISTICS_RETURN' and aggregate_type = 'RETURN')
        or (owner_type = 'LOGISTICS_SHIPMENT' and aggregate_type = 'SHIPMENT')
        or (owner_type = 'LOGISTICS_TRANSFER' and aggregate_type = 'TRANSFER')
        or (owner_type = 'LOGISTICS_CUSTOMER_PROFILE' and aggregate_type = 'CUSTOMER_PROFILE')
    );

alter table media_service_owner_proof_receipt
    drop constraint media_service_owner_proof_receipt_check1;
alter table media_service_owner_proof_receipt
    add constraint media_service_owner_proof_receipt_check1 check (
        (source_service = 'maintenance-service'
            and consumer_name = 'media-service-maintenance-owner-proof-v1'
            and owner_type in (
                'MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
                'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE'
            )
            and document_id is null and line_id is null)
        or (source_service = 'logistics-service'
            and consumer_name = 'media-service-logistics-owner-proof-v1'
            and owner_type in (
                'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
            )
            and document_id is not null and line_id is not null
            and owner_id = document_id::text || ':' || line_id::text)
        or (source_service = 'logistics-service'
            and consumer_name = 'media-service-logistics-owner-proof-v1'
            and owner_type = 'LOGISTICS_CUSTOMER_PROFILE'
            and document_id is null and line_id is null
            and owner_id = aggregate_id::text)
    );

alter table media_service_owner_proof_checkpoint
    drop constraint media_service_owner_proof_checkpoint_authorized_subject_check;
alter table media_service_owner_proof_checkpoint
    add constraint media_service_owner_proof_checkpoint_authorized_subject_check check (
        (owner_type = 'LOGISTICS_CUSTOMER_PROFILE' and authorized_subject_id is not null)
        or (owner_type = 'LOGISTICS_SHIPMENT')
        or (owner_type not in ('LOGISTICS_CUSTOMER_PROFILE','LOGISTICS_SHIPMENT')
            and authorized_subject_id is null)
    );

alter table media_service_owner_proof_receipt
    drop constraint media_service_owner_proof_receipt_authorized_subject_check;
alter table media_service_owner_proof_receipt
    add constraint media_service_owner_proof_receipt_authorized_subject_check check (
        (owner_type = 'LOGISTICS_CUSTOMER_PROFILE' and authorized_subject_id is not null)
        or (owner_type = 'LOGISTICS_SHIPMENT')
        or (owner_type not in ('LOGISTICS_CUSTOMER_PROFILE','LOGISTICS_SHIPMENT')
            and authorized_subject_id is null)
    );

alter table media_owner_binding
    drop constraint media_owner_binding_authorized_subject_check;
alter table media_owner_binding
    add constraint media_owner_binding_authorized_subject_check check (
        (owner_type = 'LOGISTICS_CUSTOMER_PROFILE' and authorized_subject_id is not null)
        or (owner_type = 'LOGISTICS_SHIPMENT')
        or (owner_type not in ('LOGISTICS_CUSTOMER_PROFILE','LOGISTICS_SHIPMENT')
            and authorized_subject_id is null)
    );
