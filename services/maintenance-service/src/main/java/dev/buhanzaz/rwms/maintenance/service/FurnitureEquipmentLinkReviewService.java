package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels.*;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PageResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Bounded administrator review boundary; all reviews are version-fenced and audited. */
@Service
public class FurnitureEquipmentLinkReviewService {
  private final FurnitureEquipmentLinkStore links;

  public FurnitureEquipmentLinkReviewService(FurnitureEquipmentLinkStore links) {
    this.links = links;
  }

  public PageResponse<FurnitureEquipmentLinkResponse> list(
      UUID warehouseId, FurnitureEquipmentLinkState state, int page, int size) {
    FurnitureEquipmentLinkStore.LinkPage result =
        links.list(warehouseId, state == null ? null : state.name(), page, size);
    return new PageResponse<>(
        result.items().stream().map(FurnitureEquipmentLinkReviewService::response).toList(),
        result.page(),
        result.size(),
        result.total());
  }

  public ReviewedLink review(
      UUID warehouseId,
      UUID nodeId,
      long expectedReviewVersion,
      FurnitureEquipmentLinkReviewAction action,
      UUID reviewSubjectId,
      String reason) {
    FurnitureEquipmentLinkStore.ReviewResult result = links.review(
        warehouseId,
        nodeId,
        expectedReviewVersion,
        FurnitureEquipmentLinkStore.ReviewAction.valueOf(action.name()),
        reviewSubjectId,
        reason);
    return new ReviewedLink(response(result.snapshot()), result.replayed());
  }

  private static FurnitureEquipmentLinkResponse response(
      FurnitureEquipmentLinkStore.LinkSnapshot value) {
    return new FurnitureEquipmentLinkResponse(
        value.nodeId(),
        value.warehouseId(),
        value.sourceCatalogVersionId(),
        value.sourceCatalogExpectedVersion(),
        value.requestedName(),
        FurnitureEquipmentLinkState.valueOf(value.state()),
        value.equipmentId(),
        value.equipmentName(),
        value.observedEquipmentId(),
        value.observedEquipmentName(),
        value.attemptCount(),
        value.nextAttemptAt(),
        value.claimUntil(),
        value.lastErrorCode(),
        value.lastErrorDetail(),
        value.reviewVersion(),
        value.reviewedBySubjectId(),
        value.reviewAction() == null
            ? null
            : FurnitureEquipmentLinkReviewAction.valueOf(value.reviewAction()),
        value.reviewReason(),
        value.reviewedAt(),
        value.confirmedAt(),
        value.abandonedAt(),
        value.createdAt(),
        value.updatedAt());
  }

  public record ReviewedLink(FurnitureEquipmentLinkResponse response, boolean replayed) {}
}
