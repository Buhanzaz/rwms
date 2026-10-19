-- A network loss after a callback-capable compensation request is never a terminal inventory
-- source conflict. Persist the attempt before the remote call so only a first, explicitly
-- no-effect task-board VERSION_CONFLICT can atomically release the unpublished source key.

ALTER TABLE public.inventory_publication_prestart_replacement
  ADD COLUMN remote_attempt_count bigint NOT NULL DEFAULT 0;

ALTER TABLE public.inventory_publication_prestart_replacement
  ADD CONSTRAINT ck_inventory_publication_prestart_remote_attempt
  CHECK (remote_attempt_count >= 0);
