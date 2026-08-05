-- V34 may already be applied in a running maintenance database, so event-store expansion and
-- immutable attempt auditing are delivered append-only here instead of rewriting its checksum.

ALTER TABLE public.event_stream_head
  DROP CONSTRAINT ck_maintenance_stream_type,
  ADD CONSTRAINT ck_maintenance_stream_type CHECK (
    aggregate_type IN ('CATALOG_VERSION', 'ESTIMATE', 'REPAIR', 'PROPERTY_DISPOSITION'));

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_maintenance_event_type,
  ADD CONSTRAINT ck_maintenance_event_type CHECK (event_type IN (
    'maintenance.catalog-version.imported.v1',
    'maintenance.catalog-version.changed.v1',
    'maintenance.catalog-version.activated.v1',
    'maintenance.catalog-version.superseded.v1',
    'maintenance.estimate.created.v1',
    'maintenance.estimate.draft-changed.v1',
    'maintenance.estimate.completed.v1',
    'maintenance.estimate.amended.v1',
    'maintenance.repair.created.v1',
    'maintenance.repair.plan-changed.v1',
    'maintenance.repair.queued.v1',
    'maintenance.repair.stage-completed.v1',
    'maintenance.repair.pending-acceptance.v1',
    'maintenance.repair.rework-created.v1',
    'maintenance.repair.transfer-prepared.v1',
    'maintenance.repair.transferred.v1',
    'maintenance.repair.accepted.v1',
    'maintenance.repair.written-off.v1',
    'maintenance.property-disposition.requested.v1',
    'maintenance.property-disposition.approved.v1',
    'maintenance.property-disposition.rejected.v1',
    'maintenance.property-disposition.movement-pending.v1',
    'maintenance.property-disposition.effect-pending.v1',
    'maintenance.property-disposition.effective.v1',
    'maintenance.property-disposition.quarantined.v1',
    'maintenance.property-disposition.recovered.v1'
  ));

ALTER TABLE public.outbox_event
  DROP CONSTRAINT ck_maintenance_outbox_topic,
  ADD CONSTRAINT ck_maintenance_outbox_topic CHECK (topic IN (
    'rwms.maintenance.catalog-version.v1',
    'rwms.maintenance.estimate.v1',
    'rwms.maintenance.repair.v1',
    'rwms.maintenance.property-disposition.v1'
  ));

CREATE FUNCTION public.reject_property_disposition_processing_attempt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'property_disposition_processing_attempt rows are immutable';
END;
$$;

CREATE TRIGGER property_disposition_processing_attempt_immutable
BEFORE UPDATE OR DELETE ON public.property_disposition_processing_attempt
FOR EACH ROW
EXECUTE FUNCTION public.reject_property_disposition_processing_attempt_mutation();
