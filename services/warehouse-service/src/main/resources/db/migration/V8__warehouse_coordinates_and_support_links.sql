-- Owner-held WGS84 coordinates are optional for legacy warehouses, but must be a complete pair.
-- Logistics reads them directly from warehouse-service instead of maintaining a second map copy.
ALTER TABLE public.warehouse
  ADD COLUMN latitude numeric(8, 6),
  ADD COLUMN longitude numeric(9, 6),
  ADD COLUMN support_link_revision bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_warehouse_coordinate_pair
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
  ADD CONSTRAINT ck_warehouse_latitude
    CHECK (latitude IS NULL OR latitude BETWEEN -90.000000 AND 90.000000),
  ADD CONSTRAINT ck_warehouse_longitude
    CHECK (longitude IS NULL OR longitude BETWEEN -180.000000 AND 180.000000),
  ADD CONSTRAINT ck_warehouse_support_link_revision
    CHECK (support_link_revision >= 0);

-- Durable create replays remain valid responses after the additive coordinate contract change.
UPDATE public.idempotency_record AS record
SET response_body = record.response_body
  || jsonb_build_object(
       'latitude', warehouse.latitude,
       'longitude', warehouse.longitude)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id
  AND (NOT (record.response_body ? 'latitude') OR NOT (record.response_body ? 'longitude'));

-- A directed edge is the first-class many-to-many network model. It is intentionally not a
-- parent_warehouse_id and therefore permits several sources for one served warehouse.
CREATE TABLE public.warehouse_support_link (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  support_warehouse_id uuid NOT NULL,
  served_warehouse_id uuid NOT NULL,
  active boolean NOT NULL,
  priority integer NOT NULL,
  allow_drivers boolean NOT NULL,
  allow_vehicles boolean NOT NULL,
  allow_inventory boolean NOT NULL,
  allow_direct_fulfillment boolean NOT NULL,
  allow_interwarehouse_transfer boolean NOT NULL,
  allow_contractor_fallback boolean NOT NULL,
  service_start time without time zone,
  service_end time without time zone,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT warehouse_support_link_pkey PRIMARY KEY (id),
  CONSTRAINT uk_warehouse_support_link_direction
    UNIQUE (support_warehouse_id, served_warehouse_id),
  CONSTRAINT fk_warehouse_support_link_support
    FOREIGN KEY (support_warehouse_id) REFERENCES public.warehouse(id) ON DELETE RESTRICT,
  CONSTRAINT fk_warehouse_support_link_served
    FOREIGN KEY (served_warehouse_id) REFERENCES public.warehouse(id) ON DELETE CASCADE,
  CONSTRAINT ck_warehouse_support_link_version CHECK (version >= 0),
  CONSTRAINT ck_warehouse_support_link_direction
    CHECK (support_warehouse_id <> served_warehouse_id),
  CONSTRAINT ck_warehouse_support_link_priority CHECK (priority > 0),
  CONSTRAINT ck_warehouse_support_link_service_window CHECK (
    (service_start IS NULL AND service_end IS NULL)
    OR
    (service_start IS NOT NULL AND service_end IS NOT NULL AND service_start < service_end)
  )
);

CREATE INDEX idx_warehouse_support_link_served_active
  ON public.warehouse_support_link(served_warehouse_id, active, priority, support_warehouse_id);

CREATE INDEX idx_warehouse_support_link_support
  ON public.warehouse_support_link(support_warehouse_id, active, served_warehouse_id);

CREATE TABLE public.warehouse_support_link_weekday (
  support_link_id uuid NOT NULL,
  weekday varchar(9) NOT NULL,
  CONSTRAINT warehouse_support_link_weekday_pkey PRIMARY KEY (support_link_id, weekday),
  CONSTRAINT fk_warehouse_support_link_weekday_link
    FOREIGN KEY (support_link_id) REFERENCES public.warehouse_support_link(id) ON DELETE CASCADE,
  CONSTRAINT ck_warehouse_support_link_weekday CHECK (
    weekday IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')
  )
);

CREATE TABLE public.warehouse_support_link_allowed_date (
  support_link_id uuid NOT NULL,
  allowed_date date NOT NULL,
  CONSTRAINT warehouse_support_link_allowed_date_pkey PRIMARY KEY (support_link_id, allowed_date),
  CONSTRAINT fk_warehouse_support_link_allowed_date_link
    FOREIGN KEY (support_link_id) REFERENCES public.warehouse_support_link(id) ON DELETE CASCADE
);

CREATE TABLE public.warehouse_support_link_excluded_date (
  support_link_id uuid NOT NULL,
  excluded_date date NOT NULL,
  CONSTRAINT warehouse_support_link_excluded_date_pkey PRIMARY KEY (support_link_id, excluded_date),
  CONSTRAINT fk_warehouse_support_link_excluded_date_link
    FOREIGN KEY (support_link_id) REFERENCES public.warehouse_support_link(id) ON DELETE CASCADE
);

-- Direct SQL cannot bypass the aggregate's representative/active endpoint invariant.
CREATE FUNCTION public.validate_warehouse_support_link_endpoints()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  support_active boolean;
  served_active boolean;
  served_representative boolean;
BEGIN
  SELECT active
    INTO support_active
    FROM public.warehouse
   WHERE id = NEW.support_warehouse_id;
  SELECT active, representative
    INTO served_active, served_representative
    FROM public.warehouse
   WHERE id = NEW.served_warehouse_id;
  IF support_active IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'support warehouse must be active';
  END IF;
  IF served_active IS DISTINCT FROM true OR served_representative IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'served warehouse must be active and representative';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_warehouse_support_link_endpoints
BEFORE INSERT OR UPDATE ON public.warehouse_support_link
FOR EACH ROW
EXECUTE FUNCTION public.validate_warehouse_support_link_endpoints();

CREATE FUNCTION public.prevent_representative_clear_with_support_links()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.representative AND NOT NEW.representative
      AND EXISTS (
        SELECT 1
          FROM public.warehouse_support_link
         WHERE served_warehouse_id = OLD.id) THEN
    RAISE EXCEPTION 'remove warehouse support links before clearing representative';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_warehouse_representative_support_links
BEFORE UPDATE OF representative ON public.warehouse
FOR EACH ROW
EXECUTE FUNCTION public.prevent_representative_clear_with_support_links();
