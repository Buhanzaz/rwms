ALTER TABLE public.rental_inquiry_outbox
    ADD COLUMN payload_sha256 char(64),
    ADD COLUMN lease_owner varchar(160),
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN last_error_code varchar(64),
    ADD COLUMN recovery_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN recovered_by_subject_id uuid,
    ADD COLUMN recovery_reason varchar(2000),
    ADD COLUMN recovered_at timestamptz;

UPDATE public.rental_inquiry_outbox
   SET payload_sha256 = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex');

ALTER TABLE public.rental_inquiry_outbox
    ALTER COLUMN payload_sha256 SET NOT NULL,
    DROP CONSTRAINT ck_rental_inquiry_outbox_status,
    ADD CONSTRAINT ck_rental_inquiry_outbox_status CHECK (
        status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'QUARANTINED')),
    ADD CONSTRAINT ck_rental_inquiry_outbox_hash CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_rental_inquiry_outbox_delivery_state CHECK (
        (status = 'PENDING' AND published_at IS NULL
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (status = 'IN_FLIGHT' AND published_at IS NULL
            AND lease_owner IS NOT NULL
            AND length(btrim(lease_owner)) BETWEEN 1 AND 160
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (status = 'QUARANTINED' AND published_at IS NULL
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND last_error_code IS NOT NULL
            AND length(btrim(last_error_code)) BETWEEN 1 AND 64)),
    ADD CONSTRAINT ck_rental_inquiry_outbox_recovery CHECK (
        recovery_version >= 0
        AND ((recovery_version = 0 AND recovered_by_subject_id IS NULL
                AND recovery_reason IS NULL AND recovered_at IS NULL)
            OR (recovery_version > 0 AND recovered_by_subject_id IS NOT NULL
                AND recovery_reason IS NOT NULL
                AND length(btrim(recovery_reason)) BETWEEN 1 AND 2000
                AND recovered_at IS NOT NULL)));

CREATE INDEX idx_rental_inquiry_outbox_lease_due
    ON public.rental_inquiry_outbox (status, next_attempt_at, lease_until, created_at, event_id);

CREATE TABLE public.rental_inquiry_outbox_recovery_audit (
    event_id uuid NOT NULL,
    expected_recovery_version bigint NOT NULL,
    recovery_version bigint NOT NULL,
    prior_attempt_count integer NOT NULL,
    prior_last_error_code varchar(64) NOT NULL,
    reviewed_by_subject_id uuid NOT NULL,
    reason varchar(2000) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    CONSTRAINT rental_inquiry_outbox_recovery_audit_pkey
        PRIMARY KEY (event_id, expected_recovery_version),
    CONSTRAINT uq_rental_inquiry_outbox_recovery_version
        UNIQUE (event_id, recovery_version),
    CONSTRAINT ck_rental_inquiry_outbox_recovery_versions CHECK (
        expected_recovery_version >= 0
        AND recovery_version = expected_recovery_version + 1),
    CONSTRAINT ck_rental_inquiry_outbox_recovery_attempts CHECK (prior_attempt_count >= 1),
    CONSTRAINT ck_rental_inquiry_outbox_recovery_error CHECK (
        length(btrim(prior_last_error_code)) BETWEEN 1 AND 64),
    CONSTRAINT ck_rental_inquiry_outbox_recovery_reason CHECK (
        length(btrim(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_rental_inquiry_outbox_recovery_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION public.reject_rental_inquiry_outbox_recovery_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'rental inquiry outbox recovery audit is immutable';
END;
$$;

CREATE TRIGGER trg_rental_inquiry_outbox_recovery_audit_immutable
BEFORE UPDATE OR DELETE ON public.rental_inquiry_outbox_recovery_audit
FOR EACH ROW EXECUTE FUNCTION public.reject_rental_inquiry_outbox_recovery_audit_mutation();

CREATE TRIGGER trg_rental_inquiry_outbox_recovery_audit_no_truncate
BEFORE TRUNCATE ON public.rental_inquiry_outbox_recovery_audit
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_rental_inquiry_outbox_recovery_audit_mutation();
