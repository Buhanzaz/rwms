CREATE TABLE public.presentation_unit_hold (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    presentation_id uuid NOT NULL,
    rental_item_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    state varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    order_id uuid,
    created_by_subject_id uuid NOT NULL,
    created_by_role varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    ended_at timestamptz,
    CONSTRAINT presentation_unit_hold_pkey PRIMARY KEY (id),
    CONSTRAINT fk_presentation_unit_hold_item FOREIGN KEY (rental_item_id)
        REFERENCES public.rental_item(id),
    CONSTRAINT ck_presentation_unit_hold_version CHECK (version >= 0),
    CONSTRAINT ck_presentation_unit_hold_state CHECK (
        state IN ('ACTIVE', 'CONVERTED', 'RELEASED', 'EXPIRED')
    ),
    CONSTRAINT ck_presentation_unit_hold_role CHECK (
        created_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_presentation_unit_hold_end_state CHECK (
        (
            state = 'ACTIVE'
            AND order_id IS NULL
            AND ended_at IS NULL
        )
        OR (
            state = 'CONVERTED'
            AND order_id IS NOT NULL
            AND ended_at IS NOT NULL
        )
        OR (
            state IN ('RELEASED', 'EXPIRED')
            AND order_id IS NULL
            AND ended_at IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uk_presentation_unit_hold_active_item
    ON public.presentation_unit_hold (rental_item_id)
    WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX uk_presentation_unit_hold_active_presentation_item
    ON public.presentation_unit_hold (presentation_id, rental_item_id)
    WHERE state = 'ACTIVE';
CREATE INDEX idx_presentation_unit_hold_presentation_state
    ON public.presentation_unit_hold (presentation_id, state, rental_item_id, id);
CREATE INDEX idx_presentation_unit_hold_active_expiry
    ON public.presentation_unit_hold (expires_at, rental_item_id)
    WHERE state = 'ACTIVE';

CREATE FUNCTION public.prevent_presentation_unit_hold_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'presentation unit hold evidence cannot be deleted';
END;
$$;

CREATE TRIGGER trg_presentation_unit_hold_no_delete
BEFORE DELETE ON public.presentation_unit_hold
FOR EACH ROW EXECUTE FUNCTION public.prevent_presentation_unit_hold_delete();
