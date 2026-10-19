-- WorkerApp may reserve one logical WebP client bundle while legacy WorkerApp
-- and DriverApp releases continue to reserve their original JPEG evidence.
ALTER TABLE public.worker_task_evidence
  ADD CONSTRAINT ck_worker_task_evidence_upload_v2 CHECK (
    (
      (
        content_type = 'image/jpeg'
        AND size_bytes BETWEEN 1 AND 15728640
      )
      OR (
        content_type = 'image/webp'
        AND size_bytes BETWEEN 1 AND 1048576
      )
    )
    AND sha256 ~ '^[0-9a-f]{64}$'
  ) NOT VALID;

ALTER TABLE public.worker_task_evidence
  VALIDATE CONSTRAINT ck_worker_task_evidence_upload_v2;

ALTER TABLE public.worker_task_evidence
  DROP CONSTRAINT ck_worker_task_evidence_upload;

ALTER TABLE public.worker_task_evidence
  RENAME CONSTRAINT ck_worker_task_evidence_upload_v2
  TO ck_worker_task_evidence_upload;
