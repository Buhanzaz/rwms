package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable import review and commit saga. Raw HTML exists only for the duration
 * of {@link #create}; all later work uses bounded normalized parser output.
 *
 * <p>This façade preserves the public transaction and controller/media-coordinator seam while
 * dedicated collaborators own intake projection, planning, durable commit recovery, and media
 * recovery.
 */
@Service
public class RentalItemHtmlImportService {
  private final RentalItemHtmlImportProjectionService projections;
  private final RentalItemHtmlImportPlanService plans;
  private final RentalItemHtmlImportCommitService commits;
  private final RentalItemHtmlImportMediaService media;

  public RentalItemHtmlImportService(
      RentalItemHtmlImportProjectionService projections,
      RentalItemHtmlImportPlanService plans,
      RentalItemHtmlImportCommitService commits,
      RentalItemHtmlImportMediaService media) {
    this.projections = projections;
    this.plans = plans;
    this.commits = commits;
    this.media = media;
  }

  @Transactional
  public HtmlImportDetailResponse create(
      UUID actorSubjectId, UUID idempotencyKey, UUID warehouseId, byte[] html) {
    return projections.create(actorSubjectId, idempotencyKey, warehouseId, html);
  }

  @Transactional(readOnly = true)
  public List<HtmlImportSummaryResponse> list(UUID warehouseId) {
    return projections.list(warehouseId);
  }

  @Transactional(readOnly = true)
  public HtmlImportDetailResponse get(UUID id) {
    return projections.get(id);
  }

  @Transactional(readOnly = true)
  public UUID warehouseId(UUID id) {
    return projections.warehouseId(id);
  }

  @Transactional(readOnly = true)
  public HtmlImportRowPage rowPage(UUID id, int page, int size) {
    return projections.rowPage(id, page, size);
  }

  @Transactional
  public HtmlImportDetailResponse updatePlan(UUID id, UpdateHtmlImportPlanRequest request) {
    return projections.detail(plans.updatePlan(id, request));
  }

  @Transactional
  public void cancel(UUID id, long expectedVersion) {
    plans.cancel(id, expectedVersion);
  }

  public HtmlImportDetailResponse commit(
      UUID actorSubjectId, UUID idempotencyKey, UUID id, CommitHtmlImportRequest request) {
    return commits.commit(actorSubjectId, idempotencyKey, id, request);
  }

  public HtmlImportDetailResponse retryMedia(
      UUID actorSubjectId, UUID idempotencyKey, UUID id, RetryHtmlImportMediaRequest request) {
    return media.retryMedia(actorSubjectId, idempotencyKey, id, request);
  }

  public HtmlImportDetailResponse replaceMedia(
      UUID actorSubjectId, UUID idempotencyKey, UUID id, ReplaceHtmlImportMediaRequest request) {
    return media.replaceMedia(actorSubjectId, idempotencyKey, id, request);
  }

  @Transactional
  public HtmlImportDetailResponse skipMedia(
      UUID actorSubjectId, UUID idempotencyKey, UUID id, SkipHtmlImportMediaRequest request) {
    return media.skipMedia(actorSubjectId, idempotencyKey, id, request);
  }

  public void synchronizeMedia(UUID id) {
    media.synchronizeMedia(id);
  }
}
