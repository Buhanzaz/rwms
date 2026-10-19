DO $rwms$
DECLARE
  actual text;
BEGIN
  SELECT pg_get_indexdef('public.uk_worker_class_code_ci'::regclass) INTO actual;
  IF actual <> 'CREATE UNIQUE INDEX uk_worker_class_code_ci ON public.worker_class USING btree (lower((code)::text))' THEN
    RAISE EXCEPTION 'Unexpected uk_worker_class_code_ci definition';
  END IF;

  SELECT pg_get_indexdef('public.uk_worker_app_login_ci'::regclass) INTO actual;
  IF actual <> 'CREATE UNIQUE INDEX uk_worker_app_login_ci ON public.worker USING btree (lower((app_login)::text)) WHERE (app_login IS NOT NULL)' THEN
    RAISE EXCEPTION 'Unexpected uk_worker_app_login_ci definition';
  END IF;

  SELECT pg_get_indexdef('public.uk_work_queue_code_ci'::regclass) INTO actual;
  IF actual <> 'CREATE UNIQUE INDEX uk_work_queue_code_ci ON public.work_queue USING btree (warehouse_id, lower((code)::text))' THEN
    RAISE EXCEPTION 'Unexpected uk_work_queue_code_ci definition';
  END IF;
END $rwms$;
