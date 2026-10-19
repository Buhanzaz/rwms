-- Admit the canonical finding-membership lifecycle markers into the durable
-- inventory inbox. These facts do not carry owner-proof revisions: only the
-- dedicated owner-proof event may persist a non-null owner_revision.
-- Existing inbox rows and media data are not rewritten or deleted.

alter table media_inventory_finding_inbox
    drop constraint media_inventory_finding_inbox_check2;

alter table media_inventory_finding_inbox
    add constraint media_inventory_finding_inbox_check2
        check (
            (event_type = 'inventory.finding.owner-proof.v1'
                and owner_revision is not null)
            or (event_type in (
                    'inventory.finding.added.v1',
                    'inventory.finding.inspection-saved.v1',
                    'inventory.finding.membership-departed.v1',
                    'inventory.finding.membership-refreshed.v1',
                    'inventory.finding.membership-restored.v1'
                ) and owner_revision is null)
        );
