-- A transfer may replace furniture in several selected cabins. Each cabin has
-- at most one worker task; the task and its physical holds remain owned by the
-- existing equipment-movement workflow.

CREATE TABLE public.transfer_furniture_movement_task (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    document_id uuid NOT NULL,
    rental_item_id uuid NOT NULL,
    unit_number varchar(64) NOT NULL,
    equipment_movement_task_id uuid NOT NULL,
    line_count integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT transfer_furniture_movement_task_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transfer_furniture_movement_task_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document(id),
    CONSTRAINT fk_transfer_furniture_movement_task_task
        FOREIGN KEY (equipment_movement_task_id) REFERENCES public.equipment_movement_task(id),
    CONSTRAINT uk_transfer_furniture_movement_task_unit
        UNIQUE (document_id, rental_item_id),
    CONSTRAINT uk_transfer_furniture_movement_task_task
        UNIQUE (equipment_movement_task_id),
    CONSTRAINT ck_transfer_furniture_movement_task_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_furniture_movement_task_line_count CHECK (line_count >= 1),
    CONSTRAINT ck_transfer_furniture_movement_task_unit_number CHECK (
        length(btrim(unit_number)) BETWEEN 1 AND 64
    )
);

CREATE INDEX idx_transfer_furniture_movement_task_document
    ON public.transfer_furniture_movement_task (document_id, unit_number, id);
