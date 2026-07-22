CREATE TABLE public.logistics_return_equipment_receipt (
    id uuid NOT NULL,
    return_id uuid NOT NULL,
    return_line_id uuid NOT NULL,
    equipment_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    stock_balance_id uuid NOT NULL,
    quantity bigint NOT NULL,
    received_at timestamptz NOT NULL,
    actor_subject_id uuid NOT NULL,
    CONSTRAINT logistics_return_equipment_receipt_pkey PRIMARY KEY (id),
    CONSTRAINT uk_logistics_return_equipment_receipt_line_equipment
        UNIQUE (return_id, return_line_id, equipment_id),
    CONSTRAINT ck_logistics_return_equipment_receipt_quantity CHECK (quantity > 0),
    CONSTRAINT fk_logistics_return_equipment_receipt_equipment
        FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
    CONSTRAINT fk_logistics_return_equipment_receipt_stock_balance
        FOREIGN KEY (stock_balance_id) REFERENCES public.equipment_balance(id)
);

CREATE INDEX idx_logistics_return_equipment_receipt_return_line
    ON public.logistics_return_equipment_receipt (return_id, return_line_id, received_at);

CREATE TRIGGER trg_logistics_return_equipment_receipt_immutable
BEFORE UPDATE OR DELETE ON public.logistics_return_equipment_receipt
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
