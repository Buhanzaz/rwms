package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.mapper.ScenarioCapacitySnapshotResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningCapacitySnapshotRequest;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Covers atomic canonicalization and replay fencing for anonymous scenario capacity. */
class ScenarioCapacitySnapshotServiceTest {
  private static final UUID SCENARIO =
      UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000402");
  private static final UUID COMMAND =
      UUID.fromString("00000000-0000-0000-0000-000000000403");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-26T20:00:00Z"), ZoneOffset.UTC);

  @Test
  void replacesInStableJobOrderAndReplaysTheExactCommand() {
    ScenarioCapacitySnapshotRepository repository = mock(ScenarioCapacitySnapshotRepository.class);
    ScenarioCapacityCommandReceiptRepository receipts =
        mock(ScenarioCapacityCommandReceiptRepository.class);
    ScenarioCapacitySnapshotResponseMapper mapper =
        mock(ScenarioCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    ScenarioCapacitySnapshotService service =
        new ScenarioCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK);
    ReplacePlanningCapacitySnapshotRequest request = request("a".repeat(64), 2);
    when(repository.findByWarehouseIdForUpdate(WAREHOUSE)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(receipts.findById(COMMAND)).thenReturn(Optional.empty());
    when(receipts
            .findFirstByWarehouseIdAndSourceScenarioIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                WAREHOUSE, SCENARIO, request.sourceGeneration(), request.sourceRevision()))
        .thenReturn(Optional.empty());
    when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.toResponse(any(ScenarioCapacitySnapshot.class), anyBoolean()))
        .thenAnswer(
            invocation -> {
              ScenarioCapacitySnapshot snapshot = invocation.getArgument(0);
              boolean replayed = invocation.getArgument(1);
              return new PlanningCapacitySnapshotResponse(
                  snapshot.getWarehouseId(),
                  snapshot.getSourceScenarioId(),
                  snapshot.getSourceGeneration(),
                  snapshot.getVersion(),
                  snapshot.getSourceRevision(),
                  snapshot.getJobs().size(),
                  replayed,
                  snapshot.getUpdatedAt());
            });
    when(mapper.toResponse(any(ScenarioCapacityCommandReceipt.class), anyBoolean()))
        .thenAnswer(
            invocation -> {
              ScenarioCapacityCommandReceipt receipt = invocation.getArgument(0);
              boolean replayed = invocation.getArgument(1);
              return new PlanningCapacitySnapshotResponse(
                  receipt.getWarehouseId(),
                  receipt.getSourceScenarioId(),
                  receipt.getSourceGeneration(),
                  receipt.getSnapshotVersion(),
                  receipt.getSourceRevision(),
                  receipt.getJobCount(),
                  replayed,
                  receipt.getResponseUpdatedAt());
            });

    PlanningCapacitySnapshotResponse created = service.replace(SCENARIO, COMMAND, request);
    ArgumentCaptor<ScenarioCapacitySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(ScenarioCapacitySnapshot.class);
    verify(repository).saveAndFlush(snapshotCaptor.capture());
    ScenarioCapacitySnapshot snapshot = snapshotCaptor.getValue();
    ArgumentCaptor<ScenarioCapacityCommandReceipt> receiptCaptor =
        ArgumentCaptor.forClass(ScenarioCapacityCommandReceipt.class);
    verify(receipts).saveAndFlush(receiptCaptor.capture());

    assertThat(created.replayed()).isFalse();
    assertThat(snapshot.getJobs()).extracting(job -> job.getDeliveryDate()).isSorted();

