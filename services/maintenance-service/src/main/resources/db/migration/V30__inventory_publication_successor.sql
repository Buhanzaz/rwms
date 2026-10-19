-- A started/queued inventory repair cannot be rewritten or cancelled through a best-effort
-- remote call. Preserve the immutable source decision, create a locally held successor, and keep
-- its mutable release fact in a separate table so V29's immutable-source trigger remains intact.

ALTER TABLE public.inventory_publication_source
  ADD COLUMN publication_outcome varchar(32) NOT NULL DEFAULT 'CREATED',
  ADD COLUMN predecessor_repair_id uuid,
  ADD COLUMN delta_snapshot jsonb;

UPDATE public.inventory_publication_source AS source
SET delta_snapshot = jsonb_build_object(
  'lines',
  COALESCE(
    (
      SELECT jsonb_agg(
        jsonb_build_object(
          'sourceIndex', source_line.ordinality - 1,
          'disposition', 'RETAINED',
          'lineType', source_line.value ->> 'type',
          'catalogNodeId', source_line.value -> 'catalogNodeId',
          'requestedQuantity', source_line.value ->> 'quantity',
          'retainedQuantity', source_line.value ->> 'quantity'
        )
        ORDER BY source_line.ordinality
      )
      FROM jsonb_array_elements(source.plan_snapshot -> 'lines')
        WITH ORDINALITY AS source_line(value, ordinality)
    ),
    '[]'::jsonb
  )
);

ALTER TABLE public.inventory_publication_source
  ALTER COLUMN publication_outcome DROP DEFAULT,
  ALTER COLUMN delta_snapshot SET NOT NULL,
  ALTER COLUMN target_kind DROP NOT NULL,
  ALTER COLUMN target_id DROP NOT NULL,
  ADD CONSTRAINT fk_inventory_publication_source_predecessor_repair
    FOREIGN KEY (predecessor_repair_id) REFERENCES public.maintenance_repair(id),
  ADD CONSTRAINT ck_inventory_publication_source_outcome CHECK (
    publication_outcome IN ('CREATED', 'SUCCESSOR', 'MATCHED')
  ),
  ADD CONSTRAINT ck_inventory_publication_source_delta CHECK (
    jsonb_typeof(delta_snapshot) = 'object'
    AND jsonb_typeof(delta_snapshot -> 'lines') = 'array'
  );

ALTER TABLE public.inventory_publication_source
  DROP CONSTRAINT ck_inventory_publication_source_strategy,
  DROP CONSTRAINT ck_inventory_publication_source_target,
  ADD CONSTRAINT ck_inventory_publication_source_strategy CHECK (
    (strategy = 'CREATE'
      AND publication_outcome = 'CREATED'
      AND selected_target_kind IS NULL AND selected_target_id IS NULL
      AND superseded_target_kind IS NULL AND superseded_target_id IS NULL)
    OR (strategy IN ('REPLACE', 'MERGE')
      AND selected_target_kind IN ('ESTIMATE', 'REPAIR') AND selected_target_id IS NOT NULL
      AND ((publication_outcome = 'CREATED'
            AND superseded_target_kind = selected_target_kind
            AND superseded_target_id = selected_target_id)
           OR (publication_outcome IN ('SUCCESSOR', 'MATCHED')
               AND superseded_target_kind IS NULL AND superseded_target_id IS NULL)))
  ),
  ADD CONSTRAINT ck_inventory_publication_source_target CHECK (
    (publication_outcome = 'CREATED'
      AND predecessor_repair_id IS NULL
      AND ((target_kind = 'ESTIMATE' AND target_id = estimate_id AND repair_id IS NULL)
           OR (target_kind = 'REPAIR' AND target_id = repair_id AND estimate_id IS NULL)))
    OR (publication_outcome = 'SUCCESSOR'
      AND predecessor_repair_id IS NOT NULL
      AND target_kind = 'REPAIR' AND target_id = repair_id AND estimate_id IS NULL)
    OR (publication_outcome = 'MATCHED'
      AND predecessor_repair_id IS NOT NULL
      AND target_kind IS NULL AND target_id IS NULL
      AND estimate_id IS NULL AND repair_id IS NULL)
  );

CREATE INDEX idx_inventory_publication_source_predecessor
  ON public.inventory_publication_source(predecessor_repair_id)
  WHERE predecessor_repair_id IS NOT NULL;

CREATE TABLE public.inventory_publication_successor (
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  predecessor_repair_id uuid NOT NULL,
  successor_repair_id uuid NOT NULL,
  state varchar(32) NOT NULL,
  terminal_fact varchar(32),
  terminal_fact_event_id uuid,
  terminal_fact_occurred_at timestamptz,
  created_at timestamptz NOT NULL,
  released_at timestamptz,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_successor_pkey
    PRIMARY KEY (inventory_id, final_plan_version, finding_id),
  CONSTRAINT fk_inventory_publication_successor_source
    FOREIGN KEY (inventory_id, final_plan_version, finding_id)
    REFERENCES public.inventory_publication_source(inventory_id, final_plan_version, finding_id),
  CONSTRAINT fk_inventory_publication_successor_predecessor
    FOREIGN KEY (predecessor_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT fk_inventory_publication_successor_successor
    FOREIGN KEY (successor_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_inventory_publication_successor_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_publication_successor_plan_version CHECK (final_plan_version >= 1),
  CONSTRAINT ck_inventory_publication_successor_state CHECK (
    (state = 'WAITING_PREDECESSOR'
      AND terminal_fact IS NULL AND terminal_fact_event_id IS NULL
      AND terminal_fact_occurred_at IS NULL AND released_at IS NULL)
    OR (state = 'RELEASED'
      AND terminal_fact IN ('TASK_BOARD_COMPLETION', 'REPAIR_ACCEPTANCE')
      AND terminal_fact_event_id IS NOT NULL
      AND terminal_fact_occurred_at IS NOT NULL AND released_at IS NOT NULL)
  ),
  CONSTRAINT ck_inventory_publication_successor_distinct_repairs CHECK (
    predecessor_repair_id <> successor_repair_id
  )
);

CREATE UNIQUE INDEX uk_inventory_publication_successor_repair
  ON public.inventory_publication_successor(successor_repair_id);
CREATE INDEX idx_inventory_publication_successor_waiting_predecessor
  ON public.inventory_publication_successor(predecessor_repair_id, state)
  WHERE state = 'WAITING_PREDECESSOR';
