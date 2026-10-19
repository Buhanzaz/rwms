-- A continuation pair belongs to the global queue standard. Existing queues stay unlinked.
ALTER TABLE queue_definition
    ADD COLUMN linked_queue_definition_id UUID,
    ADD CONSTRAINT fk_queue_definition_link
        FOREIGN KEY (linked_queue_definition_id) REFERENCES queue_definition(id),
    ADD CONSTRAINT ck_queue_definition_link_not_self
        CHECK (linked_queue_definition_id IS NULL OR linked_queue_definition_id <> id),
    ADD CONSTRAINT uk_queue_definition_link UNIQUE (linked_queue_definition_id);

-- Historical assignments did not record this distinction; their role must not be guessed.
ALTER TABLE task_assignment ADD COLUMN primary_participation BOOLEAN;
