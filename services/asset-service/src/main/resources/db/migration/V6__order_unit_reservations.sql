CREATE TABLE public.order_unit_reservation (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_id uuid NOT NULL,
    rental_item_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    state varchar(16) NOT NULL,
    added_by_subject_id uuid NOT NULL,
    added_by_role varchar(32) NOT NULL,
    released_by_subject_id uuid,
    released_by_role varchar(32),
    created_at timestamptz NOT NULL,
    released_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT order_unit_reservation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_unit_reservation_item FOREIGN KEY (rental_item_id)
        REFERENCES public.rental_item(id),
    CONSTRAINT ck_order_unit_reservation_version CHECK (version >= 0),
    CONSTRAINT ck_order_unit_reservation_state CHECK (
        state IN ('ACTIVE', 'RELEASED')
    ),
    CONSTRAINT ck_order_unit_reservation_added_role CHECK (
        added_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_order_unit_reservation_released_role CHECK (
        released_by_role IS NULL OR released_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_order_unit_reservation_release CHECK (
        (
            state = 'ACTIVE'
            AND released_at IS NULL
            AND released_by_subject_id IS NULL
            AND released_by_role IS NULL
        )
        OR (
            state = 'RELEASED'
            AND released_at IS NOT NULL
            AND released_by_subject_id IS NOT NULL
            AND released_by_role IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uk_order_unit_reservation_active_item
    ON public.order_unit_reservation (rental_item_id)
    WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX uk_order_unit_reservation_active_order_item
    ON public.order_unit_reservation (order_id, rental_item_id)
    WHERE state = 'ACTIVE';
CREATE INDEX idx_order_unit_reservation_order_state
    ON public.order_unit_reservation (order_id, state, created_at, id);

CREATE FUNCTION public.prevent_order_unit_reservation_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'order unit reservation evidence cannot be deleted';
END;
$$;

CREATE TRIGGER trg_order_unit_reservation_no_delete
BEFORE DELETE ON public.order_unit_reservation
FOR EACH ROW EXECUTE FUNCTION public.prevent_order_unit_reservation_delete();
