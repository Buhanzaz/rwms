package dev.buhanzaz.rwms.logistics.inventory.service;

import static dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnAssetStatus.FREE;
import static dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnAssetStatus.WAITING_ESTIMATE_CONFIRMATION;
import static dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnMediaOwnerType.LOGISTICS_RETURN;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReadiness;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnAssetStatus;
import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnInspectionLine;
import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnInspectionMedia;
import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnInspectionResponse;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a least-privilege projection from logistics-owned terminal return evidence. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NormalReturnInspectionService {
  private static final String RETURN_ASSET_LEASE_RELEASE = "RETURN_ASSET_LEASE_RELEASE";

  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository lines;
  private final LogisticsGuardRepository guards;
  private final LogisticsMediaReferenceRepository mediaReferences;
  private final LogisticsExternalAttemptRepository attempts;

  public NormalReturnInspectionResponse get(UUID returnId) {
    LogisticsDocument document =
        documents
            .findByIdAndDocumentType(returnId, LogisticsDocumentType.RETURN)
            .orElseThrow(LogisticsNotFoundException::new);
    if (document.isHistoricalRentalImport() || document.getInventorySourceId() != null) {
      throw new LogisticsNotFoundException();
    }

    NormalReturnAssetStatus status = terminalStatus(document.getState());
    if (document.getReturnArrivedAt() == null || document.getUpdatedAt() == null) {
      throw incomplete();
    }

    List<NormalReturnInspectionLine> inspectedLines = new ArrayList<>();
    for (LogisticsDocumentLine line :
        lines.findAllByDocument_IdOrderByLineNumber(document.getId())) {
      inspectedLines.add(line(document, line, status));
    }
    if (inspectedLines.isEmpty()) throw incomplete();

    return new NormalReturnInspectionResponse(
        document.getId(),
        document.getVersion(),
        document.getWarehouseId(),
        document.getReturnArrivedAt(),
        document.getUpdatedAt(),
        document.getState(),
        List.copyOf(inspectedLines));
  }

  private NormalReturnInspectionLine line(
      LogisticsDocument document, LogisticsDocumentLine line, NormalReturnAssetStatus status) {
    LogisticsGuard guard =
        guards.findByLine_Id(line.getId()).orElseThrow(NormalReturnInspectionService::incomplete);
    if (guard.getGuardState() != LogisticsGuardState.RELEASED
        || guard.getObservedAssetVersion() == null
        || guard.getObservedAssetVersion() < 0
        || !line.getAssetId().equals(guard.getAssetId())
        || guard.getReleasedAt() == null) {
      throw incomplete();
    }
    if (attempts
            .findByDocument_IdAndLine_IdAndOperationType(
                document.getId(), line.getId(), RETURN_ASSET_LEASE_RELEASE)
            .filter(
                attempt ->
                    attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED
                        && attempt.getCompletedAt() != null)
            .isEmpty()) {
      throw incomplete();
    }

    List<LogisticsMediaReference> references =
        mediaReferences.findAllByLine_IdAndPurposeOrderByCreatedAtAsc(
            line.getId(), LogisticsMediaPurpose.RETURN_INSPECTION);
    if (references.isEmpty()) throw incomplete();
    List<NormalReturnInspectionMedia> media =
        references.stream().map(this::media).toList();
    return new NormalReturnInspectionLine(
        line.getId(), line.getAssetId(), guard.getObservedAssetVersion(), status, media);
  }

  private NormalReturnInspectionMedia media(LogisticsMediaReference reference) {
    if (reference.getReadiness() != LogisticsMediaReadiness.READY
        || reference.getMediaId() == null
        || reference.getGeneration() == null
        || reference.getGeneration() < 1
        || reference.getOwnerVerifiedAt() == null) {
      throw incomplete();
    }
    return new NormalReturnInspectionMedia(
        reference.getMediaId(),
        reference.getGeneration(),
        LOGISTICS_RETURN,
        reference.getOwnerVerifiedAt());
  }

  private static NormalReturnAssetStatus terminalStatus(LogisticsDocumentState state) {
    return switch (state) {
      case ACCEPTED -> FREE;
      case ESTIMATE_REQUESTED -> WAITING_ESTIMATE_CONFIRMATION;
      default -> throw incomplete();
    };
  }

  private static LogisticsConflictException incomplete() {
    return new LogisticsConflictException("Normal return inspection is not completely proven");
  }
}
