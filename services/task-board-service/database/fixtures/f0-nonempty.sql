-- Sanitized compatibility graph. It deliberately exercises nullable fields,
-- representative persisted lifecycle values and two warehouse identities without inventing
-- production data.
INSERT INTO worker_class(id, version, revision_marker, code, name, description, comment_text, sort_order, active)
VALUES
  ('10000000-0000-0000-0000-000000000001', 3, '10000000-0000-0000-0000-000000000011',
   'FIXTURE_GENERAL', 'Fixture general class', 'General compatibility class', 'active fixture', 1, true),
  ('10000000-0000-0000-0000-000000000002', 2, '10000000-0000-0000-0000-000000000012',
   'FIXTURE_INACTIVE', 'Fixture inactive class', NULL, NULL, 2, false);

INSERT INTO worker(id, version, revision_marker, warehouse_id, first_name, last_name, middle_name,
                   display_name, active, comment_text, app_login, credential_status, credential_error)
VALUES
  ('20000000-0000-0000-0000-000000000001', 4, '20000000-0000-0000-0000-000000000011',
   '00000000-0000-0000-0000-000000000001', 'Active', 'Worker', NULL, 'Active Worker', true,
   'credential active', 'fixture.active', 'ACTIVE', NULL),
  ('20000000-0000-0000-0000-000000000002', 5, '20000000-0000-0000-0000-000000000012',
   '00000000-0000-0000-0000-000000000001', 'Pending', 'Worker', 'Fixture', 'Pending Worker', true,
   NULL, 'fixture.pending', 'PENDING', NULL),
  ('20000000-0000-0000-0000-000000000003', 6, '20000000-0000-0000-0000-000000000013',
   '00000000-0000-0000-0000-000000000002', 'Error', 'Worker', NULL, 'Error Worker', false,
   NULL, 'fixture.error', 'ERROR', 'sanitized credential failure'),
  ('20000000-0000-0000-0000-000000000004', 7, '20000000-0000-0000-0000-000000000014',
   '00000000-0000-0000-0000-000000000002', NULL, NULL, NULL, 'Unconfigured Worker', false,
   NULL, NULL, 'NOT_CONFIGURED', NULL);

INSERT INTO worker_deletion_intent(id, version, worker_id, status, last_error, created_at, updated_at)
VALUES
  ('21000000-0000-0000-0000-000000000001', 2, '20000000-0000-0000-0000-000000000002',
   'PENDING_AUTH', NULL, '2026-07-12T10:00:00Z', '2026-07-12T10:01:00Z'),
  ('21000000-0000-0000-0000-000000000002', 3, '20000000-0000-0000-0000-000000000003',
   'ERROR', 'sanitized deletion failure', '2026-07-12T10:02:00Z', '2026-07-12T10:03:00Z'),
  ('21000000-0000-0000-0000-000000000003', 4, '20000000-0000-0000-0000-000000000004',
   'AUTH_DELETED', NULL, '2026-07-12T10:04:00Z', '2026-07-12T10:05:00Z');

INSERT INTO worker_class_assignment(id, version, worker_id, worker_class_id, active, comment_text)
VALUES
  ('22000000-0000-0000-0000-000000000001', 2, '20000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', true, 'primary qualification'),
  ('22000000-0000-0000-0000-000000000002', 3, '20000000-0000-0000-0000-000000000003',
   '10000000-0000-0000-0000-000000000002', false, NULL);

INSERT INTO worker_group(id, version, revision_marker, warehouse_id, worker_class_id, name, description, active)
VALUES
  ('30000000-0000-0000-0000-000000000001', 2, '30000000-0000-0000-0000-000000000011',
   '00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
   'Fixture group one', 'warehouse one active group', true),
  ('30000000-0000-0000-0000-000000000002', 3, '30000000-0000-0000-0000-000000000012',
   '00000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
   'Fixture group two', NULL, false);

INSERT INTO worker_group_member(id, version, worker_group_id, worker_id, role_in_group, active)
VALUES
  ('31000000-0000-0000-0000-000000000001', 2, '30000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000001', 'LEAD', true),
  ('31000000-0000-0000-0000-000000000002', 3, '30000000-0000-0000-0000-000000000002',
   '20000000-0000-0000-0000-000000000003', NULL, false);

