CREATE TABLE public.task_problem_report (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  entry_id uuid NOT NULL,
  task_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  worker_id uuid NOT NULL,
  worker_name varchar(256) NOT NULL,
  entry_title varchar(512) NOT NULL,
  route_index integer NOT NULL,
  comment_text varchar(2000) NOT NULL,
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT task_problem_report_pkey PRIMARY KEY (id),
  CONSTRAINT fk_task_problem_report_entry
    FOREIGN KEY (entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT ck_task_problem_report_version CHECK (version >= 0),
  CONSTRAINT ck_task_problem_report_route CHECK (route_index >= 0),
  CONSTRAINT ck_task_problem_report_comment CHECK (char_length(btrim(comment_text)) BETWEEN 1 AND 2000)
);

CREATE INDEX idx_task_problem_report_warehouse_recorded
  ON public.task_problem_report(warehouse_id, recorded_at DESC, id DESC);
CREATE INDEX idx_task_problem_report_author
  ON public.task_problem_report(worker_id, warehouse_id, recorded_at DESC);

CREATE TABLE public.task_problem_report_read_receipt (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  report_id uuid NOT NULL,
  manager_id uuid NOT NULL,
  read_at timestamptz NOT NULL,
  CONSTRAINT task_problem_report_read_receipt_pkey PRIMARY KEY (id),
  CONSTRAINT fk_task_problem_report_read_receipt_report
    FOREIGN KEY (report_id) REFERENCES public.task_problem_report(id),
  CONSTRAINT uk_task_problem_report_read_receipt_report_manager UNIQUE (report_id, manager_id),
  CONSTRAINT ck_task_problem_report_read_receipt_version CHECK (version >= 0)
);

CREATE INDEX idx_task_problem_report_read_receipt_manager
  ON public.task_problem_report_read_receipt(manager_id, report_id);

ALTER TABLE public.worker_task_evidence
  ADD COLUMN problem_report_id uuid,
  ADD CONSTRAINT fk_worker_task_evidence_problem_report
    FOREIGN KEY (problem_report_id) REFERENCES public.task_problem_report(id),
  ADD CONSTRAINT ck_worker_task_evidence_problem_report_selection
    CHECK (problem_report_id IS NULL OR selected_for_completion = false);

CREATE INDEX idx_worker_task_evidence_problem_report
  ON public.worker_task_evidence(problem_report_id, recorded_at, evidence_id)
  WHERE problem_report_id IS NOT NULL;
