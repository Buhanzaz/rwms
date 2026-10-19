-- V57, V58, and V60 were already published and applied before their later requirements existed.
-- Keep those historical files immutable and add the new response receipt and generation index here.

ALTER TABLE public.logistics_idempotency_record
  ADD COLUMN response_json jsonb,
  ADD CONSTRAINT ck_logistics_idempotency_response_json CHECK (
    response_json IS NULL OR jsonb_typeof(response_json) = 'object'
  );

-- Legacy historical commands did not persist a response snapshot and cannot be reconstructed safely.
-- NOT VALID preserves those rows while enforcing a receipt for every new or subsequently updated row.
ALTER TABLE public.logistics_idempotency_record
  ADD CONSTRAINT ck_logistics_historical_idempotency_response CHECK (
    operation_name NOT IN (
      'CREATE_HISTORICAL_RENTAL_MOVEMENT',
      'UPDATE_HISTORICAL_RENTAL_MOVEMENT'
    )
    OR response_json IS NOT NULL
  ) NOT VALID;

DROP INDEX public.idx_customer_scenario_capacity_command_revision;

CREATE INDEX idx_customer_scenario_capacity_command_revision
    ON public.customer_scenario_capacity_command_receipt (
        warehouse_id, source_scenario_id, source_generation, source_revision, created_at, idempotency_key
    );
