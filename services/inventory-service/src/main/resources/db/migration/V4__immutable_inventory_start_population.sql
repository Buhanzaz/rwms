-- V3 treated the live warehouse population as the inventory baseline. Keep the
-- capture made at session start immutable and retain live arrivals as findings
-- outside that expected population.

WITH captured_population AS (
  SELECT DISTINCT ON (result.operation_id)
    result.operation_id,
    result.total_count
  FROM public.inventory_start_capture_result result
  WHERE result.outcome = 'CAPTURED'
  ORDER BY result.operation_id, result.technical_attempt DESC
),
legacy_arrivals AS (
  SELECT finding.id
  FROM public.inventory_session session
  JOIN captured_population capture
    ON capture.operation_id = session.start_operation_id
  JOIN public.inventory_expected_item expected
    ON expected.inventory_id = session.id
   AND expected.item_order >= capture.total_count
  JOIN public.inventory_finding finding
    ON finding.inventory_id = expected.inventory_id
   AND finding.id = expected.finding_id
  WHERE session.lifecycle = 'ACTIVE'
)
UPDATE public.inventory_finding finding
SET origin = 'UNEXPECTED_EXISTING',
    expected_item_id = NULL
FROM legacy_arrivals arrival
WHERE finding.id = arrival.id;

WITH captured_population AS (
  SELECT DISTINCT ON (result.operation_id)
    result.operation_id,
    result.total_count
  FROM public.inventory_start_capture_result result
  WHERE result.outcome = 'CAPTURED'
  ORDER BY result.operation_id, result.technical_attempt DESC
)
DELETE FROM public.inventory_expected_item expected
USING public.inventory_session session, captured_population capture
WHERE session.lifecycle = 'ACTIVE'
  AND capture.operation_id = session.start_operation_id
  AND expected.inventory_id = session.id
  AND expected.item_order >= capture.total_count;

WITH captured_population AS (
  SELECT DISTINCT ON (result.operation_id)
    result.operation_id,
    result.total_count
  FROM public.inventory_start_capture_result result
  WHERE result.outcome = 'CAPTURED'
  ORDER BY result.operation_id, result.technical_attempt DESC
)
UPDATE public.inventory_session session
SET expected_population_count = capture.total_count
FROM captured_population capture
WHERE session.lifecycle = 'ACTIVE'
  AND capture.operation_id = session.start_operation_id
  AND session.expected_population_count <> capture.total_count;
