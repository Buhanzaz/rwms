package dev.buhanzaz.rwms.logistics.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReadiness;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnAssetStatus;
import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnMediaOwnerType;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NormalReturnInspectionServiceTest {
  private static final OffsetDateTime ARRIVED_AT = OffsetDateTime.parse("2026-08-01T09:00:00Z");
  private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-08-01T09:15:00Z");
  private static final OffsetDateTime VERIFIED_AT = OffsetDateTime.parse("2026-08-01T09:10:00Z");

  @Test
  void returnsAcceptedInspectionWithFreeAssetAndExternalReadyMedia() {
    Fixture fixture = completed(LogisticsDocumentState.ACCEPTED);

    var response = fixture.service().get(fixture.returnId());

    assertThat(response.returnId()).isEqualTo(fixture.returnId());
    assertThat(response.documentVersion()).isEqualTo(7);
    assertThat(response.arrivedAt()).isEqualTo(ARRIVED_AT);
    assertThat(response.completedAt()).isEqualTo(COMPLETED_AT);
    assertThat(response.terminalState()).isEqualTo(LogisticsDocumentState.ACCEPTED);
    assertThat(response.lines()).singleElement().satisfies(line -> {
      assertThat(line.assetId()).isEqualTo(fixture.assetId());
      assertThat(line.assetVersion()).isEqualTo(12);
      assertThat(line.status()).isEqualTo(NormalReturnAssetStatus.FREE);
      assertThat(line.media()).singleElement().satisfies(media -> {
        assertThat(media.ownerType()).isEqualTo(NormalReturnMediaOwnerType.LOGISTICS_RETURN);
        assertThat(media.generation()).isEqualTo(3);
        assertThat(media.ownerVerifiedAt()).isEqualTo(VERIFIED_AT);
      });
    });
  }

  @Test
  void returnsEstimateRequestedInspectionWithoutCreatingAnotherMaintenanceWorkflow() {
    Fixture fixture = completed(LogisticsDocumentState.ESTIMATE_REQUESTED);

    var response = fixture.service().get(fixture.returnId());

    assertThat(response.lines().getFirst().status())
        .isEqualTo(NormalReturnAssetStatus.WAITING_ESTIMATE_CONFIRMATION);
  }

  @Test
  void rejectsTerminalReturnWithoutReleasedGuardOrReadyMedia() {
    Fixture unreleased = completed(LogisticsDocumentState.ACCEPTED);
    when(unreleased.guard().getGuardState()).thenReturn(LogisticsGuardState.ACTIVE);

    assertThatThrownBy(() -> unreleased.service().get(unreleased.returnId()))
        .isInstanceOf(LogisticsConflictException.class);

    Fixture pendingMedia = completed(LogisticsDocumentState.ACCEPTED);
    when(pendingMedia.media().getReadiness()).thenReturn(LogisticsMediaReadiness.PENDING);

    assertThatThrownBy(() -> pendingMedia.service().get(pendingMedia.returnId()))
        .isInstanceOf(LogisticsConflictException.class);

    Fixture missingReleaseReceipt = completed(LogisticsDocumentState.ACCEPTED);
    when(missingReleaseReceipt.attempts()
            .findByDocument_IdAndLine_IdAndOperationType(
                missingReleaseReceipt.returnId(),
                missingReleaseReceipt.lineId(),
                "RETURN_ASSET_LEASE_RELEASE"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> missingReleaseReceipt.service().get(missingReleaseReceipt.returnId()))
        .isInstanceOf(LogisticsConflictException.class);
  }

  @Test
  void rejectsIncompleteAndHidesUnknownOrImportedReturns() {
    Fixture incomplete = completed(LogisticsDocumentState.INSPECTION_REQUIRED);
    assertThatThrownBy(() -> incomplete.service().get(incomplete.returnId()))
        .isInstanceOf(LogisticsConflictException.class);

    Fixture imported = completed(LogisticsDocumentState.ACCEPTED);
    when(imported.document().getInventorySourceId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> imported.service().get(imported.returnId()))
        .isInstanceOf(LogisticsNotFoundException.class);

    Fixture historical = completed(LogisticsDocumentState.ACCEPTED);
    when(historical.document().isHistoricalRentalImport()).thenReturn(true);
    assertThatThrownBy(() -> historical.service().get(historical.returnId()))
        .isInstanceOf(LogisticsNotFoundException.class);

    Fixture unknown = completed(LogisticsDocumentState.ACCEPTED);
    when(unknown.documents().findByIdAndDocumentType(unknown.returnId(), LogisticsDocumentType.RETURN))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> unknown.service().get(unknown.returnId()))
        .isInstanceOf(LogisticsNotFoundException.class);
  }

  private static Fixture completed(LogisticsDocumentState state) {
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository lines = mock(LogisticsDocumentLineRepository.class);
    LogisticsGuardRepository guards = mock(LogisticsGuardRepository.class);
    LogisticsMediaReferenceRepository mediaReferences = mock(LogisticsMediaReferenceRepository.class);
    LogisticsExternalAttemptRepository attempts = mock(LogisticsExternalAttemptRepository.class);
    NormalReturnInspectionService service =
        new NormalReturnInspectionService(documents, lines, guards, mediaReferences, attempts);

    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(returnId);
    when(document.getVersion()).thenReturn(7L);
    when(document.getWarehouseId()).thenReturn(warehouseId);
    when(document.getReturnArrivedAt()).thenReturn(ARRIVED_AT);
    when(document.getUpdatedAt()).thenReturn(COMPLETED_AT);
    when(document.getState()).thenReturn(state);
    when(documents.findByIdAndDocumentType(returnId, LogisticsDocumentType.RETURN))
        .thenReturn(Optional.of(document));

    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    when(line.getId()).thenReturn(lineId);
    when(line.getAssetId()).thenReturn(assetId);
    when(lines.findAllByDocument_IdOrderByLineNumber(returnId)).thenReturn(List.of(line));

    LogisticsGuard guard = mock(LogisticsGuard.class);
    when(guard.getGuardState()).thenReturn(LogisticsGuardState.RELEASED);
    when(guard.getAssetId()).thenReturn(assetId);
    when(guard.getObservedAssetVersion()).thenReturn(12L);
    when(guard.getReleasedAt()).thenReturn(COMPLETED_AT);
    when(guards.findByLine_Id(lineId)).thenReturn(Optional.of(guard));

    LogisticsExternalAttempt release = mock(LogisticsExternalAttempt.class);
    when(release.getResult()).thenReturn(LogisticsExternalAttemptResult.CONFIRMED);
    when(release.getCompletedAt()).thenReturn(COMPLETED_AT);
    when(attempts.findByDocument_IdAndLine_IdAndOperationType(
            returnId, lineId, "RETURN_ASSET_LEASE_RELEASE"))
        .thenReturn(Optional.of(release));

    LogisticsMediaReference media = mock(LogisticsMediaReference.class);
    when(media.getMediaId()).thenReturn(UUID.randomUUID());
    when(media.getGeneration()).thenReturn(3L);
    when(media.getReadiness()).thenReturn(LogisticsMediaReadiness.READY);
    when(media.getOwnerVerifiedAt()).thenReturn(VERIFIED_AT);
    when(mediaReferences.findAllByLine_IdAndPurposeOrderByCreatedAtAsc(
            lineId, LogisticsMediaPurpose.RETURN_INSPECTION))
        .thenReturn(List.of(media));

    return new Fixture(
        service,
        returnId,
        lineId,
        assetId,
        document,
        guard,
        media,
        documents,
        attempts);
  }

  private record Fixture(
      NormalReturnInspectionService service,
      UUID returnId,
      UUID lineId,
      UUID assetId,
      LogisticsDocument document,
      LogisticsGuard guard,
      LogisticsMediaReference media,
      LogisticsDocumentRepository documents,
      LogisticsExternalAttemptRepository attempts) {}
}
