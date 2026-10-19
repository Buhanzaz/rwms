CREATE TABLE public.repair_task_evidence (
  evidence_id uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  repair_id uuid NOT NULL,
  repair_stage_id uuid NOT NULL,
  entry_id uuid NOT NULL,
  task_id uuid NOT NULL,
  route_index integer NOT NULL,
  worker_id uuid NOT NULL,
  worker_group_id uuid,
  media_id uuid NOT NULL,
  media_generation bigint NOT NULL,
  captured_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL,
  evidence_state varchar(24) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT repair_task_evidence_pkey PRIMARY KEY (evidence_id),
  CONSTRAINT fk_repair_task_evidence_repair
    FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_repair_task_evidence_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_repair_task_evidence_route_index CHECK (route_index >= 0),
  CONSTRAINT ck_repair_task_evidence_generation CHECK (media_generation >= 1),
  CONSTRAINT ck_repair_task_evidence_state
    CHECK (evidence_state IN ('READY', 'REVIEW_REQUIRED'))
);

CREATE INDEX idx_repair_task_evidence_stage
  ON public.repair_task_evidence(repair_id, repair_stage_id, recorded_at, evidence_id);
