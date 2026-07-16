DO $rwms$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.worker_class
    GROUP BY lower(code)
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Cannot enforce case-insensitive worker class identity: duplicate normalized values exist';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.worker
    WHERE app_login IS NOT NULL
    GROUP BY lower(app_login)
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Cannot enforce case-insensitive worker login identity: duplicate normalized values exist';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.work_queue
    GROUP BY warehouse_id, lower(code)
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Cannot enforce case-insensitive work queue identity: duplicate normalized values exist';
  END IF;
END $rwms$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_worker_class_code_ci
  ON public.worker_class (lower(code));
CREATE UNIQUE INDEX IF NOT EXISTS uk_worker_app_login_ci
  ON public.worker (lower(app_login))
  WHERE app_login IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_work_queue_code_ci
  ON public.work_queue (warehouse_id, lower(code));
