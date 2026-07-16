\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned

CREATE TEMPORARY TABLE rwms_exact_row_counts (
    schema_name text NOT NULL,
    table_name text NOT NULL,
    row_count bigint NOT NULL,
    PRIMARY KEY (schema_name, table_name)
);

DO $inventory$
DECLARE
    relation record;
BEGIN
    FOR relation IN
        SELECT namespace.nspname AS schema_name, class.relname AS table_name
        FROM pg_catalog.pg_class AS class
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = class.relnamespace
        WHERE class.relkind IN ('r', 'p')
          AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
          AND namespace.nspname !~ '^pg_toast'
          AND namespace.nspname !~ '^pg_temp'
        ORDER BY namespace.nspname, class.relname
    LOOP
        EXECUTE format(
            'INSERT INTO rwms_exact_row_counts(schema_name, table_name, row_count) SELECT %L, %L, count(*) FROM %I.%I',
            relation.schema_name,
            relation.table_name,
            relation.schema_name,
            relation.table_name
        );
    END LOOP;
END
$inventory$;

SELECT jsonb_build_object(
    'formatVersion', 1,
    'tables', COALESCE((
        SELECT jsonb_agg(to_jsonb(item) ORDER BY item.schema_name, item.table_name)
        FROM (
            SELECT namespace.nspname AS schema_name,
                   class.relname AS table_name,
                   class.relkind::text AS relation_kind
            FROM pg_catalog.pg_class AS class
            JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = class.relnamespace
            WHERE class.relkind IN ('r', 'p')
              AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
              AND namespace.nspname !~ '^pg_toast'
              AND namespace.nspname !~ '^pg_temp'
        ) AS item
    ), '[]'::jsonb),
    'columns', COALESCE((
        SELECT jsonb_agg(to_jsonb(item) ORDER BY item.schema_name, item.table_name, item.ordinal_position)
        FROM (
            SELECT namespace.nspname AS schema_name,
                   class.relname AS table_name,
                   attribute.attnum AS ordinal_position,
                   attribute.attname AS column_name,
                   pg_catalog.format_type(attribute.atttypid, attribute.atttypmod) AS data_type,
                   NOT attribute.attnotnull AS nullable,
                   pg_catalog.pg_get_expr(default_value.adbin, default_value.adrelid) AS default_expression
            FROM pg_catalog.pg_attribute AS attribute
            JOIN pg_catalog.pg_class AS class ON class.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = class.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef AS default_value
                ON default_value.adrelid = attribute.attrelid
               AND default_value.adnum = attribute.attnum
            WHERE attribute.attnum > 0
              AND NOT attribute.attisdropped
              AND class.relkind IN ('r', 'p')
              AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
              AND namespace.nspname !~ '^pg_toast'
              AND namespace.nspname !~ '^pg_temp'
        ) AS item
    ), '[]'::jsonb),
    'indexes', COALESCE((
        SELECT jsonb_agg(to_jsonb(item) ORDER BY item.schema_name, item.table_name, item.index_name)
        FROM (
            SELECT namespace.nspname AS schema_name,
                   table_class.relname AS table_name,
                   index_class.relname AS index_name,
                   index_definition.indisunique AS unique_index,
                   index_definition.indisprimary AS primary_index,
                   pg_catalog.pg_get_indexdef(index_definition.indexrelid) AS definition
            FROM pg_catalog.pg_index AS index_definition
            JOIN pg_catalog.pg_class AS table_class ON table_class.oid = index_definition.indrelid
            JOIN pg_catalog.pg_class AS index_class ON index_class.oid = index_definition.indexrelid
            JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = table_class.relnamespace
            WHERE namespace.nspname NOT IN ('pg_catalog', 'information_schema')
              AND namespace.nspname !~ '^pg_toast'
              AND namespace.nspname !~ '^pg_temp'
        ) AS item
    ), '[]'::jsonb),
    'constraints', COALESCE((
        SELECT jsonb_agg(to_jsonb(item) ORDER BY item.schema_name, item.table_name, item.constraint_name)
        FROM (
            SELECT namespace.nspname AS schema_name,
                   class.relname AS table_name,
                   constraint_definition.conname AS constraint_name,
                   constraint_definition.contype::text AS constraint_type,
                   pg_catalog.pg_get_constraintdef(constraint_definition.oid, true) AS definition
            FROM pg_catalog.pg_constraint AS constraint_definition
            JOIN pg_catalog.pg_class AS class ON class.oid = constraint_definition.conrelid
            JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = class.relnamespace
            WHERE namespace.nspname NOT IN ('pg_catalog', 'information_schema')
              AND namespace.nspname !~ '^pg_toast'
              AND namespace.nspname !~ '^pg_temp'
        ) AS item
    ), '[]'::jsonb),
    'rowCounts', COALESCE((
        SELECT jsonb_agg(to_jsonb(item) ORDER BY item.schema_name, item.table_name)
        FROM rwms_exact_row_counts AS item
    ), '[]'::jsonb)
)::text;
