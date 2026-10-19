LOCK TABLE public.auth_subject IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.user_warehouse_access IN SHARE ROW EXCLUSIVE MODE;

DO $rwms$
DECLARE
    invalid_value text;
    collision_user uuid;
    collision_target text;
BEGIN
    SELECT warehouse_id INTO invalid_value
    FROM (
        SELECT warehouse_id FROM public.user_warehouse_access
        UNION ALL
        SELECT warehouse_id FROM public.auth_subject WHERE warehouse_id IS NOT NULL
    ) identifiers
    WHERE lower(warehouse_id) NOT IN ('spb', 'msk')
      AND warehouse_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'Unmapped non-UUID warehouse identifier: %', invalid_value;
    END IF;

    WITH normalized AS (
        SELECT
            user_id,
            CASE lower(warehouse_id)
                WHEN 'spb' THEN '00000000-0000-0000-0000-000000000001'
                WHEN 'msk' THEN '00000000-0000-0000-0000-000000000002'
                ELSE warehouse_id::uuid::text
            END AS canonical_id
        FROM public.user_warehouse_access
    )
    SELECT user_id, canonical_id INTO collision_user, collision_target
    FROM normalized
    GROUP BY user_id, canonical_id
    HAVING count(*) > 1
    LIMIT 1;
    IF collision_user IS NOT NULL THEN
        RAISE EXCEPTION 'Warehouse identifier collision for user % and warehouse %',
            collision_user, collision_target;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_warehouse_access
        WHERE version = 2147483647
          AND warehouse_id IS DISTINCT FROM CASE lower(warehouse_id)
              WHEN 'spb' THEN '00000000-0000-0000-0000-000000000001'
              WHEN 'msk' THEN '00000000-0000-0000-0000-000000000002'
              ELSE warehouse_id::uuid::text
          END
    ) OR EXISTS (
        SELECT 1
        FROM public.auth_subject
        WHERE warehouse_id IS NOT NULL
          AND version = 2147483647
          AND warehouse_id IS DISTINCT FROM CASE lower(warehouse_id)
              WHEN 'spb' THEN '00000000-0000-0000-0000-000000000001'
              WHEN 'msk' THEN '00000000-0000-0000-0000-000000000002'
              ELSE warehouse_id::uuid::text
          END
    ) THEN
        RAISE EXCEPTION 'Warehouse identifier canonicalization would overflow an optimistic version';
    END IF;
END
$rwms$;

WITH normalized AS (
    SELECT
        id,
        CASE lower(warehouse_id)
            WHEN 'spb' THEN '00000000-0000-0000-0000-000000000001'
            WHEN 'msk' THEN '00000000-0000-0000-0000-000000000002'
            ELSE warehouse_id::uuid::text
        END AS canonical_id
    FROM public.user_warehouse_access
)
UPDATE public.user_warehouse_access access
SET warehouse_id = normalized.canonical_id,
    version = access.version + 1,
    updated_at = clock_timestamp()
FROM normalized
WHERE access.id = normalized.id
  AND access.warehouse_id IS DISTINCT FROM normalized.canonical_id;

WITH normalized AS (
    SELECT
        id,
        CASE lower(warehouse_id)
            WHEN 'spb' THEN '00000000-0000-0000-0000-000000000001'
            WHEN 'msk' THEN '00000000-0000-0000-0000-000000000002'
            ELSE warehouse_id::uuid::text
        END AS canonical_id
    FROM public.auth_subject
    WHERE warehouse_id IS NOT NULL
)
UPDATE public.auth_subject subject
SET warehouse_id = normalized.canonical_id,
    version = subject.version + 1,
    updated_at = clock_timestamp()
FROM normalized
WHERE subject.id = normalized.id
  AND subject.warehouse_id IS DISTINCT FROM normalized.canonical_id;