INSERT INTO work_queue(id, version, revision_marker, warehouse_id, code, name, description, queue_type,
                       sort_order, active, hidden, collapsed, holding_period_minutes,
                       notification_threshold, notify_when_threshold_reached)
VALUES
  ('40000000-0000-0000-0000-000000000001', 5, '40000000-0000-0000-0000-000000000011',
   '00000000-0000-0000-0000-000000000001', 'FIXTURE_MOVE', 'Fixture movement', 'movement queue',
   'MOVEMENT', 1, true, false, false, NULL, NULL, false),
  ('40000000-0000-0000-0000-000000000002', 6, '40000000-0000-0000-0000-000000000012',
   '00000000-0000-0000-0000-000000000001', 'FIXTURE_REPAIR', 'Fixture repair', NULL,
   'REPAIR', 2, true, true, true, NULL, NULL, false),
  ('40000000-0000-0000-0000-000000000003', 7, '40000000-0000-0000-0000-000000000013',
   '00000000-0000-0000-0000-000000000002', 'FIXTURE_HOLDING', 'Fixture holding', 'holding queue',
   'HOLDING', 99, false, false, true, 30, 4, true);

INSERT INTO work_queue_class_binding(id, version, queue_id, worker_class_id, stop_task_on_take)
VALUES
  ('41000000-0000-0000-0000-000000000001', 2, '40000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', true),
  ('41000000-0000-0000-0000-000000000002', 3, '40000000-0000-0000-0000-000000000002',
   '10000000-0000-0000-0000-000000000001', false);

INSERT INTO board_task(id, version, warehouse_id, external_task_id, title, unit_number, description,
                       status, planned_duration_minutes, deadline_at, done_at)
VALUES
  ('50000000-0000-0000-0000-000000000001', 7, '00000000-0000-0000-0000-000000000001',
   '50000000-0000-0000-0000-000000000011', 'Fixture active task', 'БЫТ-001', 'active fixture',
   'ACTIVE', 60, '2026-07-13T12:00:00Z', NULL),
  ('50000000-0000-0000-0000-000000000002', 8, '00000000-0000-0000-0000-000000000001',
   '50000000-0000-0000-0000-000000000012', 'Fixture done task', NULL, NULL,
   'DONE', 30, NULL, '2026-07-12T11:00:00Z'),
  ('50000000-0000-0000-0000-000000000003', 9, '00000000-0000-0000-0000-000000000002',
   '50000000-0000-0000-0000-000000000013', 'Fixture cancelled task', 'БЫТ-003', 'cancelled fixture',
   'CANCELLED', NULL, NULL, '2026-07-12T11:30:00Z'),
  ('50000000-0000-0000-0000-000000000004', 10, '00000000-0000-0000-0000-000000000001',
   '50000000-0000-0000-0000-000000000014', 'Fixture interrupting task', NULL, NULL,
   'ACTIVE', 15, NULL, NULL);

INSERT INTO queue_entry(id, version, task_id, queue_id, queue_code, route_index, queue_position,
                        entry_type, status, task_text, planned_duration_minutes, active_started_at,
                        paused_at, done_at, active_work_seconds, pause_origin)
VALUES
  ('60000000-0000-0000-0000-000000000001', 6, '50000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000002', 'FIXTURE_REPAIR', 0, 0, 'REAL', 'PAUSED',
   'Interrupted work', 60, '2026-07-12T10:00:00Z', '2026-07-12T10:02:00Z', NULL, 120, 'AUTO'),
  ('60000000-0000-0000-0000-000000000002', 2, '50000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000003', 'FIXTURE_HOLDING', 1, 1, 'SHADOW', 'WAITING',
   NULL, NULL, NULL, NULL, NULL, 0, NULL),
  ('60000000-0000-0000-0000-000000000003', 3, '50000000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000002', 'FIXTURE_REPAIR', 0, 0, 'REAL', 'DONE',
   'Completed work', 30, '2026-07-12T10:15:00Z', NULL, '2026-07-12T11:00:00Z', 2700, NULL),
  ('60000000-0000-0000-0000-000000000004', 4, '50000000-0000-0000-0000-000000000003',
   NULL, NULL, 0, 0, 'REAL', 'CANCELLED', 'Unassigned cancelled work', NULL, NULL, NULL,
   '2026-07-12T11:30:00Z', 0, NULL),
  ('60000000-0000-0000-0000-000000000005', 5, '50000000-0000-0000-0000-000000000004',
   '40000000-0000-0000-0000-000000000001', 'FIXTURE_MOVE', 0, 0, 'REAL', 'IN_PROGRESS',
   'Interrupting movement', 15, '2026-07-12T10:02:00Z', NULL, NULL, 30, NULL);

