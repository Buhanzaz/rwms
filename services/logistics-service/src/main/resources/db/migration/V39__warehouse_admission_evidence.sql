-- A permanent operation-boundary mark may support a dependency-free replay candidate only when the
-- original remote warehouse admission direction and lifecycle version were committed with it. The
-- owning command still verifies its stored checksum. Historical and owner/test-bypassed marks
-- deliberately remain unproven (both columns NULL).

ALTER TABLE public.warehouse_operation_mark_outbox
  ADD COLUMN admission_direction varchar(16),
  ADD COLUMN admission_warehouse_version bigint,
  ADD CONSTRAINT ck_warehouse_operation_mark_admission_evidence
    CHECK (
      (admission_direction IS NULL AND admission_warehouse_version IS NULL)
      OR (
        admission_direction IS NOT NULL
        AND admission_warehouse_version IS NOT NULL
        AND admission_direction IN ('INCOMING', 'OUTGOING')
        AND admission_warehouse_version >= 0
      )
    );

-- A replay candidate must also reject unexpected marks for the same domain operation. The existing
-- primary key serves each expected (warehouse_id, operation_id) lookup; this reverse index keeps
-- the exact-set check bounded as the permanent outbox grows.
CREATE INDEX idx_warehouse_operation_mark_operation
  ON public.warehouse_operation_mark_outbox (operation_id, warehouse_id);
