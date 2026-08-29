ALTER TABLE public.driver_logistics_task
    ADD COLUMN worker_content_json jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.driver_logistics_task
    ADD CONSTRAINT ck_driver_logistics_task_worker_content_json
        CHECK (jsonb_typeof(worker_content_json) = 'object');