INSERT INTO task_assignment(id, version, queue_entry_id, worker_group_id, worker_id,
                            worker_name_snapshot, group_name_snapshot, status, assigned_at,
                            started_at, paused_at, finished_at)
VALUES
  ('70000000-0000-0000-0000-000000000001', 3, '60000000-0000-0000-0000-000000000001',
   '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
   'Active Worker', 'Fixture group one', 'PAUSED', '2026-07-12T09:55:00Z',
   '2026-07-12T10:00:00Z', '2026-07-12T10:02:00Z', NULL),
  ('70000000-0000-0000-0000-000000000002', 4, '60000000-0000-0000-0000-000000000003',
   '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002',
   'Pending Worker', 'Fixture group one', 'DONE', '2026-07-12T10:10:00Z',
   '2026-07-12T10:15:00Z', NULL, '2026-07-12T11:00:00Z'),
  ('70000000-0000-0000-0000-000000000003', 5, '60000000-0000-0000-0000-000000000004',
   NULL, NULL, 'Removed Worker Snapshot', 'Removed Group Snapshot', 'CANCELLED',
   '2026-07-12T11:20:00Z', NULL, NULL, '2026-07-12T11:30:00Z'),
  ('70000000-0000-0000-0000-000000000004', 6, '60000000-0000-0000-0000-000000000005',
   '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
   'Active Worker', 'Fixture group one', 'ACTIVE', '2026-07-12T10:01:00Z',
   '2026-07-12T10:02:00Z', NULL, NULL);

INSERT INTO task_time_event(id, version, queue_entry_id, worker_id, worker_name_snapshot,
                            group_name_snapshot, event_type, reason, created_at, related_entry_id)
VALUES
  ('71000000-0000-0000-0000-000000000001', 2, '60000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000001', 'Active Worker', 'Fixture group one',
   'AUTO_INTERRUPTED', 'fixture interruption', '2026-07-12T10:02:00Z',
   '60000000-0000-0000-0000-000000000005'),
  ('71000000-0000-0000-0000-000000000002', 3, '60000000-0000-0000-0000-000000000003',
   '20000000-0000-0000-0000-000000000002', 'Pending Worker', 'Fixture group one',
   'FINISHED', NULL, '2026-07-12T11:00:00Z', NULL),
  ('71000000-0000-0000-0000-000000000003', 4, '60000000-0000-0000-0000-000000000004',
   NULL, 'Removed Worker Snapshot', 'Removed Group Snapshot', 'CANCELLED',
   'fixture cancellation', '2026-07-12T11:30:00Z', NULL);

INSERT INTO task_auto_interruption(id, version, worker_id, interrupted_entry_id,
                                   interrupting_entry_id, active, created_at, resolved_at)
VALUES
  ('72000000-0000-0000-0000-000000000001', 2, '20000000-0000-0000-0000-000000000001',
   '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000005',
   false, '2026-07-12T10:02:00Z', '2026-07-12T10:17:00Z');

INSERT INTO queue_usage_reference(id, version, revision_marker, queue_id, reference_type, external_reference_id)
VALUES
  ('80000000-0000-0000-0000-000000000001', 2, '80000000-0000-0000-0000-000000000011',
   '40000000-0000-0000-0000-000000000002', 'CATALOG_POSITION', 'fixture-catalog-reference'),
  ('80000000-0000-0000-0000-000000000002', 3, '80000000-0000-0000-0000-000000000012',
   '40000000-0000-0000-0000-000000000001', 'TASK_HISTORY', 'fixture-history-reference');
