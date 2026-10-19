-- Adopt workforce projections that have no event stream, without inventing past events,
-- changing operational data or publishing baseline snapshots as new business commands.
LOCK TABLE public.worker, public.worker_group, public.worker_class_assignment,
  public.worker_group_member IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE workforce_stream_adoption ON COMMIT DROP AS
SELECT 'WORKER'::varchar(64) AS aggregate_type, item.id::text AS aggregate_id,
  item.version AS aggregate_version,
  jsonb_build_object(
    'workerId', item.id, 'warehouseId', item.warehouse_id, 'active', item.active,
    'profileRevision', item.revision_marker, 'currentGroupId', item.current_group_id,
    'qualifications', COALESCE((
      SELECT jsonb_agg(jsonb_build_object(
        'assignmentId', qualification.id, 'version', qualification.version,
        'workerClassId', qualification.worker_class_id, 'active', qualification.active)
        ORDER BY qualification.id)
      FROM public.worker_class_assignment qualification WHERE qualification.worker_id = item.id
    ), '[]'::jsonb), 'deleted', false) AS payload
FROM public.worker item
WHERE NOT EXISTS (SELECT 1 FROM public.event_stream_head head
  WHERE head.aggregate_type = 'WORKER' AND head.aggregate_id = item.id::text)
UNION ALL
SELECT 'WORKER_GROUP', item.id::text, item.version,
  jsonb_build_object(
    'workerGroupId', item.id, 'revisionMarker', item.revision_marker,
    'warehouseId', item.warehouse_id, 'workerClassId', item.worker_class_id,
    'active', item.active, 'operationalStatus', item.operational_status,
    'members', COALESCE((
      SELECT jsonb_agg(jsonb_build_object(
        'membershipId', member.id, 'version', member.version,
        'workerId', member.worker_id, 'active', member.active) ORDER BY member.id)
      FROM public.worker_group_member member WHERE member.worker_group_id = item.id
    ), '[]'::jsonb), 'deleted', false)
FROM public.worker_group item
WHERE NOT EXISTS (SELECT 1 FROM public.event_stream_head head
  WHERE head.aggregate_type = 'WORKER_GROUP' AND head.aggregate_id = item.id::text);

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM workforce_stream_adoption candidate
    JOIN (
      SELECT aggregate_type, aggregate_id FROM public.domain_event
      UNION ALL SELECT aggregate_type, aggregate_id FROM public.aggregate_snapshot
      UNION ALL SELECT aggregate_type, aggregate_id FROM public.projection_checkpoint
      UNION ALL SELECT aggregate_type, aggregate_id FROM public.outbox_event
    ) evidence USING (aggregate_type, aggregate_id)
  ) THEN
    RAISE EXCEPTION 'Cannot adopt workforce projection with existing event evidence but no stream head';
  END IF;
END;
$$;

INSERT INTO public.event_stream_head(
  aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
SELECT aggregate_type, aggregate_id, aggregate_version,
  md5(aggregate_type || ':' || aggregate_id || ':' || aggregate_version || ':baseline.v1')::uuid,
  transaction_timestamp()
FROM workforce_stream_adoption;

INSERT INTO public.domain_event(
  event_id, aggregate_type, aggregate_id, aggregate_version, event_type, event_version,
  occurred_at, recorded_at, correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline)
SELECT head.last_event_id, candidate.aggregate_type, candidate.aggregate_id,
  candidate.aggregate_version,
  'task-board.' || replace(lower(candidate.aggregate_type), '_', '-') || '.baseline.v1',
  1, NULL, head.updated_at, md5(head.last_event_id::text || ':correlation')::uuid, NULL, NULL,
  candidate.payload, encode(sha256(convert_to(candidate.payload::text, 'UTF8')), 'hex'), true
FROM workforce_stream_adoption candidate
JOIN public.event_stream_head head USING (aggregate_type, aggregate_id);

INSERT INTO public.projection_checkpoint(
  projection_name, aggregate_type, aggregate_id, aggregate_version, projection_sha256, updated_at)
SELECT 'task-board-live-v1', candidate.aggregate_type, candidate.aggregate_id,
  candidate.aggregate_version, event.payload_sha256, event.recorded_at
FROM workforce_stream_adoption candidate
JOIN public.domain_event event USING (aggregate_type, aggregate_id, aggregate_version);