    when(receipts.findById(COMMAND)).thenReturn(Optional.of(receiptCaptor.getValue()));
    PlanningCapacitySnapshotResponse replayed = service.replace(SCENARIO, COMMAND, request);

    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.jobCount()).isEqualTo(2);
    verify(capacityFence).acquireScenario(WAREHOUSE);
  }

  @Test
  void rejectsReuseOfACommandKeyForDifferentCapacityFacts() {
    ScenarioCapacitySnapshotRepository repository = mock(ScenarioCapacitySnapshotRepository.class);
    ScenarioCapacityCommandReceiptRepository receipts =
        mock(ScenarioCapacityCommandReceiptRepository.class);
    ScenarioCapacitySnapshotResponseMapper mapper =
        mock(ScenarioCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    ScenarioCapacitySnapshotService service =
        new ScenarioCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK);
    ReplacePlanningCapacitySnapshotRequest original = request("a".repeat(64), 1);
    List<PlanningCapacityJobRequest> sorted = original.jobs().stream().sorted(
        java.util.Comparator.comparing(PlanningCapacityJobRequest::deliveryDate)).toList();
    ScenarioCapacitySnapshot snapshot =
        ScenarioCapacitySnapshot.create(
            WAREHOUSE,
            SCENARIO,
            original.sourceGeneration(),
            original.sourceRevision(),
            sorted,
            java.time.OffsetDateTime.now(CLOCK));
    ScenarioCapacityCommandReceipt receipt =
        ScenarioCapacityCommandReceipt.applied(
            COMMAND,
            snapshot,
            ScenarioCapacityChecksum.sha256(SCENARIO, original, sorted),
            java.time.OffsetDateTime.now(CLOCK));
    when(receipts.findById(COMMAND)).thenReturn(Optional.of(receipt));

    assertThatThrownBy(() -> service.replace(SCENARIO, COMMAND, request("b".repeat(64), 2)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo("PLANNING_CAPACITY_IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void rejectsAPreviouslyUnacceptedGenerationOlderThanTheActiveWarehouseState() {
    ScenarioCapacitySnapshotRepository repository = mock(ScenarioCapacitySnapshotRepository.class);
    ScenarioCapacityCommandReceiptRepository receipts =
        mock(ScenarioCapacityCommandReceiptRepository.class);
    ScenarioCapacitySnapshotResponseMapper mapper =
        mock(ScenarioCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    ScenarioCapacitySnapshotService service =
        new ScenarioCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK);
    ReplacePlanningCapacitySnapshotRequest activeRequest =
        request(2, "b".repeat(64), 1);
    ScenarioCapacitySnapshot active =
        ScenarioCapacitySnapshot.create(
            WAREHOUSE,
            SCENARIO,
            activeRequest.sourceGeneration(),
            activeRequest.sourceRevision(),
            activeRequest.jobs(),
            java.time.OffsetDateTime.now(CLOCK));
    when(receipts.findById(COMMAND)).thenReturn(Optional.empty());
    when(receipts
            .findFirstByWarehouseIdAndSourceScenarioIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                WAREHOUSE, SCENARIO, 1, "a".repeat(64)))
        .thenReturn(Optional.empty());
    when(repository.findByWarehouseIdForUpdate(WAREHOUSE)).thenReturn(Optional.of(active));

    assertThatThrownBy(
            () -> service.replace(SCENARIO, COMMAND, request(1, "a".repeat(64), 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo("PLANNING_CAPACITY_GENERATION_STALE"));
  }

  @Test
  void reappliesAFormerRevisionWhenItReturnsInANewerGeneration() {
    ScenarioCapacitySnapshotRepository repository = mock(ScenarioCapacitySnapshotRepository.class);
    ScenarioCapacityCommandReceiptRepository receipts =
        mock(ScenarioCapacityCommandReceiptRepository.class);
    ScenarioCapacitySnapshotResponseMapper mapper =
        mock(ScenarioCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    ScenarioCapacitySnapshotService service =
        new ScenarioCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK);
    ReplacePlanningCapacitySnapshotRequest activeRequest = request(2, "b".repeat(64), 1);
    ScenarioCapacitySnapshot active =
        ScenarioCapacitySnapshot.create(
            WAREHOUSE,
            SCENARIO,
            activeRequest.sourceGeneration(),
            activeRequest.sourceRevision(),
            activeRequest.jobs(),
            java.time.OffsetDateTime.now(CLOCK));
    ReplacePlanningCapacitySnapshotRequest returning = request(3, "a".repeat(64), 1);
    when(receipts.findById(COMMAND)).thenReturn(Optional.empty());
    when(receipts
            .findFirstByWarehouseIdAndSourceScenarioIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                WAREHOUSE, SCENARIO, 3, returning.sourceRevision()))
        .thenReturn(Optional.empty());
    when(repository.findByWarehouseIdForUpdate(WAREHOUSE)).thenReturn(Optional.of(active));
    when(repository.saveAndFlush(active)).thenReturn(active);
    when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.replace(SCENARIO, COMMAND, returning);

    assertThat(active.getSourceGeneration()).isEqualTo(3);
    assertThat(active.getSourceRevision()).isEqualTo("a".repeat(64));
    verify(repository).saveAndFlush(active);
  }

  private static ReplacePlanningCapacitySnapshotRequest request(String revision, int jobs) {
    return request(1, revision, jobs);
  }

  private static ReplacePlanningCapacitySnapshotRequest request(
      long sourceGeneration, String revision, int jobs) {
    List<PlanningCapacityJobRequest> values =
        java.util.stream.IntStream.range(0, jobs)
            .mapToObj(
                index ->
                    new PlanningCapacityJobRequest(
                        UUID.nameUUIDFromBytes(("job-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        LocalDate.of(2026, 8, 28).plusDays(jobs - index),
                        BigDecimal.valueOf(55.7 + index / 100.0),
                        BigDecimal.valueOf(37.6 + index / 100.0),
                        1,
                        LocalTime.of(9, 0),
                        LocalTime.of(12, 0),
                        30))
            .toList();
    return new ReplacePlanningCapacitySnapshotRequest(
        WAREHOUSE, sourceGeneration, revision, values);
  }
}
