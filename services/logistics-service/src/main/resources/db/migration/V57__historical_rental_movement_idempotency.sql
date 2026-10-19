-- Historical rental movements were introduced as normal, retryable logistics documents in V54.
-- Admit their command name to the existing idempotency receipt constraint without changing any
-- previously stored receipt.
ALTER TABLE public.logistics_idempotency_record
  DROP CONSTRAINT ck_logistics_idempotency_operation;

ALTER TABLE public.logistics_idempotency_record
  ADD CONSTRAINT ck_logistics_idempotency_operation CHECK (
    operation_name IN (
      'CREATE_RETURN',
      'CREATE_SHIPMENT',
      'CREATE_RENTAL_ORDER_SHIPMENT',
      'CREATE_HISTORICAL_RENTAL_MOVEMENT',
      'CREATE_TRANSFER',
      'REGISTER_RETURN',
      'ACCEPT_RETURN',
      'REQUEST_RETURN_ESTIMATE',
      'START_RETURN_ESTIMATES',
      'PLAN_SHIPMENT',
      'CONFIRM_SHIPMENT',
      'CANCEL_SHIPMENT',
      'DEPART_TRANSFER_LINE',
      'ARRIVE_TRANSFER_LINE',
      'CANCEL_TRANSFER',
      'RECONCILE_DOCUMENT'
    )
  );
