-- Reuse the immutable native action receipt for selected requirement restoration.
ALTER TABLE public.worker_action_receipt
  DROP CONSTRAINT ck_worker_action_receipt_action;
ALTER TABLE public.worker_action_receipt
  ADD CONSTRAINT ck_worker_action_receipt_action
  CHECK (action IN ('TAKE', 'JOIN', 'PAUSE', 'RESUME', 'COMPLETE', 'RESTORE_ITEM'));
