-- Desired furniture is order intent, not a source-cabin allocation.  A zero
-- quantity retains local history after a manager removes a catalogue position.

CREATE TABLE public.rental_order_equipment_requirement (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_id uuid NOT NULL,
    rental_item_id uuid NOT NULL,
    equipment_id uuid NOT NULL,
    equipment_code varchar(128) NOT NULL,
    equipment_name varchar(512) NOT NULL,
    quantity bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT rental_order_equipment_requirement_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_equipment_requirement_order
        FOREIGN KEY (order_id) REFERENCES public.rental_order(id),
    CONSTRAINT uk_rental_order_equipment_requirement
        UNIQUE (order_id, rental_item_id, equipment_id),
    CONSTRAINT ck_order_equipment_requirement_version CHECK (version >= 0),
    CONSTRAINT ck_order_equipment_requirement_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_order_equipment_requirement_code CHECK (
        length(btrim(equipment_code)) BETWEEN 1 AND 128
    ),
    CONSTRAINT ck_order_equipment_requirement_name CHECK (
        length(btrim(equipment_name)) BETWEEN 1 AND 512
    )
);

CREATE INDEX idx_order_equipment_requirement_order_unit
    ON public.rental_order_equipment_requirement (order_id, rental_item_id, equipment_code);
