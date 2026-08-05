package dev.buhanzaz.rwms.maintenance.disposition.domain;

import java.util.List;
import java.util.UUID;

/** Input captured once when a disposition decision is initiated. */
public record PropertyDispositionDecisionDraft(
    UUID warehouseId,
    PropertyDispositionAssetKind assetKind,
    UUID assetId,
    String assetDisplayName,
    PropertyDispositionKind kind,
    PropertyDispositionSource source,
    Long expectedAssetVersion,
    Long expectedSourceBalanceVersion,
    Long quantity,
    UUID maintenanceCustodyClaimId,
    Long maintenanceCustodyVersion,
    String reason,
    String evidenceLink,
    UUID sourceRepairId,
    UUID rootRepairId,
    UUID inventoryId,
    UUID findingId,
    UUID initiatedBySubjectId,
    UUID idempotencyKey,
    String requestSha256,
    String initiatedByActorSnapshot,
    PropertyDispositionContentsMode contentsMode,
    List<PropertyDispositionContentSnapshotLineDraft> contents) {
  public PropertyDispositionDecisionDraft {
    contents = contents == null ? List.of() : List.copyOf(contents);
  }

  public PropertyDispositionDecisionDraft(
      UUID warehouseId,
      PropertyDispositionAssetKind assetKind,
      UUID assetId,
      String assetDisplayName,
      PropertyDispositionKind kind,
      PropertyDispositionSource source,
      long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      String reason,
      String evidenceLink,
      UUID sourceRepairId,
      UUID rootRepairId,
      UUID inventoryId,
      UUID findingId,
      UUID initiatedBySubjectId,
      UUID idempotencyKey,
      String requestSha256,
      String initiatedByActorSnapshot,
      PropertyDispositionContentsMode contentsMode,
      List<PropertyDispositionContentSnapshotLineDraft> contents) {
    this(
        warehouseId,
        assetKind,
        assetId,
        assetDisplayName,
        kind,
        source,
        expectedAssetVersion,
        expectedSourceBalanceVersion,
        quantity,
        null,
        null,
        reason,
        evidenceLink,
        sourceRepairId,
        rootRepairId,
        inventoryId,
        findingId,
        initiatedBySubjectId,
        idempotencyKey,
        requestSha256,
        initiatedByActorSnapshot,
        contentsMode,
        contents);
  }
}
