-- Subject-bound CustomerApp access for logistics shipment-line media.
-- Existing rows remain valid with a null subject; every new shipment proof is
-- required by application validation to carry its authoritative subject.

alter table media_service_owner_proof_checkpoint
    add column authorized_subject_id uuid;
alter table media_service_owner_proof_checkpoint
    add constraint media_service_owner_proof_checkpoint_authorized_subject_check
    check (authorized_subject_id is null or owner_type = 'LOGISTICS_SHIPMENT');

alter table media_service_owner_proof_receipt
    add column authorized_subject_id uuid;
alter table media_service_owner_proof_receipt
    add constraint media_service_owner_proof_receipt_authorized_subject_check
    check (authorized_subject_id is null or owner_type = 'LOGISTICS_SHIPMENT');

alter table media_owner_binding
    add column authorized_subject_id uuid;
alter table media_owner_binding
    add constraint media_owner_binding_authorized_subject_check
    check (authorized_subject_id is null or owner_type = 'LOGISTICS_SHIPMENT');

create index media_owner_binding_authorized_subject_idx
    on media_owner_binding (authorized_subject_id, owner_type, owner_id)
    where authorized_subject_id is not null and active;
