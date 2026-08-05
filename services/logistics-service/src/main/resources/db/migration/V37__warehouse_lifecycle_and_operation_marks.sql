-- Durable local half of warehouse lifecycle coordination. Warehouse-service remains the
-- lifecycle/timezone owner; logistics stores only in-flight admission/readiness intents and the
-- delivery outbox for immutable operated-boundary marks.

CREATE TABLE public.logistics_warehouse_admission_intent (
    operation_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    direction varchar(16) NOT NULL,
    state varchar(16) NOT NULL,
    warehouse_version bigint,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_logistics_warehouse_admission_intent
        PRIMARY KEY (operation_id, warehouse_id),
    CONSTRAINT ck_logistics_warehouse_admission_direction
        CHECK (direction IN ('INCOMING', 'OUTGOING')),
    CONSTRAINT ck_logistics_warehouse_admission_state
        CHECK (state IN ('RESERVED', 'ADMITTED')),
    CONSTRAINT ck_logistics_warehouse_admission_version
        CHECK ((state = 'RESERVED' AND warehouse_version IS NULL)
            OR (state = 'ADMITTED' AND warehouse_version >= 0))
);

CREATE INDEX idx_logistics_warehouse_admission_blocking
    ON public.logistics_warehouse_admission_intent (warehouse_id, expires_at);

CREATE TABLE public.logistics_warehouse_readiness_fence (
    warehouse_id uuid PRIMARY KEY,
    warehouse_version bigint NOT NULL,
    state varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_logistics_warehouse_readiness_version CHECK (warehouse_version >= 0),
    CONSTRAINT ck_logistics_warehouse_readiness_state
        CHECK (state IN ('CONFIRMING', 'SEALED'))
);

