-- Initial owner truth always starts the media aggregate stream at version zero, independently
-- from the owning maintenance aggregate's current source version. Replace the rejected legacy
-- event identity with the exact UUIDv3 produced by UUID.nameUUIDFromBytes for the corrected body.
-- Delivery/review state remains unchanged, so quarantined work still requires reviewed resume.
WITH incorrect_initial_proof AS (
  SELECT
    id,
    set_byte(
      set_byte(
        decode(md5(
          'maintenance-media-proof:' || media_owner_type || ':' || media_owner_id || ':0:0'),
          'hex'),
        6,
        (get_byte(decode(md5(
          'maintenance-media-proof:' || media_owner_type || ':' || media_owner_id || ':0:0'),
          'hex'), 6) & 15) | 48),
      8,
      (get_byte(decode(md5(
        'maintenance-media-proof:' || media_owner_type || ':' || media_owner_id || ':0:0'),
        'hex'), 8) & 63) | 128) AS proof_uuid_bytes
  FROM public.integration_reconciliation
  WHERE dependency_type = 'MEDIA'
    AND operation_type = 'UPSERT_MEDIA_OWNER_PROOF'
    AND media_owner_revision = 0
    AND media_aggregate_version <> 0
), corrected_initial_proof AS (
  SELECT
    id,
    (
      substr(encode(proof_uuid_bytes, 'hex'), 1, 8) || '-' ||
      substr(encode(proof_uuid_bytes, 'hex'), 9, 4) || '-' ||
      substr(encode(proof_uuid_bytes, 'hex'), 13, 4) || '-' ||
      substr(encode(proof_uuid_bytes, 'hex'), 17, 4) || '-' ||
      substr(encode(proof_uuid_bytes, 'hex'), 21, 12)
    )::uuid AS proof_event_id
  FROM incorrect_initial_proof
)
UPDATE public.integration_reconciliation reconciliation
SET
  media_aggregate_version = 0,
  media_proof_event_id = corrected.proof_event_id,
  idempotency_key = corrected.proof_event_id,
  response_snapshot = jsonb_set(
    jsonb_set(
      response_snapshot,
      '{aggregateVersion}',
      to_jsonb(0::bigint),
      true),
    '{proofEventId}',
    to_jsonb(corrected.proof_event_id),
    true)
FROM corrected_initial_proof corrected
WHERE reconciliation.id = corrected.id;
