package dev.buhanzaz.rwms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.api.ReturnEstimateInspectionResponse.State;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryReturnInspectionImport;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway.CompletedReturnEstimateProof;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryReturnInspectionImportRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ReturnEstimateInspectionReaderTest {
  private static final OffsetDateTime COMPLETED_AT =
      OffsetDateTime.parse("2026-09-07T11:00:00Z");

  private final InventoryReturnInspectionImportRepository imports =
      mock(InventoryReturnInspectionImportRepository.class);
  private final InventoryFindingRepository findings = mock(InventoryFindingRepository.class);
  private final InventorySessionRepository sessions = mock(InventorySessionRepository.class);
  private final InventoryDependencyGateway dependencies = mock(InventoryDependencyGateway.class);
  private final ReturnEstimateInspectionReader reader =
      new ReturnEstimateInspectionReader(imports, findings, sessions, dependencies);

  @Test
  void confirmedReceiptWinsWithoutConsultingSessionLifecycleOrRemoteProof() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    InventoryReturnInspectionImport receipt = mock(InventoryReturnInspectionImport.class);
    InventoryFinding finding = mock(InventoryFinding.class);
    when(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .thenReturn(Optional.of(receipt));
    when(receipt.getEstimateId()).thenReturn(estimateId);
    when(receipt.getWarehouseId()).thenReturn(warehouseId);
    when(receipt.getInventoryId()).thenReturn(inventoryId);
    when(receipt.getFindingId()).thenReturn(findingId);
    when(findings.findByIdAndInventoryId(findingId, inventoryId)).thenReturn(Optional.of(finding));
    when(finding.getDisplayCanonicalNumber()).thenReturn("A-017");

    var response = reader.get(estimateId, warehouseId);

    assertThat(response.state()).isEqualTo(State.CONFIRMED);
    assertThat(response.inventoryId()).isEqualTo(inventoryId);
    assertThat(response.findingId()).isEqualTo(findingId);
    assertThat(response.cabinNumber()).isEqualTo("A-017");
    verifyNoInteractions(dependencies, sessions);
  }

  @Test
  void absentReturnProofMeansInspectionIsNotRequired() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    when(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .thenReturn(Optional.empty());
    when(dependencies.completedReturnEstimate(estimateId)).thenReturn(Optional.empty());

    var response = reader.get(estimateId, warehouseId);

    assertThat(response.state()).isEqualTo(State.NOT_REQUIRED);
    assertThat(response.inventoryId()).isNull();
    assertThat(response.findingId()).isNull();
    assertThat(response.cabinNumber()).isNull();
    verifyNoInteractions(sessions);
  }

  @Test
  void activeSessionStartedAtCompletionTimeMeansInspectionIsPending() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    InventorySession active = mock(InventorySession.class);
    noReceiptWithProof(estimateId, warehouseId);
    when(sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE))
        .thenReturn(Optional.of(active));
    when(active.getStartedAt()).thenReturn(COMPLETED_AT);

    var response = reader.get(estimateId, warehouseId);

    assertThat(response.state()).isEqualTo(State.PENDING);
    assertThat(response.inventoryId()).isNull();
    assertThat(response.findingId()).isNull();
    assertThat(response.cabinNumber()).isNull();
  }

  @Test
  void laterOrAbsentActiveSessionMeansInspectionIsNotRequired() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    InventorySession active = mock(InventorySession.class);
    noReceiptWithProof(estimateId, warehouseId);
    when(sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE))
        .thenReturn(Optional.of(active));
    when(active.getStartedAt()).thenReturn(COMPLETED_AT.plusSeconds(1));

    assertThat(reader.get(estimateId, warehouseId).state()).isEqualTo(State.NOT_REQUIRED);

    estimateId = UUID.randomUUID();
    noReceiptWithProof(estimateId, warehouseId);
    when(sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE))
        .thenReturn(Optional.empty());

    assertThat(reader.get(estimateId, warehouseId).state()).isEqualTo(State.NOT_REQUIRED);
  }

  @Test
  void receiptImportedDuringSessionLookupIsConfirmedBeforeReportingNotRequired() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    InventoryReturnInspectionImport receipt = mock(InventoryReturnInspectionImport.class);
    InventoryFinding finding = mock(InventoryFinding.class);
    when(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(receipt));
    when(dependencies.completedReturnEstimate(estimateId))
        .thenReturn(Optional.of(proof(estimateId, warehouseId)));
    when(sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE))
        .thenReturn(Optional.empty());
    when(receipt.getEstimateId()).thenReturn(estimateId);
    when(receipt.getWarehouseId()).thenReturn(warehouseId);
    when(receipt.getInventoryId()).thenReturn(inventoryId);
    when(receipt.getFindingId()).thenReturn(findingId);
    when(findings.findByIdAndInventoryId(findingId, inventoryId)).thenReturn(Optional.of(finding));
    when(finding.getDisplayCanonicalNumber()).thenReturn("A-018");

    var response = reader.get(estimateId, warehouseId);

    assertThat(response.state()).isEqualTo(State.CONFIRMED);
    assertThat(response.inventoryId()).isEqualTo(inventoryId);
    assertThat(response.findingId()).isEqualTo(findingId);
    assertThat(response.cabinNumber()).isEqualTo("A-018");
  }

  @Test
  void proofFromAnotherWarehouseIsHidden() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    when(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .thenReturn(Optional.empty());
    when(dependencies.completedReturnEstimate(estimateId))
        .thenReturn(Optional.of(proof(estimateId, UUID.randomUUID())));

    assertThatThrownBy(() -> reader.get(estimateId, warehouseId))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(exception.code()).isEqualTo("INVENTORY_NOT_FOUND");
            });
    verifyNoInteractions(sessions);
  }

  @Test
  void inconsistentReceiptCannotInventAConfirmation() {
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    InventoryReturnInspectionImport receipt = mock(InventoryReturnInspectionImport.class);
    when(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .thenReturn(Optional.of(receipt));
    when(receipt.getEstimateId()).thenReturn(UUID.randomUUID());

    assertThatThrownBy(() -> reader.get(estimateId, warehouseId))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
    verifyNoInteractions(findings, dependencies, sessions);
  }

  private void noReceiptWithProof(UUID estimateId, UUID warehouseId) {
    when(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .thenReturn(Optional.empty());
    when(dependencies.completedReturnEstimate(estimateId))
        .thenReturn(Optional.of(proof(estimateId, warehouseId)));
  }

  private static CompletedReturnEstimateProof proof(UUID estimateId, UUID warehouseId) {
    return new CompletedReturnEstimateProof(
        estimateId,
        2L,
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        warehouseId,
        UUID.randomUUID(),
        3L,
        COMPLETED_AT.minusHours(1),
        COMPLETED_AT,
        "EMPTY",
        null);
  }
}