CREATE TABLE public.warehouse_operation_mark_outbox (
    operation_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    state varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claim_token uuid,
    claim_until timestamptz,
    last_error_code varchar(96),
    recovery_version bigint NOT NULL DEFAULT 0,
    recovered_by_subject_id uuid,
    recovery_reason varchar(2000),
    recovered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_warehouse_operation_mark_outbox
        PRIMARY KEY (warehouse_id, operation_id),
    CONSTRAINT ck_warehouse_operation_mark_state
        CHECK (state IN ('PENDING', 'IN_FLIGHT', 'RETRY_PENDING', 'CONFIRMED', 'QUARANTINED')),
    CONSTRAINT ck_warehouse_operation_mark_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_warehouse_operation_mark_recovery_version CHECK (recovery_version >= 0),
    CONSTRAINT ck_warehouse_operation_mark_claim
        CHECK ((state = 'IN_FLIGHT' AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
            OR (state <> 'IN_FLIGHT' AND claim_token IS NULL AND claim_until IS NULL)),
    CONSTRAINT ck_warehouse_operation_mark_recovery
        CHECK ((recovered_by_subject_id IS NULL AND recovery_reason IS NULL AND recovered_at IS NULL)
            OR (recovered_by_subject_id IS NOT NULL AND recovery_reason IS NOT NULL
                AND recovered_at IS NOT NULL))
);

CREATE INDEX idx_warehouse_operation_mark_due
    ON public.warehouse_operation_mark_outbox (state, next_attempt_at, claim_until);
CREATE INDEX idx_warehouse_operation_mark_blocking
    ON public.warehouse_operation_mark_outbox (warehouse_id, state);

CREATE TABLE public.warehouse_operation_mark_recovery_audit (
    id uuid PRIMARY KEY,
    warehouse_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    recovery_version bigint NOT NULL,
    reviewed_by_subject_id uuid NOT NULL,
    reason varchar(2000) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    CONSTRAINT uq_warehouse_operation_mark_recovery
        UNIQUE (warehouse_id, operation_id, recovery_version),
    CONSTRAINT ck_warehouse_operation_mark_recovery_audit_version
        CHECK (recovery_version > 0),
    CONSTRAINT ck_warehouse_operation_mark_recovery_audit_reason
        CHECK (length(btrim(reason)) BETWEEN 1 AND 2000)
);

CREATE FUNCTION public.reject_warehouse_operation_mark_recovery_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'warehouse operation mark recovery audit rows are immutable';
END;
$$;

CREATE TRIGGER trg_warehouse_operation_mark_recovery_audit_immutable
BEFORE UPDATE OR DELETE ON public.warehouse_operation_mark_recovery_audit
FOR EACH ROW EXECUTE FUNCTION public.reject_warehouse_operation_mark_recovery_audit_mutation();

-- Every table that can introduce a new lifecycle blocker takes the same warehouse advisory lock
-- as admission/readiness. This closes the last active->draining race for non-physical commercial
-- records while still allowing existing rows to progress toward a terminal state.
CREATE FUNCTION public.logistics_assert_warehouse_lifecycle_open(p_warehouse_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_warehouse_id IS NULL THEN
        RETURN;
    END IF;
    PERFORM pg_advisory_xact_lock(
        hashtextextended('warehouse-lifecycle:logistics:' || p_warehouse_id::text, 0));
    IF EXISTS (
        SELECT 1 FROM public.logistics_warehouse_readiness_fence
         WHERE warehouse_id = p_warehouse_id
    ) THEN
        RAISE EXCEPTION 'logistics warehouse lifecycle is fenced for readiness'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION public.logistics_guard_primary_warehouse_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM public.logistics_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id THEN
        PERFORM public.logistics_assert_warehouse_lifecycle_open(NEW.warehouse_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.logistics_guard_document_warehouse_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    warehouse uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        FOR warehouse IN
            SELECT DISTINCT candidate
              FROM unnest(ARRAY[NEW.warehouse_id, NEW.destination_warehouse_id]) candidate
             WHERE candidate IS NOT NULL
             ORDER BY candidate
        LOOP
            PERFORM public.logistics_assert_warehouse_lifecycle_open(warehouse);
        END LOOP;
    ELSIF NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
       OR NEW.destination_warehouse_id IS DISTINCT FROM OLD.destination_warehouse_id THEN
        FOR warehouse IN
            SELECT DISTINCT candidate
              FROM unnest(ARRAY[NEW.warehouse_id, NEW.destination_warehouse_id]) candidate
             WHERE candidate IS NOT NULL
             ORDER BY candidate
        LOOP
            PERFORM public.logistics_assert_warehouse_lifecycle_open(warehouse);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.logistics_guard_movement_line_warehouse_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    warehouse uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        FOR warehouse IN
            SELECT DISTINCT candidate
              FROM unnest(ARRAY[NEW.source_warehouse_id, NEW.target_warehouse_id]) candidate
             WHERE candidate IS NOT NULL
             ORDER BY candidate
        LOOP
            PERFORM public.logistics_assert_warehouse_lifecycle_open(warehouse);
        END LOOP;
    ELSIF NEW.source_warehouse_id IS DISTINCT FROM OLD.source_warehouse_id
       OR NEW.target_warehouse_id IS DISTINCT FROM OLD.target_warehouse_id THEN
        FOR warehouse IN
            SELECT DISTINCT candidate
              FROM unnest(ARRAY[NEW.source_warehouse_id, NEW.target_warehouse_id]) candidate
             WHERE candidate IS NOT NULL
             ORDER BY candidate
        LOOP
            PERFORM public.logistics_assert_warehouse_lifecycle_open(warehouse);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_logistics_document_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF warehouse_id,destination_warehouse_id
ON public.logistics_document
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_document_warehouse_insert();

CREATE TRIGGER trg_equipment_movement_task_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF warehouse_id ON public.equipment_movement_task
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_primary_warehouse_insert();

CREATE TRIGGER trg_equipment_movement_line_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF source_warehouse_id,target_warehouse_id
ON public.equipment_movement_task_line
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_movement_line_warehouse_insert();

CREATE TRIGGER trg_driver_logistics_task_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF warehouse_id ON public.driver_logistics_task
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_primary_warehouse_insert();

CREATE TRIGGER trg_rental_order_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF warehouse_id ON public.rental_order
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_primary_warehouse_insert();

CREATE TRIGGER trg_rental_inquiry_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF warehouse_id ON public.rental_inquiry
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_primary_warehouse_insert();

CREATE TRIGGER trg_client_presentation_warehouse_lifecycle
BEFORE INSERT OR UPDATE OF warehouse_id ON public.client_presentation
FOR EACH ROW EXECUTE FUNCTION public.logistics_guard_primary_warehouse_insert();

-- A warehouse is operational as soon as rental work is bound to it, even when the first fact is
-- an inquiry/order/presentation rather than a transport document. The marker is inserted in the
-- same transaction and only once per warehouse; warehouse-service keeps the permanent operated
-- truth after delivery.
CREATE FUNCTION public.logistics_mark_first_warehouse_operation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    next_row jsonb := to_jsonb(NEW);
    previous_row jsonb;
    warehouse uuid;
    aggregate_id uuid;
    occurred timestamptz;
    marker_id uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        previous_row := to_jsonb(OLD);
        IF next_row ->> 'warehouse_id' IS NOT DISTINCT FROM previous_row ->> 'warehouse_id' THEN
            RETURN NEW;
        END IF;
    END IF;
    IF next_row ->> 'warehouse_id' IS NULL THEN
        RETURN NEW;
    END IF;
    warehouse := (next_row ->> 'warehouse_id')::uuid;
    aggregate_id := (next_row ->> 'id')::uuid;
    occurred := coalesce(
        nullif(next_row ->> 'updated_at', '')::timestamptz,
        nullif(next_row ->> 'created_at', '')::timestamptz,
        clock_timestamp());
    marker_id := md5(
        'logistics-first-warehouse-operation:' || TG_TABLE_NAME || ':' || aggregate_id::text)::uuid;
    INSERT INTO public.warehouse_operation_mark_outbox(
        operation_id,warehouse_id,occurred_at,state,attempt_count,next_attempt_at,
        created_at,updated_at)
    SELECT marker_id,warehouse,occurred,'PENDING',0,clock_timestamp(),
           clock_timestamp(),clock_timestamp()
     WHERE NOT EXISTS (
         SELECT 1 FROM public.warehouse_operation_mark_outbox existing
          WHERE existing.warehouse_id=warehouse);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_rental_order_first_warehouse_operation
AFTER INSERT OR UPDATE OF warehouse_id ON public.rental_order
FOR EACH ROW EXECUTE FUNCTION public.logistics_mark_first_warehouse_operation();

CREATE TRIGGER trg_rental_inquiry_first_warehouse_operation
AFTER INSERT OR UPDATE OF warehouse_id ON public.rental_inquiry
FOR EACH ROW EXECUTE FUNCTION public.logistics_mark_first_warehouse_operation();

CREATE TRIGGER trg_client_presentation_first_warehouse_operation
AFTER INSERT OR UPDATE OF warehouse_id ON public.client_presentation
FOR EACH ROW EXECUTE FUNCTION public.logistics_mark_first_warehouse_operation();

WITH candidates AS (
    SELECT id,'rental_order'::text AS source,warehouse_id,updated_at AS occurred_at
      FROM public.rental_order WHERE warehouse_id IS NOT NULL
    UNION ALL
    SELECT id,'rental_inquiry',warehouse_id,updated_at
      FROM public.rental_inquiry WHERE warehouse_id IS NOT NULL
    UNION ALL
    SELECT id,'client_presentation',warehouse_id,created_at
      FROM public.client_presentation
), earliest AS (
    SELECT DISTINCT ON (warehouse_id)
           id,source,warehouse_id,occurred_at
      FROM candidates
     ORDER BY warehouse_id,occurred_at,id
)
INSERT INTO public.warehouse_operation_mark_outbox(
    operation_id,warehouse_id,occurred_at,state,attempt_count,next_attempt_at,
    created_at,updated_at)
SELECT md5(
           'logistics-first-warehouse-operation:' || source || ':' || id::text)::uuid,
       warehouse_id,occurred_at,'PENDING',0,clock_timestamp(),
       clock_timestamp(),clock_timestamp()
  FROM earliest
ON CONFLICT (warehouse_id,operation_id) DO NOTHING;
