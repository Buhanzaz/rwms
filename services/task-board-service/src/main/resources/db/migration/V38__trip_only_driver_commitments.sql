-- One-trip cross-warehouse driver commitments that never change operational basing.

ALTER TABLE public.worker_operational_assignment
  DROP CONSTRAINT ck_worker_operational_assignment_mode,
  DROP CONSTRAINT ck_worker_operational_assignment_interval;

ALTER TABLE public.worker_operational_assignment
  ADD CONSTRAINT ck_worker_operational_assignment_mode
    CHECK (assignment_mode IN ('TEMPORARY', 'PERMANENT', 'TRIP_ONLY')),
  ADD CONSTRAINT ck_worker_operational_assignment_interval
    CHECK (
      travel_starts_at < effective_from
      AND (
        (assignment_mode = 'TEMPORARY'
          AND effective_until IS NOT NULL
          AND effective_until > effective_from)
        OR
        (assignment_mode = 'PERMANENT' AND effective_until IS NULL)
        OR
        (assignment_mode = 'TRIP_ONLY'
          AND effective_until IS NOT NULL
          AND effective_until = effective_from)
      )
    );
