ALTER TABLE work_queue
  ADD COLUMN worker_feed_enabled boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN work_queue.available_task_limit IS
  'Warehouse-local number of waiting REAL entries published to WorkerApp';

COMMENT ON COLUMN work_queue.worker_feed_enabled IS
  'Whether this warehouse queue is discoverable through WorkerApp';
