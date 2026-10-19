-- Repair current finding revisions whose non-media update failed to carry immutable photo evidence.
WITH repairable_findings AS (
  SELECT finding.id AS finding_id,
         finding.finding_revision AS target_revision,
         (
           SELECT reference.finding_revision
             FROM public.finding_media_reference reference
            WHERE reference.finding_id = finding.id
              AND reference.finding_revision < finding.finding_revision
              AND reference.media_id = finding.cover_media_id
            GROUP BY reference.finding_revision
            ORDER BY reference.finding_revision DESC
            LIMIT 1
         ) AS source_revision
    FROM public.inventory_finding finding
   WHERE finding.cover_media_id IS NOT NULL
     AND NOT EXISTS (
       SELECT 1
         FROM public.finding_media_reference current_reference
        WHERE current_reference.finding_id = finding.id
          AND current_reference.finding_revision = finding.finding_revision
     )
)
INSERT INTO public.finding_media_reference(
  finding_id,
  finding_revision,
  media_id,
  generation,
  media_kind,
  media_status,
  attached_at)
SELECT repair.finding_id,
       repair.target_revision,
       source.media_id,
       source.generation,
       source.media_kind,
       source.media_status,
       source.attached_at
  FROM repairable_findings repair
  JOIN public.finding_media_reference source
    ON source.finding_id = repair.finding_id
   AND source.finding_revision = repair.source_revision
 WHERE repair.source_revision IS NOT NULL
ON CONFLICT (finding_id, finding_revision, media_id, generation) DO NOTHING;
