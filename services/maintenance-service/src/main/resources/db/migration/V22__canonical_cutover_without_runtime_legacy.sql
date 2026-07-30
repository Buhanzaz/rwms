-- One-time canonicalization.  After this migration application code reads only the current
-- snapshot shape and never repairs old durations, routes, fingerprints, or event fields at
-- runtime.

CREATE FUNCTION public.maintenance_v22_money(minor bigint)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
  SELECT (minor / 100)::text || '.' || lpad((minor % 100)::text, 2, '0')
$$;

CREATE FUNCTION public.maintenance_v22_catalog_snapshot(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  snapshot_catalog_id uuid;
  snapshot_node_id uuid;
  snapshot_node_type text;
  snapshot_node_name text;
  snapshot_node_unit text;
  snapshot_node_price bigint;
  snapshot_node_duration integer;
  snapshot_queue_id uuid;
  snapshot_queue_name text;
  snapshot_queue_type text;
  route jsonb;
BEGIN
  IF document IS NULL OR document = 'null'::jsonb THEN
    RETURN NULL;
  END IF;
  IF jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'catalog snapshot must be an object';
  END IF;

  snapshot_catalog_id := NULLIF(document ->> 'catalogVersionId', '')::uuid;
  snapshot_node_id := NULLIF(document ->> 'nodeId', '')::uuid;

  IF snapshot_catalog_id IS NOT NULL AND snapshot_node_id IS NOT NULL THEN
    SELECT
      catalog.node_type,
      catalog.name,
      catalog.unit,
      catalog.price_minor,
      catalog.duration_minutes
    INTO
      snapshot_node_type,
      snapshot_node_name,
      snapshot_node_unit,
      snapshot_node_price,
      snapshot_node_duration
    FROM public.catalog_node AS catalog
    WHERE catalog.catalog_version_id = snapshot_catalog_id
      AND catalog.node_id = snapshot_node_id;
  END IF;

  snapshot_node_type := upper(COALESCE(
    NULLIF(document ->> 'nodeType', ''),
    snapshot_node_type
  ));
  snapshot_node_name := COALESCE(
    NULLIF(document ->> 'name', ''),
    snapshot_node_name
  );
  snapshot_node_unit := COALESCE(
    NULLIF(document ->> 'unit', ''),
    snapshot_node_unit
  );
  snapshot_node_duration := CASE
    WHEN snapshot_node_type = 'WORK' THEN
      CASE
        WHEN COALESCE(
            NULLIF(document ->> 'durationMinutes', '')::integer,
            snapshot_node_duration,
            0
          ) < 1
          THEN 60
        ELSE COALESCE(
          NULLIF(document ->> 'durationMinutes', '')::integer,
          snapshot_node_duration
        )
      END
    WHEN snapshot_node_type = 'MATERIAL' THEN 0
    ELSE COALESCE(
      NULLIF(document ->> 'durationMinutes', '')::integer,
      snapshot_node_duration,
      0
    )
  END;

  IF document -> 'routing' IS NOT NULL
      AND jsonb_typeof(document -> 'routing') = 'object' THEN
    snapshot_queue_id := NULLIF(document #>> '{routing,queueId}', '')::uuid;
    snapshot_queue_name := NULLIF(document #>> '{routing,queueName}', '');
    snapshot_queue_type := NULLIF(document #>> '{routing,queueType}', '');
  END IF;

  IF snapshot_catalog_id IS NOT NULL
      AND snapshot_node_id IS NOT NULL
      AND (
        snapshot_queue_id IS NULL
        OR snapshot_queue_name IS NULL
        OR snapshot_queue_type IS NULL
      ) THEN
    WITH RECURSIVE lineage AS (
      SELECT catalog.*, 0 AS depth
      FROM public.catalog_node AS catalog
      WHERE catalog.catalog_version_id = snapshot_catalog_id
        AND catalog.node_id = snapshot_node_id
      UNION ALL
      SELECT parent.*, child.depth + 1
      FROM lineage AS child
      JOIN public.catalog_node AS parent
        ON parent.catalog_version_id = child.catalog_version_id
       AND parent.node_id = child.parent_node_id
      WHERE child.depth < 100
    )
    SELECT
      COALESCE(snapshot_queue_id, lineage.routing_queue_id),
      lineage.routing_queue_name,
      lineage.routing_queue_type
    INTO snapshot_queue_id, snapshot_queue_name, snapshot_queue_type
    FROM lineage
    WHERE lineage.routing_queue_id IS NOT NULL
      AND (
        snapshot_queue_id IS NULL
        OR lineage.routing_queue_id = snapshot_queue_id
      )
    ORDER BY lineage.depth
    LIMIT 1;
  END IF;

  IF snapshot_queue_id IS NULL THEN
    route := NULL;
  ELSIF snapshot_queue_name IS NULL OR snapshot_queue_type IS NULL THEN
    RAISE EXCEPTION
      'cannot resolve queue-definition snapshot % for catalog node %/%',
      snapshot_queue_id,
      snapshot_catalog_id,
      snapshot_node_id;
  ELSE
    route := jsonb_build_object(
      'queueId', snapshot_queue_id,
      'queueName', snapshot_queue_name,
      'queueType', upper(snapshot_queue_type)
    );
  END IF;

  IF snapshot_node_type NOT IN ('WORK', 'MATERIAL')
      OR snapshot_node_name IS NULL
      OR snapshot_node_unit IS NULL THEN
    RAISE EXCEPTION
      'cannot canonicalize catalog line snapshot for node %/%',
      snapshot_catalog_id,
      snapshot_node_id;
  END IF;

  result :=
    document
    - 'code'
    - 'catalogNodeCode'
    - 'routingQueueCode'
    - 'queueCode'
    - 'equipmentCode'
    - 'opaqueReferences'
    - 'references';
  result := result || jsonb_build_object(
    'catalogVersionId', snapshot_catalog_id,
    'nodeId', snapshot_node_id,
    'nodeType', snapshot_node_type,
    'name', snapshot_node_name,
    'unit', snapshot_node_unit,
    'unitPrice', COALESCE(
      NULLIF(document ->> 'unitPrice', ''),
      CASE WHEN snapshot_node_price IS NULL THEN '0.00'
        ELSE public.maintenance_v22_money(snapshot_node_price) END),
    'durationMinutes', snapshot_node_duration,
    'routing', route,
    'furnitureEquipment', COALESCE(document -> 'furnitureEquipment', 'null'::jsonb),
    'forcesCapitalRepair', COALESCE(document -> 'forcesCapitalRepair', 'false'::jsonb),
    'characteristic', COALESCE(document -> 'characteristic', 'null'::jsonb)
  );
  RETURN result;
END
$$;

CREATE FUNCTION public.maintenance_v22_estimate_line(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  catalog jsonb;
  line_type text;
  duration integer;
  unit_name text;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'estimate line snapshot must be an object';
  END IF;

  catalog := public.maintenance_v22_catalog_snapshot(document -> 'catalogSnapshot');
  line_type := upper(COALESCE(
    NULLIF(document ->> 'lineType', ''),
    NULLIF(catalog ->> 'nodeType', '')
  ));
  IF line_type NOT IN ('WORK', 'MATERIAL') THEN
    RAISE EXCEPTION 'estimate line snapshot has no canonical line type';
  END IF;

  unit_name := COALESCE(
    NULLIF(document ->> 'unit', ''),
    NULLIF(catalog ->> 'unit', '')
  );
  IF unit_name IS NULL THEN
    RAISE EXCEPTION 'estimate line snapshot has no canonical unit';
  END IF;

  duration := CASE
    WHEN line_type = 'WORK' THEN
      CASE
        WHEN COALESCE(NULLIF(document ->> 'normativeMinutes', '')::integer, 0) < 1
          THEN COALESCE(NULLIF(catalog ->> 'durationMinutes', '')::integer, 60)
        ELSE (document ->> 'normativeMinutes')::integer
      END
    ELSE 0
  END;

  result := document || jsonb_build_object(
    'catalogSnapshot', catalog,
    'lineType', line_type,
    'unit', unit_name,
    'normativeMinutes', duration,
    'mediaReferences', COALESCE(document -> 'mediaReferences', '[]'::jsonb)
  );
  RETURN result;
END
$$;

CREATE FUNCTION public.maintenance_v22_estimate_lines(lines jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb := '[]'::jsonb;
  line jsonb;
BEGIN
  IF lines IS NULL OR jsonb_typeof(lines) <> 'array' THEN
    RAISE EXCEPTION 'estimate line collection must be an array';
  END IF;
  FOR line IN SELECT value FROM jsonb_array_elements(lines)
  LOOP
    result := result || jsonb_build_array(
      public.maintenance_v22_estimate_line(line));
  END LOOP;
  RETURN result;
END
$$;

UPDATE public.catalog_node
SET duration_minutes = 60
WHERE node_type = 'WORK'
  AND duration_minutes < 1;

UPDATE public.estimate_line
SET unit = COALESCE(
      NULLIF(btrim(unit), ''),
      NULLIF(btrim(catalog_snapshot ->> 'unit'), '')
    ),
    duration_minutes = CASE
      WHEN line_type = 'WORK' AND COALESCE(duration_minutes, 0) < 1 THEN 60
      WHEN line_type = 'MATERIAL' THEN 0
      ELSE duration_minutes
    END,
    catalog_snapshot = public.maintenance_v22_catalog_snapshot(catalog_snapshot);

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.estimate_line
    WHERE unit IS NULL OR btrim(unit) = ''
  ) THEN
    RAISE EXCEPTION
      'estimate lines without a canonical unit require controlled data recovery';
  END IF;
END
$$;

UPDATE public.repair_stage
SET work_lines = public.maintenance_v22_estimate_lines(work_lines),
    material_lines = public.maintenance_v22_estimate_lines(material_lines);

ALTER TABLE public.catalog_node
  DROP CONSTRAINT ck_catalog_node_duration,
  ADD CONSTRAINT ck_catalog_node_duration CHECK (
    (node_type = 'WORK' AND duration_minutes BETWEEN 1 AND 525600)
    OR
    (node_type <> 'WORK' AND duration_minutes BETWEEN 0 AND 525600)
  );

ALTER TABLE public.estimate_line
  DROP CONSTRAINT ck_estimate_line_duration,
  ALTER COLUMN unit SET NOT NULL,
  ALTER COLUMN duration_minutes SET NOT NULL,
  ADD CONSTRAINT ck_estimate_line_unit
    CHECK (length(btrim(unit)) BETWEEN 1 AND 32),
  ADD CONSTRAINT ck_estimate_line_duration CHECK (
    (line_type = 'WORK' AND duration_minutes BETWEEN 1 AND 525600)
    OR
    (line_type = 'MATERIAL' AND duration_minutes = 0)
  );

CREATE FUNCTION public.maintenance_v22_inventory_plan(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  lines jsonb := '[]'::jsonb;
  stages jsonb := '[]'::jsonb;
  line jsonb;
  stage jsonb;
  synthetic jsonb;
  canonical jsonb;
  line_type text;
  duration text;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'inventory repair plan must be an object';
  END IF;

  FOR line IN
    SELECT value
    FROM jsonb_array_elements(COALESCE(document -> 'lines', '[]'::jsonb))
  LOOP
    line_type := upper(NULLIF(line ->> 'type', ''));
    duration := CASE
      WHEN line_type = 'WORK'
          AND COALESCE(NULLIF(line ->> 'normativeMinutes', '')::numeric, 0) <= 0
        THEN '60'
      WHEN line_type = 'MATERIAL' THEN '0'
      ELSE line ->> 'normativeMinutes'
    END;
    IF line ->> 'aggregationKind' = 'CATALOG' THEN
      synthetic := jsonb_build_object(
        'catalogVersionId', line -> 'catalogVersionId',
        'nodeId', line -> 'catalogNodeId',
        'nodeType', line -> 'type',
        'name', line -> 'catalogNodeName',
        'unit', line -> 'unit',
        'unitPrice', public.maintenance_v22_money(
          COALESCE((line ->> 'unitPriceMinor')::bigint, 0)),
        'durationMinutes', duration,
        'routing', line -> 'routing',
        'furnitureEquipment', NULL
      );
      canonical := public.maintenance_v22_catalog_snapshot(synthetic);
      line := line || jsonb_build_object(
        'routing', canonical -> 'routing',
        'normativeMinutes', duration,
        'forcesCapitalRepair', COALESCE(
          line -> 'forcesCapitalRepair', 'false'::jsonb),
        'characteristic', COALESCE(
          line -> 'characteristic', 'null'::jsonb)
      );
    ELSE
      line := line || jsonb_build_object(
        'normativeMinutes', duration,
        'forcesCapitalRepair', false,
        'characteristic', NULL
      );
    END IF;
    lines := lines || jsonb_build_array(line);
  END LOOP;

  FOR stage IN
    SELECT value
    FROM jsonb_array_elements(COALESCE(document -> 'stages', '[]'::jsonb))
  LOOP
    IF stage -> 'routing' IS NOT NULL
        AND (
          NULLIF(stage #>> '{routing,queueName}', '') IS NULL
          OR NULLIF(stage #>> '{routing,queueType}', '') IS NULL
        ) THEN
      synthetic := jsonb_build_object(
        'catalogVersionId', document -> 'catalogVersionId',
        'nodeId', stage -> 'catalogNodeId',
        'nodeType', 'WORK',
        'name', COALESCE(stage -> 'catalogNodeName', '"Маршрут"'::jsonb),
        'unit', 'шт.',
        'unitPrice', '0.00',
        'durationMinutes', 1,
        'routing', stage -> 'routing',
        'furnitureEquipment', NULL
      );
      canonical := public.maintenance_v22_catalog_snapshot(synthetic);
      stage := stage || jsonb_build_object('routing', canonical -> 'routing');
    END IF;
    stages := stages || jsonb_build_array(stage);
  END LOOP;

  result := document || jsonb_build_object(
    'lines', lines,
    'stages', stages,
    'priority', COALESCE(document -> 'priority', '3'::jsonb),
    'coverMediaId', COALESCE(document -> 'coverMediaId', 'null'::jsonb)
  );
  RETURN result;
END
$$;

CREATE FUNCTION public.maintenance_v22_freeze_request(
  warehouse_id uuid,
  inventory_id uuid,
  finding_id uuid,
  source_revision bigint,
  snapshot jsonb
)
RETURNS jsonb
LANGUAGE sql
STABLE
AS $$
  SELECT jsonb_build_object(
    'warehouseId', warehouse_id,
    'inventoryId', inventory_id,
    'findingId', finding_id,
    'sourceRevision', source_revision,
    'mode', snapshot -> 'mode',
    'lines', COALESCE((
      SELECT jsonb_agg(
        CASE line ->> 'aggregationKind'
          WHEN 'CATALOG' THEN jsonb_build_object(
            'aggregationKind', 'CATALOG',
            'catalogNodeId', line -> 'catalogNodeId',
            'description', NULL,
            'type', NULL,
            'unit', NULL,
            'quantity', line -> 'quantity',
            'unitPriceMinor', NULL,
            'normativeMinutes', NULL,
            'groupComment', line -> 'groupComment',
            'mediaReferences', COALESCE(line -> 'mediaReferences', '[]'::jsonb)
          )
          ELSE jsonb_build_object(
            'aggregationKind', 'MANUAL',
            'catalogNodeId', NULL,
            'description', line -> 'description',
            'type', line -> 'type',
            'unit', line -> 'unit',
            'quantity', line -> 'quantity',
            'unitPriceMinor', line -> 'unitPriceMinor',
            'normativeMinutes', line -> 'normativeMinutes',
            'groupComment', line -> 'groupComment',
            'mediaReferences', COALESCE(line -> 'mediaReferences', '[]'::jsonb)
          )
        END
        ORDER BY position
      )
      FROM jsonb_array_elements(snapshot -> 'lines')
        WITH ORDINALITY AS source(line, position)
    ), '[]'::jsonb),
    'plan', COALESCE((
      SELECT jsonb_agg(
        jsonb_build_object(
          'catalogNodeId', stage -> 'catalogNodeId',
          'kind', stage -> 'kind',
          'order', stage -> 'order'
        )
        ORDER BY position
      )
      FROM jsonb_array_elements(snapshot -> 'stages')
        WITH ORDINALITY AS source(stage, position)
    ), '[]'::jsonb),
    'mediaReferences', COALESCE(snapshot -> 'mediaReferences', '[]'::jsonb),
    'priority', snapshot -> 'priority',
    'coverMediaId', snapshot -> 'coverMediaId'
  )
$$;

DROP TRIGGER trg_inventory_repair_source_immutable
  ON public.inventory_repair_source;

UPDATE public.inventory_repair_source
SET plan_snapshot =
      public.maintenance_v22_inventory_plan(plan_snapshot);

UPDATE public.inventory_repair_source
SET plan_fingerprint = encode(
      sha256(convert_to(plan_snapshot::text, 'UTF8')),
      'hex'
    ),
    plan_request_sha256 = encode(
      sha256(convert_to(
        public.maintenance_v22_freeze_request(
          warehouse_id,
          inventory_id,
          finding_id,
          source_revision,
          plan_snapshot
        )::text,
        'UTF8'
      )),
      'hex'
    );

UPDATE public.inventory_repair_source_operation AS operation
SET request_sha256 = source.plan_request_sha256
FROM public.inventory_repair_source AS source
WHERE source.inventory_id = operation.inventory_id
  AND source.finding_id = operation.finding_id;

UPDATE public.inventory_repair_source AS source
SET source_fingerprint = encode(
      sha256(convert_to(
        jsonb_build_object(
          'inventoryId', source.inventory_id,
          'findingId', source.finding_id,
          'sourceRevision', source.source_revision,
          'warehouseId', source.warehouse_id,
          'rentalItemId', source.rental_item_id,
          'rentalItemVersion', source.rental_item_version_snapshot,
          'dispatchDate', repair.dispatch_date,
          'planFingerprint', source.plan_fingerprint,
          'snapshot', source.plan_snapshot
        )::text,
        'UTF8'
      )),
      'hex'
    )
FROM public.maintenance_repair AS repair
WHERE source.repair_id = repair.id;

CREATE TRIGGER trg_inventory_repair_source_immutable
BEFORE UPDATE ON public.inventory_repair_source
FOR EACH ROW
EXECUTE FUNCTION public.enforce_inventory_repair_source_immutability();

CREATE FUNCTION public.maintenance_v22_state(
  aggregate_type text,
  aggregate_id uuid,
  document jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb := document;
  items jsonb := '[]'::jsonb;
  nested jsonb := '[]'::jsonb;
  item jsonb;
  child jsonb;
  canonical_lines jsonb;
  node_type text;
  duration integer;
  route_id uuid;
  route_name text;
  route_type text;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'maintenance full-state snapshot must be an object';
  END IF;

  IF aggregate_type = 'CATALOG_VERSION' THEN
    FOR item IN
      SELECT value
      FROM jsonb_array_elements(COALESCE(document -> 'nodes', '[]'::jsonb))
    LOOP
      node_type := upper(NULLIF(item ->> 'nodeType', ''));
      duration := COALESCE(NULLIF(item ->> 'durationMinutes', '')::integer, 0);
      IF node_type = 'WORK' AND duration < 1 THEN
        duration := 60;
      END IF;
      SELECT
        node.routing_queue_id,
        node.routing_queue_name,
        node.routing_queue_type
      INTO route_id, route_name, route_type
      FROM public.catalog_node AS node
      WHERE node.catalog_version_id = aggregate_id
        AND node.node_id = NULLIF(item ->> 'id', '')::uuid;
      item :=
        item
        - 'photoRequired'
        - 'mediaReferences'
        - 'mediaOwnerId'
        - 'media'
        - 'furnitureCategory'
        - 'furnitureEquipment';
      item := item || jsonb_build_object(
        'durationMinutes', duration,
        'canvasX', COALESCE(item -> 'canvasX', 'null'::jsonb),
        'canvasY', COALESCE(item -> 'canvasY', 'null'::jsonb),
        'displayColor', COALESCE(item -> 'displayColor', 'null'::jsonb),
        'forcesCapitalRepair', COALESCE(
          item -> 'forcesCapitalRepair', 'false'::jsonb),
        'characteristic', COALESCE(
          item -> 'characteristic', 'null'::jsonb),
        'routing', CASE
          WHEN route_id IS NULL THEN NULL
          ELSE jsonb_build_object(
            'queueId', route_id,
            'queueName', route_name,
            'queueType', route_type
          )
        END
      );
      items := items || jsonb_build_array(item);
    END LOOP;
    RETURN result || jsonb_build_object('nodes', items);
  END IF;

  IF aggregate_type = 'ESTIMATE' THEN
    FOR item IN
      SELECT value
      FROM jsonb_array_elements(COALESCE(document -> 'revisions', '[]'::jsonb))
    LOOP
      nested := '[]'::jsonb;
      FOR child IN
        SELECT value
        FROM jsonb_array_elements(COALESCE(item -> 'lines', '[]'::jsonb))
      LOOP
        node_type := upper(COALESCE(
          NULLIF(child ->> 'lineType', ''),
          NULLIF(child #>> '{catalogSnapshot,nodeType}', '')
        ));
        duration := COALESCE(NULLIF(child ->> 'durationMinutes', '')::integer, 0);
        IF node_type = 'WORK' AND duration < 1 THEN
          duration := 60;
        ELSIF node_type = 'MATERIAL' THEN
          duration := 0;
        END IF;
        child := child || jsonb_build_object(
          'lineType', node_type,
          'unit', COALESCE(
            NULLIF(child ->> 'unit', ''),
            NULLIF(child #>> '{catalogSnapshot,unit}', '')
          ),
          'durationMinutes', duration,
          'catalogSnapshot',
            public.maintenance_v22_catalog_snapshot(
              child -> 'catalogSnapshot')
        );
        nested := nested || jsonb_build_array(child);
      END LOOP;
      item := item || jsonb_build_object('lines', nested);

      nested := '[]'::jsonb;
      FOR child IN
        SELECT value
        FROM jsonb_array_elements(COALESCE(item -> 'plan', '[]'::jsonb))
      LOOP
        SELECT
          stage.routing_queue_id,
          stage.routing_queue_name,
          stage.routing_queue_type
        INTO route_id, route_name, route_type
        FROM public.estimate_plan_stage AS stage
        WHERE stage.estimate_id = aggregate_id
          AND stage.estimate_revision = (item ->> 'revision')::integer
          AND stage.stage_id = NULLIF(child ->> 'id', '')::uuid;
        child :=
          child
          - 'routingQueueCode'
          - 'routingQueueKind';
        child := child || jsonb_build_object(
          'routingQueueName', route_name,
          'routingQueueType', route_type,
          'includedLineIds', COALESCE(
            child -> 'includedLineIds', '[]'::jsonb),
          'primaryLineId', COALESCE(
            child -> 'primaryLineId', 'null'::jsonb),
          'groupComment', COALESCE(
            child -> 'groupComment', '""'::jsonb),
          'taskDeadline', COALESCE(
            child -> 'taskDeadline', 'null'::jsonb)
        );
        nested := nested || jsonb_build_array(child);
      END LOOP;
      item := item || jsonb_build_object('plan', nested);
      items := items || jsonb_build_array(item);
    END LOOP;
    RETURN result || jsonb_build_object(
      'revisions', items,
      'coverMediaId', COALESCE(document -> 'coverMediaId', 'null'::jsonb)
    );
  END IF;

  IF aggregate_type = 'REPAIR' THEN
    FOR item IN
      SELECT value
      FROM jsonb_array_elements(COALESCE(document -> 'stages', '[]'::jsonb))
    LOOP
      SELECT
        stage.routing_queue_id,
        stage.routing_queue_name,
        stage.routing_queue_type
      INTO route_id, route_name, route_type
      FROM public.repair_stage AS stage
      WHERE stage.repair_id = aggregate_id
        AND stage.stage_id = NULLIF(item ->> 'id', '')::uuid;

      canonical_lines := CASE jsonb_typeof(item -> 'workLines')
        WHEN 'array' THEN item -> 'workLines'
        WHEN 'string' THEN (item ->> 'workLines')::jsonb
        ELSE '[]'::jsonb
      END;
      canonical_lines :=
        public.maintenance_v22_estimate_lines(canonical_lines);
      item := jsonb_set(
        item,
        '{workLines}',
        to_jsonb(canonical_lines::text),
        true
      );

      canonical_lines := CASE jsonb_typeof(item -> 'materialLines')
        WHEN 'array' THEN item -> 'materialLines'
        WHEN 'string' THEN (item ->> 'materialLines')::jsonb
        ELSE '[]'::jsonb
      END;
      canonical_lines :=
        public.maintenance_v22_estimate_lines(canonical_lines);
      item := jsonb_set(
        item,
        '{materialLines}',
        to_jsonb(canonical_lines::text),
        true
      );

      item :=
        item
        - 'routingQueueCode'
        - 'routingQueueKind';
      item := item || jsonb_build_object(
        'routingQueueName', route_name,
        'routingQueueType', route_type,
        'primaryLineId', COALESCE(
          item -> 'primaryLineId', 'null'::jsonb),
        'groupComment', COALESCE(
          item -> 'groupComment', '""'::jsonb)
      );
      items := items || jsonb_build_array(item);
    END LOOP;
    RETURN result || jsonb_build_object(
      'priority', COALESCE(document -> 'priority', '3'::jsonb),
      'coverMediaId', COALESCE(document -> 'coverMediaId', 'null'::jsonb),
      'movementToShipment', COALESCE(
        document -> 'movementToShipment', 'false'::jsonb),
      'transferState', COALESCE(
        document -> 'transferState', '"NONE"'::jsonb),
      'transferDocumentId', COALESCE(
        document -> 'transferDocumentId', 'null'::jsonb),
      'transferLineId', COALESCE(
        document -> 'transferLineId', 'null'::jsonb),
      'transferTargetWarehouseId', COALESCE(
        document -> 'transferTargetWarehouseId', 'null'::jsonb),
      'stages', items
    );
  END IF;

  RETURN result;
END
$$;

WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      payload,
      '{state}',
      public.maintenance_v22_state(
        aggregate_type,
        aggregate_id::uuid,
        payload -> 'state'
      ),
      false
    ) AS payload
  FROM public.domain_event
)
UPDATE public.domain_event AS event
SET payload = normalized.payload,
    payload_sha256 = encode(
      sha256(convert_to(normalized.payload::text, 'UTF8')),
      'hex'
    )
FROM normalized
WHERE normalized.event_id = event.event_id;

WITH normalized AS (
  SELECT
    aggregate_type,
    aggregate_id,
    aggregate_version,
    public.maintenance_v22_state(
      aggregate_type,
      aggregate_id::uuid,
      state
    ) AS state
  FROM public.aggregate_snapshot
)
UPDATE public.aggregate_snapshot AS snapshot
SET state = normalized.state,
    state_sha256 = encode(
      sha256(convert_to(normalized.state::text, 'UTF8')),
      'hex'
    )
FROM normalized
WHERE normalized.aggregate_type = snapshot.aggregate_type
  AND normalized.aggregate_id = snapshot.aggregate_id
  AND normalized.aggregate_version = snapshot.aggregate_version;

WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      envelope_body,
      '{payload,priority}',
      COALESCE(envelope_body #> '{payload,priority}', '3'::jsonb),
      true
    ) AS envelope_body
  FROM public.outbox_event
  WHERE aggregate_type = 'REPAIR'
)
UPDATE public.outbox_event AS outbox
SET envelope_body = normalized.envelope_body,
    envelope_sha256 = encode(
      sha256(convert_to(normalized.envelope_body::text, 'UTF8')),
      'hex'
    )
FROM normalized
WHERE normalized.event_id = outbox.event_id;

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256,
    updated_at = clock_timestamp()
FROM public.event_stream_head AS head
JOIN public.aggregate_snapshot AS snapshot
  ON snapshot.aggregate_type = head.aggregate_type
 AND snapshot.aggregate_id = head.aggregate_id
 AND snapshot.aggregate_version = head.current_version
WHERE checkpoint.aggregate_type = head.aggregate_type
  AND checkpoint.aggregate_id = head.aggregate_id;

DO $$
DECLARE
  catalog record;
  previous_state jsonb;
  next_state jsonb;
  local_payload jsonb;
  integration_payload jsonb;
  envelope jsonb;
  event_id uuid;
  correlation_id uuid;
  recorded_at timestamptz;
  event_hash text;
  state_hash text;
  envelope_hash text;
BEGIN
  FOR catalog IN
    SELECT
      version.id,
      version.version,
      version.warehouse_id,
      version.state,
      version.source_sha256,
      version.node_count,
      version.link_count,
      version.validation_report,
      head.current_version
    FROM public.catalog_version AS version
    JOIN public.event_stream_head AS head
      ON head.aggregate_type = 'CATALOG_VERSION'
     AND head.aggregate_id = version.id::text
    WHERE version.version = head.current_version + 1
      AND version.state = 'SUPERSEDED'
    ORDER BY version.id
  LOOP
    SELECT snapshot.state
    INTO STRICT previous_state
    FROM public.aggregate_snapshot AS snapshot
    WHERE snapshot.aggregate_type = 'CATALOG_VERSION'
      AND snapshot.aggregate_id = catalog.id::text
      AND snapshot.aggregate_version = catalog.current_version;

    next_state :=
      jsonb_set(
        jsonb_set(
          previous_state,
          '{version}',
          to_jsonb(catalog.version),
          false
        ),
        '{lifecycle}',
        '"SUPERSEDED"'::jsonb,
        false
      );
    event_id := gen_random_uuid();
    correlation_id := gen_random_uuid();
    recorded_at := clock_timestamp();
    integration_payload := jsonb_build_object(
      'catalogVersionId', catalog.id,
      'warehouseId', catalog.warehouse_id,
      'lifecycle', catalog.state,
      'sourceSha256', catalog.source_sha256,
      'nodeCount', catalog.node_count,
      'linkCount', catalog.link_count,
      'validationReportSha256', encode(
        sha256(convert_to(catalog.validation_report, 'UTF8')),
        'hex'
      )
    );
    local_payload := jsonb_build_object(
      'model', 'maintenance-full-state-v1',
      'event', jsonb_build_object(
        'catalogVersionId', catalog.id,
        'migration', 'global-catalog-cutover-v1'
      ),
      'state', next_state
    );
    envelope := jsonb_build_object(
      'envelopeVersion', 2,
      'eventId', event_id,
      'eventType', 'maintenance.catalog-version.superseded.v1',
      'eventVersion', 1,
      'occurredAt', to_jsonb(recorded_at),
      'recordedAt', to_jsonb(recorded_at),
      'producer', 'maintenance-service',
      'aggregateType', 'CATALOG_VERSION',
      'aggregateId', catalog.id::text,
      'aggregateVersion', catalog.version,
      'correlation', jsonb_build_object(
        'correlationId', correlation_id,
        'causationId', NULL
      ),
      'actorRef', NULL,
      'payload', integration_payload
    );
    event_hash := encode(
      sha256(convert_to(local_payload::text, 'UTF8')),
      'hex'
    );
    state_hash := encode(
      sha256(convert_to(next_state::text, 'UTF8')),
      'hex'
    );
    envelope_hash := encode(
      sha256(convert_to(envelope::text, 'UTF8')),
      'hex'
    );

    UPDATE public.event_stream_head
    SET current_version = catalog.version,
        last_event_id = event_id,
        updated_at = recorded_at
    WHERE aggregate_type = 'CATALOG_VERSION'
      AND aggregate_id = catalog.id::text
      AND current_version = catalog.current_version;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'catalog stream changed during global cutover: %', catalog.id;
    END IF;

    INSERT INTO public.domain_event(
      event_id,
      aggregate_type,
      aggregate_id,
      aggregate_version,
      event_type,
      event_version,
      occurred_at,
      recorded_at,
      correlation_id,
      causation_id,
      actor_ref,
      payload,
      payload_sha256,
      baseline
    ) VALUES (
      event_id,
      'CATALOG_VERSION',
      catalog.id::text,
      catalog.version,
      'maintenance.catalog-version.superseded.v1',
      1,
      recorded_at,
      recorded_at,
      correlation_id,
      NULL,
      NULL,
      local_payload,
      event_hash,
      false
    );

    INSERT INTO public.aggregate_snapshot(
      aggregate_type,
      aggregate_id,
      aggregate_version,
      state,
      state_sha256,
      recorded_at
    ) VALUES (
      'CATALOG_VERSION',
      catalog.id::text,
      catalog.version,
      next_state,
      state_hash,
      recorded_at
    );

    INSERT INTO public.outbox_event(
      event_id,
      aggregate_type,
      aggregate_id,
      aggregate_version,
      event_type,
      topic,
      envelope_body,
      envelope_sha256,
      status,
      attempt_count,
      next_attempt_at,
      created_at
    ) VALUES (
      event_id,
      'CATALOG_VERSION',
      catalog.id::text,
      catalog.version,
      'maintenance.catalog-version.superseded.v1',
      'rwms.maintenance.catalog-version.v1',
      envelope,
      envelope_hash,
      'PENDING',
      0,
      recorded_at,
      recorded_at
    );

    INSERT INTO public.projection_checkpoint(
      projection_name,
      aggregate_type,
      aggregate_id,
      aggregate_version,
      projection_sha256,
      updated_at
    ) VALUES (
      'maintenance-live-v1',
      'CATALOG_VERSION',
      catalog.id::text,
      catalog.version,
      state_hash,
      recorded_at
    )
    ON CONFLICT (projection_name, aggregate_type, aggregate_id)
    DO UPDATE SET
      aggregate_version = EXCLUDED.aggregate_version,
      projection_sha256 = EXCLUDED.projection_sha256,
      updated_at = EXCLUDED.updated_at;
  END LOOP;

  IF EXISTS (
    SELECT 1
    FROM public.catalog_version AS version
    JOIN public.event_stream_head AS head
      ON head.aggregate_type = 'CATALOG_VERSION'
     AND head.aggregate_id = version.id::text
    WHERE version.version <> head.current_version
  ) THEN
    RAISE EXCEPTION
      'catalog projection and event streams differ after global cutover';
  END IF;
END
$$;

DROP FUNCTION public.maintenance_v22_state(text, uuid, jsonb);
DROP FUNCTION public.maintenance_v22_freeze_request(
  uuid, uuid, uuid, bigint, jsonb);
DROP FUNCTION public.maintenance_v22_inventory_plan(jsonb);
DROP FUNCTION public.maintenance_v22_estimate_lines(jsonb);
DROP FUNCTION public.maintenance_v22_estimate_line(jsonb);
DROP FUNCTION public.maintenance_v22_catalog_snapshot(jsonb);
DROP FUNCTION public.maintenance_v22_money(bigint);
