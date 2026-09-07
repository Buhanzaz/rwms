package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortageId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.mapper.CompletedReturnEstimateProofMapperImpl;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.LogisticsReturnShortageRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompletedReturnEstimateProofReaderTest {
  private static final OffsetDateTime ARRIVED_AT = OffsetDateTime.parse("2026-09-07T10:00:00Z");
  private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-07T11:00:00Z");

  private final LogisticsReturnShortageRepository returnSources =
      mock(LogisticsReturnShortageRepository.class);
  private final MaintenanceEstimateRepository estimates = mock(MaintenanceEstimateRepository.class);
  private final EstimateLineRepository lines = mock(EstimateLineRepository.class);
  private final CompletedReturnEstimateProofReader reader =
      new CompletedReturnEstimateProofReader(
          returnSources,
          estimates,
          lines,
          new CompletedReturnEstimateProofMapperImpl());

  @Test
  void returnsCompletedNonEmptyReturnEstimateProof() {
    Fixture fixture = completedFixture(UUID.randomUUID());
    UUID repairId = UUID.randomUUID();
    when(fixture.estimate().getRepairId()).thenReturn(repairId);
    when(lines.countByEstimateIdAndEstimateRevision(fixture.estimateId(), 3)).thenReturn(2L);

    var response = reader.get(fixture.estimateId());

    assertThat(response.estimateId()).isEqualTo(fixture.estimateId());
    assertThat(response.estimateVersion()).isEqualTo(4L);
    assertThat(response.estimateRevision()).isEqualTo(3);
    assertThat(response.returnId()).isEqualTo(fixture.returnId());
    assertThat(response.lineId()).isEqualTo(fixture.lineId());
    assertThat(response.warehouseId()).isEqualTo(fixture.warehouseId());
    assertThat(response.assetId()).isEqualTo(fixture.assetId());
    assertThat(response.assetVersion()).isEqualTo(9L);
    assertThat(response.arrivedAt()).isEqualTo(ARRIVED_AT);
    assertThat(response.completedAt()).isEqualTo(COMPLETED_AT);
    assertThat(response.completionKind()).isEqualTo("NON_EMPTY");
    assertThat(response.repairId()).isEqualTo(repairId);
  }

  @Test
  void returnsCompletedEmptyReturnEstimateProof() {
    Fixture fixture = completedFixture(UUID.randomUUID());
    when(fixture.estimate().getRepairId()).thenReturn(null);
    when(lines.countByEstimateIdAndEstimateRevision(fixture.estimateId(), 3)).thenReturn(0L);

    var response = reader.get(fixture.estimateId());

    assertThat(response.completionKind()).isEqualTo("EMPTY");
    assertThat(response.repairId()).isNull();
  }

  @Test
  void rejectsLinkedDraftAsIncomplete() {
    Fixture fixture = completedFixture(UUID.randomUUID());
    when(fixture.estimate().getState()).thenReturn(EstimateState.DRAFT);

    assertThatThrownBy(() -> reader.get(fixture.estimateId()))
        .isInstanceOfSatisfying(
            MaintenanceConflictException.class,
            exception -> assertThat(exception.code()).isEqualTo("INCOMPLETE_ESTIMATE"));
    verify(lines, never()).countByEstimateIdAndEstimateRevision(fixture.estimateId(), 3);
  }

  @Test
  void hidesAnEstimateThatIsNotOwnedByAReturnLine() {
    UUID estimateId = UUID.randomUUID();
    when(returnSources.findByEstimateId(estimateId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> reader.get(estimateId))
        .isInstanceOf(MaintenanceNotFoundException.class);
    verify(estimates, never()).findById(estimateId);
  }

  @Test
  void hidesLegacyReturnSourceWithoutPhysicalArrivalProof() {
    UUID estimateId = UUID.randomUUID();
    LogisticsReturnShortage source = mock(LogisticsReturnShortage.class);
    when(returnSources.findByEstimateId(estimateId)).thenReturn(Optional.of(source));
    when(source.getArrivedAt()).thenReturn(null);

    assertThatThrownBy(() -> reader.get(estimateId))
        .isInstanceOf(MaintenanceNotFoundException.class);
    verify(estimates, never()).findById(estimateId);
  }

  @Test
  void rejectsConflictingSourceEstimateId() {
    Fixture fixture = completedFixture(UUID.randomUUID());
    when(fixture.source().getEstimateId()).thenReturn(UUID.randomUUID());

    assertProofConflict(fixture.estimateId());
  }

  @Test
  void rejectsMismatchedSourceOwnershipAndCompletionTime() {
    Fixture fixture = completedFixture(UUID.randomUUID());
    when(fixture.source().getWarehouseId()).thenReturn(UUID.randomUUID());

    assertProofConflict(fixture.estimateId());

    fixture = completedFixture(UUID.randomUUID());
    when(fixture.source().getRentalItemId()).thenReturn(UUID.randomUUID());

    assertProofConflict(fixture.estimateId());

    fixture = completedFixture(UUID.randomUUID());
    when(fixture.estimate().getCompletedAt()).thenReturn(ARRIVED_AT.minusSeconds(1));

    assertProofConflict(fixture.estimateId());
  }

  @Test
  void rejectsCompletionKindThatDisagreesWithRepairLink() {
    Fixture fixture = completedFixture(UUID.randomUUID());
    when(fixture.estimate().getRepairId()).thenReturn(UUID.randomUUID());
    when(lines.countByEstimateIdAndEstimateRevision(fixture.estimateId(), 3)).thenReturn(0L);

    assertProofConflict(fixture.estimateId());
  }

  private Fixture completedFixture(UUID estimateId) {
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsReturnShortage source = mock(LogisticsReturnShortage.class);
    MaintenanceEstimate estimate = mock(MaintenanceEstimate.class);
    when(returnSources.findByEstimateId(estimateId)).thenReturn(Optional.of(source));
    when(estimates.findById(estimateId)).thenReturn(Optional.of(estimate));
    when(source.getId()).thenReturn(new LogisticsReturnShortageId(returnId, lineId));
    when(source.getEstimateId()).thenReturn(estimateId);
    when(source.getWarehouseId()).thenReturn(warehouseId);
    when(source.getRentalItemId()).thenReturn(assetId);
    when(source.getRentalItemVersionSnapshot()).thenReturn(9L);
    when(source.getArrivedAt()).thenReturn(ARRIVED_AT);
    when(estimate.getId()).thenReturn(estimateId);
    when(estimate.getVersion()).thenReturn(4L);
    when(estimate.getRevision()).thenReturn(3);
    when(estimate.getState()).thenReturn(EstimateState.COMPLETED);
    when(estimate.getWarehouseId()).thenReturn(warehouseId);
    when(estimate.getRentalItemId()).thenReturn(assetId);
    when(estimate.getCompletedAt()).thenReturn(COMPLETED_AT);
    return new Fixture(estimateId, returnId, lineId, warehouseId, assetId, source, estimate);
  }

  private void assertProofConflict(UUID estimateId) {
    assertThatThrownBy(() -> reader.get(estimateId))
        .isInstanceOfSatisfying(
            MaintenanceConflictException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("RETURN_ESTIMATE_PROOF_CONFLICT"));
  }

  private record Fixture(
      UUID estimateId,
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID assetId,
      LogisticsReturnShortage source,
      MaintenanceEstimate estimate) {}
}
