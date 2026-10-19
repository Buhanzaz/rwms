-- A completed-inventory correction may restore an explicitly observed finding that the obsolete
-- automatic-membership rule deactivated. The event remains finding-owned audit evidence and does
-- not reopen the completed session's media owner authorization.
ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_inventory_domain_event_type,
  DROP CONSTRAINT ck_inventory_domain_event_family,
  ADD CONSTRAINT ck_inventory_domain_event_type CHECK (event_type IN (
    'inventory.session.started.v1','inventory.finding.added.v1',
    'inventory.finding.inspection-saved.v1','inventory.finding.owner-proof.v1',
    'inventory.finding.membership-departed.v1','inventory.finding.membership-refreshed.v1',
    'inventory.finding.membership-restored.v1',
    'inventory.session.completed.v1','inventory.session.cancelled.v1',
    'inventory.publication.ready.v1','inventory.publication.requested.v1',
    'inventory.publication.succeeded.v1','inventory.publication.transient-failed.v1',
    'inventory.publication.blocked.v1','inventory.publication.closed-blocked.v1')),
  ADD CONSTRAINT ck_inventory_domain_event_family CHECK (
    (aggregate_type = 'SESSION' AND event_type IN (
      'inventory.session.started.v1','inventory.session.completed.v1','inventory.session.cancelled.v1'))
    OR (aggregate_type = 'FINDING' AND event_type IN (
      'inventory.finding.added.v1','inventory.finding.inspection-saved.v1',
      'inventory.finding.owner-proof.v1','inventory.finding.membership-departed.v1',
      'inventory.finding.membership-refreshed.v1','inventory.finding.membership-restored.v1'))
    OR (aggregate_type = 'PUBLICATION' AND event_type LIKE 'inventory.publication.%'));
