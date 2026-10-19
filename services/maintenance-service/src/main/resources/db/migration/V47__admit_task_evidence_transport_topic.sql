ALTER TABLE public.maintenance_inbound_replay_message
  DROP CONSTRAINT ck_maintenance_replay_topic;

ALTER TABLE public.maintenance_inbound_replay_message
  ADD CONSTRAINT ck_maintenance_replay_topic CHECK (source_topic IN (
    'rwms.task-board.board-task.v1',
    'rwms.task-board.queue-entry.v1',
    'rwms.task-board.task-evidence.v1',
    'rwms.media.media.v1',
    'rwms.asset.rental-item.v1',
    'rwms.asset.operation-lease.v1'
  ));

ALTER TABLE public.inbox_message
  DROP CONSTRAINT ck_maintenance_inbox_topic;

ALTER TABLE public.inbox_message
  ADD CONSTRAINT ck_maintenance_inbox_topic CHECK (source_topic IN (
    'rwms.task-board.board-task.v1',
    'rwms.task-board.queue-entry.v1',
    'rwms.task-board.task-evidence.v1',
    'rwms.media.media.v1',
    'rwms.asset.rental-item.v1',
    'rwms.asset.operation-lease.v1'
  ));
