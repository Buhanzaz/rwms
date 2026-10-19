ALTER TABLE public.logistics_document
    ADD COLUMN equipment_movement_task_id uuid;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT fk_logistics_document_equipment_movement_task
        FOREIGN KEY (equipment_movement_task_id)
        REFERENCES public.equipment_movement_task(id);

CREATE UNIQUE INDEX uk_logistics_document_equipment_movement_task
    ON public.logistics_document (equipment_movement_task_id)
    WHERE equipment_movement_task_id IS NOT NULL;
