-- Contractor-driver availability and transfer-backed operational warehouse history.

ALTER TABLE public.worker
  ADD COLUMN employment_type varchar(16) NOT NULL DEFAULT 'STAFF',
  ADD COLUMN phone varchar(64),
  ADD COLUMN contract_available_from timestamptz,
  ADD COLUMN contract_available_until timestamptz,
  ADD CONSTRAINT ck_worker_employment_type
    CHECK (employment_type IN ('STAFF', 'CONTRACTOR')),
  ADD CONSTRAINT ck_worker_contractor_profile
    CHECK (
      (employment_type = 'STAFF'
        AND phone IS NULL
        AND contract_available_from IS NULL
        AND contract_available_until IS NULL)
      OR
      (employment_type = 'CONTRACTOR'
        AND phone IS NOT NULL
        AND length(btrim(phone)) BETWEEN 1 AND 64
        AND contract_available_from IS NOT NULL
        AND contract_available_until IS NOT NULL
        AND contract_available_until > contract_available_from)
    );

CREATE INDEX idx_worker_contractor_availability
  ON public.worker(warehouse_id, contract_available_from, contract_available_until, id)
  WHERE employment_type = 'CONTRACTOR' AND active = true;

CREATE TABLE public.worker_operational_assignment (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  worker_id uuid NOT NULL,
  transfer_id uuid NOT NULL,
  home_warehouse_id uuid NOT NULL,
  source_warehouse_id uuid NOT NULL,
  destination_warehouse_id uuid NOT NULL,
  assignment_mode varchar(16) NOT NULL,
  status varchar(16) NOT NULL,
  travel_starts_at timestamptz NOT NULL,
  effective_from timestamptz NOT NULL,
  effective_until timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  created_by varchar(128) NOT NULL,
  updated_by varchar(128) NOT NULL,
  CONSTRAINT worker_operational_assignment_pkey PRIMARY KEY (id),
  CONSTRAINT fk_worker_operational_assignment_worker
    FOREIGN KEY (worker_id) REFERENCES public.worker(id),
  CONSTRAINT uk_worker_operational_assignment_transfer_worker
    UNIQUE (transfer_id, worker_id),
  CONSTRAINT ck_worker_operational_assignment_warehouses
    CHECK (source_warehouse_id <> destination_warehouse_id),
  CONSTRAINT ck_worker_operational_assignment_mode
    CHECK (assignment_mode IN ('TEMPORARY', 'PERMANENT')),
  CONSTRAINT ck_worker_operational_assignment_status
    CHECK (status IN ('PLANNED', 'IN_TRANSIT', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT ck_worker_operational_assignment_interval
    CHECK (
      travel_starts_at < effective_from
      AND (
        (assignment_mode = 'TEMPORARY'
        AND effective_until IS NOT NULL
        AND effective_until > effective_from)
        OR
        (assignment_mode = 'PERMANENT' AND effective_until IS NULL)
      )
    ),
  CONSTRAINT ck_worker_operational_assignment_audit
    CHECK (
      updated_at >= created_at
      AND length(btrim(created_by)) BETWEEN 1 AND 128
      AND length(btrim(updated_by)) BETWEEN 1 AND 128)
);

CREATE INDEX idx_worker_operational_assignment_worker_status
  ON public.worker_operational_assignment(worker_id, status, travel_starts_at);

CREATE INDEX idx_worker_operational_assignment_destination_status
  ON public.worker_operational_assignment(destination_warehouse_id, status, effective_from);
