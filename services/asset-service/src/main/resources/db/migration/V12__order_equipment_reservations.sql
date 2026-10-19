-- Generic order allocations reserve catalogue quantities, not a particular
-- cabin. A worker task later chooses the physical source balance.

CREATE TABLE public.order_equipment_reservation (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_id uuid NOT NULL,
    equipment_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    quantity bigint NOT NULL,
    state varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    released_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT order_equipment_reservation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_equipment_reservation_catalog
        FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
    CONSTRAINT ck_order_equipment_reservation_version CHECK (version >= 0),
    CONSTRAINT ck_order_equipment_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_equipment_reservation_state CHECK (
        state IN ('ACTIVE', 'RELEASED')
    ),
    CONSTRAINT ck_order_equipment_reservation_release CHECK (
        (state = 'ACTIVE' AND released_at IS NULL)
        OR (state = 'RELEASED' AND released_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_order_equipment_reservation_active_order_equipment
    ON public.order_equipment_reservation (order_id, equipment_id)
    WHERE state = 'ACTIVE';
CREATE INDEX idx_order_equipment_reservation_active_equipment_warehouse
    ON public.order_equipment_reservation (equipment_id, warehouse_id, order_id)
    WHERE state = 'ACTIVE';

CREATE FUNCTION public.prevent_order_equipment_reservation_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'order equipment reservation evidence cannot be deleted';
END;
$$;

CREATE TRIGGER trg_order_equipment_reservation_no_delete
BEFORE DELETE ON public.order_equipment_reservation
FOR EACH ROW EXECUTE FUNCTION public.prevent_order_equipment_reservation_delete();
