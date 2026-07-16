DO $rwms$
DECLARE
    invalid_value text;
    collision_user uuid;
BEGIN
    SELECT warehouse_id INTO invalid_value
    FROM (
        SELECT warehouse_id FROM public.user_warehouse_access
        UNION ALL
        SELECT warehouse_id FROM public.auth_subject WHERE warehouse_id IS NOT NULL
    ) identifiers
    WHERE warehouse_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR warehouse_id IS DISTINCT FROM warehouse_id::uuid::text
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'Warehouse identifier is not canonical after V0002: %', invalid_value;
    END IF;

    SELECT user_id INTO collision_user
    FROM public.user_warehouse_access
    GROUP BY user_id, warehouse_id
    HAVING count(*) > 1
    LIMIT 1;
    IF collision_user IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate canonical warehouse grants remain for user %', collision_user;
    END IF;
END
$rwms$;
