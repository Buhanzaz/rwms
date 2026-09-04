package dev.buhanzaz.rwms.logistics.retention;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlacePlanningRetentionLegalHoldRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningArchiveManifestResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRetentionCandidateCount;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRetentionDryRunResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRetentionLegalHoldResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.RecordPlanningArchiveManifestRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReleasePlanningRetentionLegalHoldRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.VerifyPlanningArchiveManifestRequest;

import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsArchiveManifest;
import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionDataset;
import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionLegalHold;
import dev.buhanzaz.rwms.logistics.retention.repository.LogisticsArchiveManifestRepository;
import dev.buhanzaz.rwms.logistics.retention.repository.LogisticsRetentionLegalHoldRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-destructive retention foundation. It reports eligible terminal rows, persists legal holds,
 * and records private archive checksums; no method deletes an online business or transport row.
 */
@Service
@RequiredArgsConstructor
public class LogisticsRetentionService {
  private final LogisticsRetentionProperties properties;
  private final LogisticsRetentionLegalHoldRepository legalHolds;
  private final LogisticsArchiveManifestRepository manifests;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** Counts terminal event transport rows older than the approved online period. */
  @Transactional(readOnly = true)
  public PlanningRetentionDryRunResponse dryRun() {
    OffsetDateTime generatedAt = now();
    OffsetDateTime cutoff = generatedAt.minus(properties.getEventOnline());
    long outboxRows =
        count(
                "select count(*) from outbox_event where status = 'PUBLISHED' and published_at < ?",
                cutoff)
            + count(
                "select count(*) from rental_inquiry_outbox where status = 'PUBLISHED' and"
                    + " published_at < ?",
                cutoff)
            + count(
                "select count(*) from warehouse_operation_mark_outbox where state = 'CONFIRMED' and"
                    + " updated_at < ?",
                cutoff);
    long inboxRows =
        count(
            "select count(*) from inbox_message where status = 'PROCESSED' and processed_at < ?",
            cutoff);
    return new PlanningRetentionDryRunResponse(
        generatedAt,
        properties.getBusinessAuditProof().toDays(),
        properties.getEventOnline().toDays(),
        properties.getEventArchive().toDays(),
        properties.getGpsTelemetry().toDays(),
        properties.isDeletionEnabled(),
        true,
        List.of(
            new PlanningRetentionCandidateCount(
                LogisticsRetentionDataset.EVENT_OUTBOX,
                cutoff,
                outboxRows,
                legalHolds.countByDatasetAndReleasedAtIsNull(
                    LogisticsRetentionDataset.EVENT_OUTBOX)),
            new PlanningRetentionCandidateCount(
                LogisticsRetentionDataset.EVENT_INBOX,
                cutoff,
                inboxRows,
                legalHolds.countByDatasetAndReleasedAtIsNull(
                    LogisticsRetentionDataset.EVENT_INBOX))));
  }

  /** Places and returns one durable hold without affecting data or archive objects. */
  @Transactional
  public PlanningRetentionLegalHoldResponse place(
      PlacePlanningRetentionLegalHoldRequest request, UUID authenticatedSubjectId) {
    return response(
        legalHolds.saveAndFlush(
            LogisticsRetentionLegalHold.place(
                request.dataset(), request.scopeKey(), request.reason(), authenticatedSubjectId)));
  }

  /** Releases one hold under its version fence; the row and original reason remain immutable. */
  @Transactional
  public PlanningRetentionLegalHoldResponse release(
      UUID holdId, ReleasePlanningRetentionLegalHoldRequest request, UUID authenticatedSubjectId) {
    LogisticsRetentionLegalHold hold =
        legalHolds.findForUpdate(holdId).orElseThrow(LogisticsNotFoundException::new);
    try {
      hold.release(request.expectedVersion(), authenticatedSubjectId, request.reason());
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException("Юридическая блокировка уже изменилась");
    }
    return response(legalHolds.saveAndFlush(hold));
  }

  /** Lists active legal holds used by every future deletion-approval guard. */
  @Transactional(readOnly = true)
  public List<PlanningRetentionLegalHoldResponse> activeHolds() {
    return legalHolds.findAllByReleasedAtIsNullOrderByPlacedAtAscIdAsc().stream()
        .map(LogisticsRetentionService::response)
        .toList();
  }

  /** Records only a manifest for an archive object that an external archival job already wrote. */
  @Transactional
  public PlanningArchiveManifestResponse record(
      RecordPlanningArchiveManifestRequest request, UUID authenticatedSubjectId) {
    return response(
        manifests.saveAndFlush(
            LogisticsArchiveManifest.record(
                request.dataset(),
                request.periodStart(),
                request.periodEnd(),
                request.objectKey(),
                request.sha256(),
                request.rowCount(),
                authenticatedSubjectId)));
  }

  /** Marks one checksum manifest verified; it still does not authorize or execute deletion. */
  @Transactional
  public PlanningArchiveManifestResponse verify(
      UUID manifestId, VerifyPlanningArchiveManifestRequest request, UUID authenticatedSubjectId) {
    LogisticsArchiveManifest manifest =
        manifests.findForUpdate(manifestId).orElseThrow(LogisticsNotFoundException::new);
    try {
      manifest.verify(request.expectedVersion(), authenticatedSubjectId);
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException("Манифест архива уже изменился");
    }
    return response(manifests.saveAndFlush(manifest));
  }

  private long count(String sql, OffsetDateTime cutoff) {
    Long value = jdbc.queryForObject(sql, Long.class, cutoff);
    return value == null ? 0 : value;
  }

  private static PlanningRetentionLegalHoldResponse response(LogisticsRetentionLegalHold hold) {
    return new PlanningRetentionLegalHoldResponse(
        hold.getId(),
        hold.getVersion(),
        hold.getDataset(),
        hold.getScopeKey(),
        hold.getReason(),
        hold.getPlacedBySubjectId(),
        hold.getPlacedAt(),
        hold.getReleasedBySubjectId(),
        hold.getReleasedAt(),
        hold.getReleaseReason());
  }

  private static PlanningArchiveManifestResponse response(LogisticsArchiveManifest manifest) {
    return new PlanningArchiveManifestResponse(
        manifest.getId(),
        manifest.getVersion(),
        manifest.getDataset(),
        manifest.getPeriodStart(),
        manifest.getPeriodEnd(),
        manifest.getSha256(),
        manifest.getRowCount(),
        manifest.getState().name(),
        manifest.getCreatedBySubjectId(),
        manifest.getCreatedAt(),
        manifest.getVerifiedBySubjectId(),
        manifest.getVerifiedAt());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }
}
