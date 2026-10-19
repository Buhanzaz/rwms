-- The active estimate catalog is the editable source of truth.  Older catalog versions are
-- immutable history and must not be rewritten.  A few imported active catalogs contained the
-- same WORK/MATERIAL more than once under the same parent; keep the best-connected definition
-- and remove the redundant graph position.

CREATE TEMP TABLE maintenance_v25_catalog_duplicate_map (
  catalog_version_id uuid NOT NULL,
  duplicate_node_id uuid NOT NULL,
  canonical_node_id uuid NOT NULL,
  PRIMARY KEY (catalog_version_id, duplicate_node_id)
) ON COMMIT DROP;

WITH ranked AS (
  SELECT
    node.catalog_version_id,
    node.node_id,
    first_value(node.node_id) OVER (
      PARTITION BY
        node.catalog_version_id,
        node.parent_node_id,
        node.node_type,
        lower(regexp_replace(btrim(node.name), '\s+', ' ', 'g'))
      ORDER BY
        (
          SELECT count(*)
          FROM public.estimate_line line
          WHERE line.catalog_node_id = node.node_id
        ) DESC,
        (
          SELECT count(*)
          FROM public.integration_reconciliation reconciliation
          WHERE reconciliation.catalog_version_id = node.catalog_version_id
            AND reconciliation.catalog_node_id = node.node_id
        ) DESC,
        (
          SELECT count(*)
          FROM public.catalog_link link
          WHERE link.catalog_version_id = node.catalog_version_id
            AND link.source_node_id = node.node_id
            AND link.link_type = 'DEPENDENCY'
        ) DESC,
        (
          SELECT count(*)
          FROM public.catalog_link link
          WHERE link.catalog_version_id = node.catalog_version_id
            AND (
              link.source_node_id = node.node_id
              OR link.target_node_id = node.node_id
            )
        ) DESC,
        node.active DESC,
        node.include_in_estimate DESC,
        node.node_id
    ) AS canonical_node_id,
    row_number() OVER (
      PARTITION BY
        node.catalog_version_id,
        node.parent_node_id,
        node.node_type,
        lower(regexp_replace(btrim(node.name), '\s+', ' ', 'g'))
      ORDER BY
        (
          SELECT count(*)
          FROM public.estimate_line line
          WHERE line.catalog_node_id = node.node_id
        ) DESC,
        (
          SELECT count(*)
          FROM public.integration_reconciliation reconciliation
          WHERE reconciliation.catalog_version_id = node.catalog_version_id
            AND reconciliation.catalog_node_id = node.node_id
        ) DESC,
        (
          SELECT count(*)
          FROM public.catalog_link link
          WHERE link.catalog_version_id = node.catalog_version_id
            AND link.source_node_id = node.node_id
            AND link.link_type = 'DEPENDENCY'
        ) DESC,
        (
          SELECT count(*)
          FROM public.catalog_link link
          WHERE link.catalog_version_id = node.catalog_version_id
            AND (
              link.source_node_id = node.node_id
              OR link.target_node_id = node.node_id
            )
        ) DESC,
        node.active DESC,
        node.include_in_estimate DESC,
        node.node_id
    ) AS position
  FROM public.catalog_node node
  JOIN public.catalog_version version
    ON version.id = node.catalog_version_id
   AND version.state = 'ACTIVE'
  WHERE node.node_type IN ('WORK', 'MATERIAL')
)
INSERT INTO maintenance_v25_catalog_duplicate_map(
  catalog_version_id,
  duplicate_node_id,
  canonical_node_id
)
SELECT catalog_version_id, node_id, canonical_node_id
FROM ranked
WHERE position > 1;

CREATE TEMP TABLE maintenance_v25_catalog_links (
  row_id uuid NOT NULL,
  link_id uuid NOT NULL,
  catalog_version_id uuid NOT NULL,
  source_node_id uuid NOT NULL,
  target_node_id uuid NOT NULL,
  link_type varchar(32) NOT NULL,
  source_anchor varchar(32),
  target_anchor varchar(32),
  sort_order integer NOT NULL
) ON COMMIT DROP;

