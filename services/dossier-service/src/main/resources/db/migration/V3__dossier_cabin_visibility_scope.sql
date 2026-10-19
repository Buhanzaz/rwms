-- Keep sanitized failure audit and Kafka relay state independent from the
-- cabin/generation coverage that can make a public dossier read partial.

alter table public.dossier_sanitized_dead_letter
    add column coverage_generation_id uuid,
    add column coverage_subject_cabin_id uuid,
    add column coverage_resolved_at timestamptz;

-- A legacy processing failure is scoped only when the active generation has
-- one exact unlinked source event with an explicit cabin. Ambiguous, global,
-- off-generation and raw validation failures deliberately remain unscoped.
update public.dossier_sanitized_dead_letter as failure
set coverage_generation_id = evidence.generation_id,
    coverage_subject_cabin_id = evidence.subject_cabin_id,
    coverage_resolved_at = evidence.resolved_at
from public.dossier_unlinked_fact as evidence
join public.dossier_active_generation as active
  on active.pointer_name = 'DOSSIER'
 and active.generation_id = evidence.generation_id
where failure.source_event_id = evidence.source_event_id
  and failure.failure_code in (
    'EVENT_IDENTITY_CONFLICT',
    'MEDIA_GENERATION_CONFLICT',
    'PROCESSING_FAILED'
  )
  and evidence.subject_cabin_id is not null;

alter table public.dossier_sanitized_dead_letter
    add constraint fk_dossier_dead_letter_coverage_generation
        foreign key (coverage_generation_id)
        references public.dossier_projection_generation (id),
    add constraint ck_dossier_dead_letter_coverage_scope check (
        (coverage_generation_id is null
            and coverage_subject_cabin_id is null
            and coverage_resolved_at is null)
        or (coverage_generation_id is not null
            and coverage_subject_cabin_id is not null)
    );

create index idx_dossier_dead_letter_unresolved_coverage
    on public.dossier_sanitized_dead_letter (
        coverage_generation_id,
        coverage_subject_cabin_id
    )
    where coverage_resolved_at is null;
