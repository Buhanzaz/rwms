-- Asset-local lifecycle readiness fencing. Warehouse-service remains the lifecycle owner;
-- asset-service only makes its own drain observation and post-admission commits atomic.

CREATE TABLE public.asset_warehouse_readiness_fence (
  warehouse_id uuid NOT NULL,
  warehouse_version bigint NOT NULL,
  state varchar(16) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT asset_warehouse_readiness_fence_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT ck_asset_warehouse_readiness_fence_version CHECK (warehouse_version >= 0),
  CONSTRAINT ck_asset_warehouse_readiness_fence_state CHECK (state IN ('CONFIRMING','SEALED'))
);

-- All transitions below use this exact key. A transaction which wrote a new blocker first makes
-- readiness wait for its commit/rollback; a transaction which writes after the fence is installed
-- sees the durable fence and aborts before it can commit the new blocker.
CREATE FUNCTION public.asset_assert_warehouse_lifecycle_open(p_warehouse_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  IF p_warehouse_id IS NULL THEN
    RETURN;
  END IF;

  PERFORM pg_advisory_xact_lock(
    hashtextextended('warehouse-lifecycle:asset:' || p_warehouse_id::text, 0));

  IF EXISTS (
    SELECT 1
      FROM public.asset_warehouse_readiness_fence
     WHERE warehouse_id=p_warehouse_id
  ) THEN
    RAISE EXCEPTION 'asset warehouse lifecycle is fenced for readiness'
      USING ERRCODE = '23514';
  END IF;
END;
$$;

CREATE FUNCTION public.asset_guard_rental_item_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status NOT IN ('WRITTEN_OFF','LOST') THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.status IN ('WRITTEN_OFF','LOST') THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_equipment_balance_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.quantity > 0
     AND NEW.location_kind NOT IN ('WRITTEN_OFF','LOST') THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.quantity = 0
       OR OLD.location_kind IN ('WRITTEN_OFF','LOST') THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_equipment_hold_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state IN ('ACTIVE','COMMITTED') THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state NOT IN ('ACTIVE','COMMITTED') THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_order_equipment_reservation_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state = 'ACTIVE' THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state <> 'ACTIVE' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_order_unit_reservation_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state = 'ACTIVE' THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state <> 'ACTIVE' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_presentation_hold_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state = 'ACTIVE' AND NEW.expires_at > clock_timestamp() THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state <> 'ACTIVE'
       OR OLD.expires_at <= clock_timestamp() THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_inventory_capture_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state = 'ACTIVE' AND NEW.expires_at > clock_timestamp() THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state <> 'ACTIVE'
       OR OLD.expires_at <= clock_timestamp() THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_html_import_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED') THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED') THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_property_disposition_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.state = 'PREPARED' THEN
    IF TG_OP = 'INSERT' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR OLD.state <> 'PREPARED' THEN
      PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_furniture_custody_claim_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM public.asset_assert_warehouse_lifecycle_open(NEW.warehouse_id);
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.asset_guard_operation_lease_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  warehouse uuid;
  introduces_blocker boolean := false;
BEGIN
  IF NEW.state = 'ACTIVE' AND NEW.expires_at > clock_timestamp() THEN
    IF TG_OP = 'INSERT' THEN
      introduces_blocker := true;
    ELSIF NEW.rental_item_id IS DISTINCT FROM OLD.rental_item_id
       OR OLD.state <> 'ACTIVE'
       OR OLD.expires_at <= clock_timestamp() THEN
      introduces_blocker := true;
    END IF;
  END IF;

  IF introduces_blocker THEN
    SELECT warehouse_id INTO warehouse
      FROM public.rental_item
     WHERE id=NEW.rental_item_id;
    IF warehouse IS NULL THEN
      RAISE EXCEPTION 'operation lease rental item is missing';
    END IF;
    PERFORM public.asset_assert_warehouse_lifecycle_open(warehouse);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_rental_item_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,status ON public.rental_item
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_rental_item_readiness();

CREATE TRIGGER trg_equipment_balance_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,location_kind,quantity ON public.equipment_balance
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_equipment_balance_readiness();

CREATE TRIGGER trg_equipment_allocation_hold_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state ON public.equipment_allocation_hold
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_equipment_hold_readiness();

CREATE TRIGGER trg_order_equipment_reservation_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state ON public.order_equipment_reservation
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_order_equipment_reservation_readiness();

CREATE TRIGGER trg_order_unit_reservation_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state ON public.order_unit_reservation
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_order_unit_reservation_readiness();

CREATE TRIGGER trg_presentation_unit_hold_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state,expires_at ON public.presentation_unit_hold
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_presentation_hold_readiness();

CREATE TRIGGER trg_inventory_asset_capture_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state,expires_at ON public.inventory_asset_capture
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_inventory_capture_readiness();

CREATE TRIGGER trg_rental_item_html_import_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state ON public.rental_item_html_import
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_html_import_readiness();

CREATE TRIGGER trg_property_disposition_fence_warehouse_readiness
BEFORE INSERT OR UPDATE OF warehouse_id,state ON public.property_disposition_fence
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_property_disposition_readiness();

CREATE TRIGGER trg_maintenance_furniture_custody_claim_warehouse_readiness
BEFORE INSERT ON public.maintenance_furniture_custody_claim
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_furniture_custody_claim_readiness();

CREATE TRIGGER trg_operation_lease_warehouse_readiness
BEFORE INSERT OR UPDATE OF rental_item_id,state,expires_at ON public.operation_lease
FOR EACH ROW EXECUTE FUNCTION public.asset_guard_operation_lease_readiness();
