-- Assistant tool results are replayed to the LLM and retained as conversation
-- context. Warehouse identity is UUID-only, so remove the legacy display /
-- business code from historical cabin-facet results without touching Problem
-- Details-style failure codes.

UPDATE public.assistant_tool_call AS tool_call
SET result_payload =
    jsonb_set(
      tool_call.result_payload,
      '{data,warehouses}',
      (
        SELECT jsonb_agg(warehouse.value - 'code' ORDER BY warehouse.ordinality)
        FROM jsonb_array_elements(tool_call.result_payload #> '{data,warehouses}')
          WITH ORDINALITY AS warehouse(value, ordinality)
      ),
      false)
WHERE tool_call.tool_name = 'list_available_cabin_facets'
  AND tool_call.result_payload ->> 'tool' = 'list_available_cabin_facets'
  AND jsonb_typeof(tool_call.result_payload #> '{data,warehouses}') = 'array'
  AND EXISTS (
    SELECT 1
    FROM jsonb_array_elements(tool_call.result_payload #> '{data,warehouses}')
      AS warehouse(value)
    WHERE warehouse.value ? 'code');