DO $$
DECLARE
  catalog record;
  previous_state jsonb;
  next_state jsonb;
  previous_report jsonb;
  pre_report json;
  final_report json;
  report jsonb;
  report_text text;
  content_sha256 text;
  report_sha256 text;
  validation_report_sha256 text;
  local_payload jsonb;
  integration_payload jsonb;
  envelope jsonb;
  event_id uuid;
  correlation_id uuid;
  recorded_at timestamptz;
  recorded_text text;
  event_sha256 text;
  state_sha256 text;
  envelope_sha256 text;
  clean_node_count integer;
  clean_link_count integer;
  clean_material_count integer;
  dependency_acyclic boolean;
BEGIN
  FOR catalog IN
    SELECT
      version.id,
      version.version,
      version.warehouse_id,
      version.state,
      version.source_sha256,
      version.validation_report,
      head.current_version
    FROM public.catalog_version version
    JOIN public.event_stream_head head
      ON head.aggregate_type = 'CATALOG_VERSION'
     AND head.aggregate_id = version.id::text
    WHERE version.state = 'ACTIVE'
      AND EXISTS (
        SELECT 1
        FROM maintenance_v25_catalog_duplicate_map duplicate
        WHERE duplicate.catalog_version_id = version.id
      )
    ORDER BY version.id
  LOOP
    IF catalog.version <> catalog.current_version THEN
      RAISE EXCEPTION
        'active catalog projection and event stream differ before duplicate cleanup: %',
        catalog.id;
    END IF;

    SELECT snapshot.state
    INTO STRICT previous_state
    FROM public.aggregate_snapshot snapshot
    WHERE snapshot.aggregate_type = 'CATALOG_VERSION'
      AND snapshot.aggregate_id = catalog.id::text
      AND snapshot.aggregate_version = catalog.current_version;

    previous_report := catalog.validation_report::jsonb;
    recorded_at := clock_timestamp();
    recorded_text := to_char(
      recorded_at AT TIME ZONE 'UTC',
      'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');

    -- Preserve dependency edges, but remove FOLLOW_UP positions that point at the
    -- redundant definition.  Rewriting the complete link set keeps link IDs and sort
    -- order stable while allowing a duplicate work/material to retain its dependencies.
    TRUNCATE maintenance_v25_catalog_links;
    INSERT INTO maintenance_v25_catalog_links(
      row_id,
      link_id,
      catalog_version_id,
      source_node_id,
      target_node_id,
      link_type,
      source_anchor,
      target_anchor,
      sort_order
    )
    SELECT
      link.row_id,
      link.link_id,
      link.catalog_version_id,
      CASE
        WHEN link.link_type = 'DEPENDENCY'
          THEN COALESCE(source_map.canonical_node_id, link.source_node_id)
        ELSE link.source_node_id
      END,
      CASE
        WHEN link.link_type = 'DEPENDENCY'
          THEN COALESCE(target_map.canonical_node_id, link.target_node_id)
        ELSE link.target_node_id
      END,
      link.link_type,
      link.source_anchor,
      link.target_anchor,
      link.sort_order
    FROM public.catalog_link link
    LEFT JOIN maintenance_v25_catalog_duplicate_map source_map
      ON source_map.catalog_version_id = link.catalog_version_id
     AND source_map.duplicate_node_id = link.source_node_id
    LEFT JOIN maintenance_v25_catalog_duplicate_map target_map
      ON target_map.catalog_version_id = link.catalog_version_id
     AND target_map.duplicate_node_id = link.target_node_id
    WHERE link.catalog_version_id = catalog.id
      AND (
        link.link_type = 'DEPENDENCY'
        OR (source_map.duplicate_node_id IS NULL AND target_map.duplicate_node_id IS NULL)
      );

    UPDATE public.catalog_node child
    SET parent_node_id = duplicate.canonical_node_id
    FROM maintenance_v25_catalog_duplicate_map duplicate
    WHERE duplicate.catalog_version_id = catalog.id
      AND child.catalog_version_id = catalog.id
      AND child.parent_node_id = duplicate.duplicate_node_id;

    -- Catalog-node IDs are intentionally not foreign keys because estimate snapshots are
    -- historical.  New commands should resolve the canonical ID, while existing references
    -- continue to point at the same logical definition.
    UPDATE public.estimate_line line
    SET catalog_node_id = duplicate.canonical_node_id
    FROM maintenance_v25_catalog_duplicate_map duplicate
    WHERE duplicate.catalog_version_id = catalog.id
      AND line.catalog_node_id = duplicate.duplicate_node_id;

    UPDATE public.integration_reconciliation reconciliation
    SET catalog_node_id = duplicate.canonical_node_id
    FROM maintenance_v25_catalog_duplicate_map duplicate
    WHERE duplicate.catalog_version_id = catalog.id
      AND reconciliation.catalog_version_id = catalog.id
      AND reconciliation.catalog_node_id = duplicate.duplicate_node_id;

    DELETE FROM public.catalog_link
    WHERE catalog_version_id = catalog.id;

    INSERT INTO public.catalog_link(
      row_id,
      link_id,
      catalog_version_id,
      source_node_id,
      target_node_id,
      link_type,
      source_anchor,
      target_anchor,
      sort_order
    )
    SELECT
      links.row_id,
      links.link_id,
      links.catalog_version_id,
      links.source_node_id,
      links.target_node_id,
      links.link_type,
      links.source_anchor,
      links.target_anchor,
      links.sort_order
    FROM maintenance_v25_catalog_links links
    WHERE links.catalog_version_id = catalog.id
      AND links.source_node_id <> links.target_node_id
    ON CONFLICT (catalog_version_id, source_node_id, target_node_id, link_type)
    DO NOTHING;

    DELETE FROM public.catalog_node node
    USING maintenance_v25_catalog_duplicate_map duplicate
    WHERE duplicate.catalog_version_id = catalog.id
      AND node.catalog_version_id = catalog.id
      AND node.node_id = duplicate.duplicate_node_id;

    SELECT count(*)::integer
    INTO clean_node_count
    FROM public.catalog_node
    WHERE catalog_version_id = catalog.id;

    SELECT count(*)::integer
    INTO clean_link_count
    FROM public.catalog_link
    WHERE catalog_version_id = catalog.id;

    SELECT count(*)::integer
    INTO clean_material_count
    FROM public.catalog_node
    WHERE catalog_version_id = catalog.id
      AND node_type = 'MATERIAL';

    SELECT encode(
      sha256(convert_to(content.value::text, 'UTF8')),
      'hex')
    INTO content_sha256
    FROM (
      SELECT jsonb_build_object(
        'nodes',
        COALESCE(
          (
            SELECT jsonb_agg(node_json.value ORDER BY node_json.name, node_json.node_id)
            FROM (
              SELECT
                node.name,
                node.node_id,
                jsonb_build_object(
                  'id', node.node_id,
                  'nodeType', node.node_type,
                  'name', node.name,
                  'active', node.active,
                  'parentNodeId', node.parent_node_id,
                  'furnitureCategory', node.furniture_category,
                  'furnitureEquipment',
                    CASE
                      WHEN node.furniture_equipment_id IS NULL THEN NULL
                      ELSE jsonb_build_object(
                        'equipmentId', node.furniture_equipment_id,
                        'equipmentName', node.furniture_equipment_name)
                    END,
                  'unit', node.unit,
                  'unitPrice',
                    CASE
                      WHEN node.price_minor IS NULL THEN NULL
                      ELSE (node.price_minor::numeric / 100)::numeric(30, 2)::text
                    END,
                  'durationMinutes', node.duration_minutes,
                  'includeInEstimate', node.include_in_estimate,
                  'commonItem', node.common_item,
                  'showInMainMenu', node.show_in_main_menu,
                  'canvasX', node.canvas_x,
                  'canvasY', node.canvas_y,
                  'routing',
                    CASE
                      WHEN node.routing_queue_id IS NULL THEN NULL
                      ELSE jsonb_build_object(
                        'queueId', node.routing_queue_id,
                        'queueType', node.routing_queue_type)
                    END,
                  'comment', node.comment,
                  'displayColor', node.display_color,
                  'forcesCapitalRepair', node.forces_capital_repair,
                  'characteristicId', node.characteristic_id
                ) AS value
              FROM public.catalog_node node
              WHERE node.catalog_version_id = catalog.id
            ) node_json
          ),
          '[]'::jsonb
        ),
        'links',
        COALESCE(
          (
            SELECT jsonb_agg(link_json.value ORDER BY link_json.sort_order, link_json.link_id)
            FROM (
              SELECT
                link.sort_order,
                link.link_id,
                jsonb_build_object(
                  'id', link.link_id,
                  'fromNodeId', link.source_node_id,
                  'toNodeId', link.target_node_id,
                  'linkType', link.link_type,
                  'sourceAnchor', link.source_anchor,
                  'targetAnchor', link.target_anchor,
                  'sortOrder', link.sort_order
                ) AS value
              FROM public.catalog_link link
              WHERE link.catalog_version_id = catalog.id
            ) link_json
          ),
          '[]'::jsonb
        )
      ) AS value
    ) content;

    dependency_acyclic := COALESCE(
      (previous_report ->> 'dependencyAcyclic')::boolean,
      true);

    IF previous_report ? 'source' THEN
      pre_report := json_build_object(
        'valid', true,
        'errorCount', 0,
        'warningCount', 0,
        'contentSha256', content_sha256,
        'nodeCount', clean_node_count,
        'linkCount', clean_link_count,
        'materialCount', clean_material_count,
        'dependencyAcyclic', dependency_acyclic,
        'source', previous_report -> 'source'
      );
      report := jsonb_build_object(
        'valid', true,
        'errorCount', 0,
        'warningCount', 0,
        'contentSha256', content_sha256,
        'nodeCount', clean_node_count,
        'linkCount', clean_link_count,
        'materialCount', clean_material_count,
        'dependencyAcyclic', dependency_acyclic,
        'source', previous_report -> 'source'
      );
    ELSE
      pre_report := json_build_object(
        'valid', true,
        'errorCount', 0,
        'warningCount', 0,
        'contentSha256', content_sha256,
        'nodeCount', clean_node_count,
        'linkCount', clean_link_count,
        'materialCount', clean_material_count,
        'dependencyAcyclic', dependency_acyclic
      );
      report := jsonb_build_object(
        'valid', true,
        'errorCount', 0,
        'warningCount', 0,
        'contentSha256', content_sha256,
        'nodeCount', clean_node_count,
        'linkCount', clean_link_count,
        'materialCount', clean_material_count,
        'dependencyAcyclic', dependency_acyclic
      );
    END IF;

    report_sha256 := encode(
      sha256(convert_to((pre_report::jsonb)::text, 'UTF8')),
      'hex');
    report := report || jsonb_build_object('reportSha256', report_sha256);
    report_text := CASE
      WHEN previous_report ? 'source' THEN
        json_build_object(
          'valid', true,
          'errorCount', 0,
          'warningCount', 0,
          'contentSha256', content_sha256,
          'nodeCount', clean_node_count,
          'linkCount', clean_link_count,
          'materialCount', clean_material_count,
          'dependencyAcyclic', dependency_acyclic,
          'source', previous_report -> 'source',
          'reportSha256', report_sha256
        )::text
      ELSE
        json_build_object(
          'valid', true,
          'errorCount', 0,
          'warningCount', 0,
          'contentSha256', content_sha256,
          'nodeCount', clean_node_count,
          'linkCount', clean_link_count,
          'materialCount', clean_material_count,
          'dependencyAcyclic', dependency_acyclic,
          'reportSha256', report_sha256
        )::text
    END;
    validation_report_sha256 := encode(
      sha256(convert_to(report_text, 'UTF8')),
      'hex');

    UPDATE public.catalog_version
    SET version = catalog.version + 1,
        node_count = clean_node_count,
        link_count = clean_link_count,
        validation_report = report_text,
        updated_at = recorded_at
    WHERE id = catalog.id
      AND version = catalog.version;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'active catalog changed during duplicate cleanup: %', catalog.id;
    END IF;

    -- The previous snapshot already has the exact projection shape emitted by the service.
    -- Replace only mutable identity/count fields and rebuild links from the post-cleanup rows.
    next_state := jsonb_set(
      previous_state,
      '{version}',
      to_jsonb(catalog.version + 1),
      false);
    next_state := jsonb_set(
      next_state,
      '{nodeCount}',
      to_jsonb(clean_node_count),
      false);
    next_state := jsonb_set(
      next_state,
      '{linkCount}',
      to_jsonb(clean_link_count),
      false);
    next_state := jsonb_set(
      next_state,
      '{validationReport}',
      report,
      false);
    next_state := jsonb_set(
      next_state,
      '{updatedAt}',
      to_jsonb(recorded_text),
      false);
    next_state := jsonb_set(
      next_state,
      '{nodes}',
      COALESCE(
        (
          SELECT jsonb_agg(
            CASE
              WHEN parent_map.canonical_node_id IS NULL THEN node
              ELSE jsonb_set(
                node,
                '{parentNodeId}',
                to_jsonb(parent_map.canonical_node_id::text),
                true)
            END
            ORDER BY node ->> 'name', node ->> 'id')
          FROM jsonb_array_elements(previous_state -> 'nodes') node
          LEFT JOIN maintenance_v25_catalog_duplicate_map parent_map
            ON parent_map.catalog_version_id = catalog.id
           AND parent_map.duplicate_node_id = NULLIF(node ->> 'parentNodeId', '')::uuid
          WHERE NOT EXISTS (
            SELECT 1
            FROM maintenance_v25_catalog_duplicate_map duplicate
            WHERE duplicate.catalog_version_id = catalog.id
              AND duplicate.duplicate_node_id = NULLIF(node ->> 'id', '')::uuid)
        ),
        '[]'::jsonb),
      false);
    next_state := jsonb_set(
      next_state,
      '{links}',
      COALESCE(
        (
          SELECT jsonb_agg(
            jsonb_build_object(
              'id', link.link_id,
              'sourceNodeId', link.source_node_id,
              'targetNodeId', link.target_node_id,
              'linkType', link.link_type,
              'sourceAnchor', link.source_anchor,
              'targetAnchor', link.target_anchor,
              'sortOrder', link.sort_order)
            ORDER BY link.sort_order, link.link_id)
          FROM public.catalog_link link
          WHERE link.catalog_version_id = catalog.id
        ),
        '[]'::jsonb),
      false);

    event_id := gen_random_uuid();
    correlation_id := gen_random_uuid();
    local_payload := jsonb_build_object(
      'model', 'maintenance-full-state-v1',
      'event', jsonb_build_object(
        'catalogVersionId', catalog.id,
        'migration', 'catalog-duplicate-cleanup-v1'),
      'state', next_state);
    integration_payload := jsonb_build_object(
      'catalogVersionId', catalog.id,
      'warehouseId', catalog.warehouse_id,
      'lifecycle', catalog.state,
      'sourceSha256', catalog.source_sha256,
      'nodeCount', clean_node_count,
      'linkCount', clean_link_count,
      'validationReportSha256', validation_report_sha256);
    envelope := jsonb_build_object(
      'envelopeVersion', 2,
      'eventId', event_id,
      'eventType', 'maintenance.catalog-version.changed.v1',
      'eventVersion', 1,
      'occurredAt', to_jsonb(recorded_text),
      'recordedAt', to_jsonb(recorded_text),
      'producer', 'maintenance-service',
      'aggregateType', 'CATALOG_VERSION',
      'aggregateId', catalog.id::text,
      'aggregateVersion', catalog.version + 1,
      'correlation', jsonb_build_object(
        'correlationId', correlation_id,
        'causationId', NULL),
      'actorRef', NULL,
      'payload', integration_payload);
    event_sha256 := encode(
      sha256(convert_to(local_payload::text, 'UTF8')),
      'hex');
    state_sha256 := encode(
      sha256(convert_to(next_state::text, 'UTF8')),
      'hex');
    envelope_sha256 := encode(
      sha256(convert_to(envelope::text, 'UTF8')),
      'hex');

    UPDATE public.event_stream_head
    SET current_version = catalog.version + 1,
        last_event_id = event_id,
        updated_at = recorded_at
    WHERE aggregate_type = 'CATALOG_VERSION'
      AND aggregate_id = catalog.id::text
      AND current_version = catalog.current_version;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'catalog stream changed during duplicate cleanup: %', catalog.id;
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
      catalog.version + 1,
      'maintenance.catalog-version.changed.v1',
      1,
      recorded_at,
      recorded_at,
      correlation_id,
      NULL,
      NULL,
      local_payload,
      event_sha256,
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
      catalog.version + 1,
      next_state,
      state_sha256,
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
      catalog.version + 1,
      'maintenance.catalog-version.changed.v1',
      'rwms.maintenance.catalog-version.v1',
      envelope,
      envelope_sha256,
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
      catalog.version + 1,
      state_sha256,
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
    FROM public.catalog_version version
    JOIN public.event_stream_head head
      ON head.aggregate_type = 'CATALOG_VERSION'
     AND head.aggregate_id = version.id::text
    WHERE version.state = 'ACTIVE'
      AND version.version <> head.current_version
  ) THEN
    RAISE EXCEPTION
      'active catalog projection and event streams differ after duplicate cleanup';
  END IF;
END
$$;
