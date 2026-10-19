CREATE TABLE public.task_sync_source (
  board_task_id uuid PRIMARY KEY,
  external_task_id uuid NOT NULL,
  source_client_id varchar(100) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT fk_task_sync_source_task
    FOREIGN KEY (board_task_id) REFERENCES public.board_task(id) ON DELETE CASCADE,
  CONSTRAINT uk_task_sync_source_external UNIQUE (external_task_id),
  CONSTRAINT ck_task_sync_source_client
    CHECK (source_client_id = btrim(source_client_id) AND source_client_id <> '')
);

CREATE INDEX idx_task_sync_source_client_external
  ON public.task_sync_source(source_client_id, external_task_id);
