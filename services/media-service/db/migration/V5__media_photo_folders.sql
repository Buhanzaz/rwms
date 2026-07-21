-- Persist one logical photo folder for every media asset. Existing assets are
-- preserved and become one-item folders; new multi-file uploads may share the
-- same folder UUID.

alter table media_asset
    add column folder_id uuid;

update media_asset
set folder_id = media_id
where folder_id is null;

alter table media_asset
    alter column folder_id set not null;

create index media_asset_owner_folder_idx
    on media_asset (owner_type, owner_id, warehouse_id, folder_id, created_at)
    where deleted_at is null;

-- Keep the canonical aggregate snapshot complete after adding folder_id.
create or replace function media_full_state(p_media_id uuid)
returns jsonb language sql stable as $$
select jsonb_build_object(
    'schemaVersion', 1,
    'asset', jsonb_build_object(
        'mediaId', asset.media_id,
        'folderId', asset.folder_id,
        'ownerType', asset.owner_type,
        'ownerId', asset.owner_id,
        'warehouseId', asset.warehouse_id,
        'kind', asset.media_kind,
        'fileName', asset.original_file_name,
        'contentType', asset.original_content_type,
        'sourceObjectKey', asset.source_object_key,
        'sourceVersionId', asset.source_version_id,
        'sourceEtag', asset.source_etag,
        'sourceChecksumSha256', asset.source_checksum_sha256,
        'finalizedContentType', asset.finalized_content_type,
        'finalizedSizeBytes', asset.finalized_size_bytes,
        'status', asset.processing_status,
        'rotationDegrees', asset.rotation_degrees,
        'currentGeneration', asset.current_generation,
        'pendingGeneration', asset.pending_generation,
        'pendingRotationDegrees', asset.pending_rotation_degrees,
        'nextGeneration', asset.next_generation,
        'sortOrder', asset.sort_order,
        'sizeBytes', asset.size_bytes,
        'processingAttempts', asset.processing_attempts,
        'processingError', asset.processing_error,
        'version', asset.version,
        'createdAt', asset.created_at,
        'updatedAt', asset.updated_at,
        'deletedAt', asset.deleted_at
    ),
    'uploadSession', (
        select jsonb_build_object(
            'uploadSessionId', session.upload_session_id,
            'mediaId', session.media_id,
            'subjectId', session.subject_id,
            'idempotencyKey', session.idempotency_key,
            'expectedContentLength', session.expected_content_length,
            'expectedContentType', session.expected_content_type,
            'expectedChecksumSha256', session.expected_checksum_sha256,
            'expiresAt', session.expires_at,
            'completedAt', session.completed_at,
            'createdAt', session.created_at
        )
        from media_upload_session session where session.media_id=asset.media_id
    ),
    'processingJobs', coalesce((
        select jsonb_agg(jsonb_build_object(
            'processingJobId', job.processing_job_id,
            'mediaId', job.media_id,
            'generation', job.generation,
            'processingKind', job.processing_kind,
            'requestedRotationDegrees', job.requested_rotation_degrees,
            'status', case
                when asset.processing_status='PROCESSING'
                    and asset.pending_generation=job.generation then 'PENDING'
                else job.job_status
            end,
            'sourceVersionId', job.source_version_id,
            'sourceChecksumSha256', job.source_checksum_sha256,
            'createdAt', job.created_at,
            'completedAt', job.completed_at
        ) order by job.generation,job.processing_job_id)
        from media_processing_job job where job.media_id=asset.media_id
    ), '[]'::jsonb),
    'variants', coalesce((
        select jsonb_agg(jsonb_build_object(
            'mediaId', variant.media_id,
            'generation', variant.generation,
            'variant', variant.variant,
            'objectKey', variant.object_key,
            'objectVersionId', variant.object_version_id,
            'contentType', variant.content_type,
            'sizeBytes', variant.size_bytes,
            'width', variant.width,
            'height', variant.height,
            'checksumSha256', variant.checksum_sha256,
            'createdAt', variant.created_at
        ) order by variant.generation,variant.variant)
        from media_variant variant where variant.media_id=asset.media_id
    ), '[]'::jsonb)
)
from media_asset asset
where asset.media_id=p_media_id
$$;
