package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.mapper.WarehouseCapacitySnapshotResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityTaskType;
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
import tools.jackson.databind.ObjectMapper;

/** Covers atomic canonicalization and replay fencing for anonymous warehouse capacity. */
class WarehouseCapacitySnapshotServiceTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000402");
  private static final UUID COMMAND =
      UUID.fromString("00000000-0000-0000-0000-000000000403");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-26T20:00:00Z"), ZoneOffset.UTC);

  @Test
  void replacesInStableJobOrderAndReplaysTheExactCommand() {
    WarehouseCapacitySnapshotRepository repository = mock(WarehouseCapacitySnapshotRepository.class);
    WarehouseCapacityCommandReceiptRepository receipts =
        mock(WarehouseCapacityCommandReceiptRepository.class);
    WarehouseCapacitySnapshotResponseMapper mapper =
        mock(WarehouseCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    WarehouseCapacitySnapshotService service =
        new WarehouseCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK, new ObjectMapper());
    ReplacePlanningCapacitySnapshotRequest request = request("a".repeat(64), 2);
    when(repository.findByWarehouseIdForUpdate(WAREHOUSE)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(receipts.findById(COMMAND)).thenReturn(Optional.empty());
    when(receipts
            .findFirstByWarehouseIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                WAREHOUSE, request.sourceGeneration(), request.sourceRevision()))
        .thenReturn(Optional.empty());
    when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.toResponse(any(WarehouseCapacitySnapshot.class), anyBoolean()))
        .thenAnswer(
            invocation -> {
              WarehouseCapacitySnapshot snapshot = invocation.getArgument(0);
              boolean replayed = invocation.getArgument(1);
              return new PlanningCapacitySnapshotResponse(
                  snapshot.getWarehouseId(),
                  snapshot.getSourceGeneration(),
                  snapshot.getVersion(),
                  snapshot.getSourceRevision(),
                  snapshot.getJobs().size(),
                  snapshot.getShifts().size(),
                  snapshot.getPriceZones().size(),
                  replayed,
                  snapshot.getUpdatedAt());
            });
    when(mapper.toResponse(any(WarehouseCapacityCommandReceipt.class), anyBoolean()))
        .thenAnswer(
            invocation -> {
              WarehouseCapacityCommandReceipt receipt = invocation.getArgument(0);
              boolean replayed = invocation.getArgument(1);
              return new PlanningCapacitySnapshotResponse(
                  receipt.getWarehouseId(),
                  receipt.getSourceGeneration(),
                  receipt.getSnapshotVersion(),
                  receipt.getSourceRevision(),
                  receipt.getJobCount(),
                  receipt.getShiftCount(),
                  receipt.getPriceZoneCount(),
                  replayed,
                  receipt.getResponseUpdatedAt());
            });

    PlanningCapacitySnapshotResponse created = service.replace(WAREHOUSE, COMMAND, request);
    ArgumentCaptor<WarehouseCapacitySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(WarehouseCapacitySnapshot.class);
    verify(repository).saveAndFlush(snapshotCaptor.capture());
    WarehouseCapacitySnapshot snapshot = snapshotCaptor.getValue();
    ArgumentCaptor<WarehouseCapacityCommandReceipt> receiptCaptor =
        ArgumentCaptor.forClass(WarehouseCapacityCommandReceipt.class);
    verify(receipts).saveAndFlush(receiptCaptor.capture());

    assertThat(created.replayed()).isFalse();
    assertThat(snapshot.getJobs()).extracting(job -> job.getDeliveryDate()).isSorted();
    assertThat(snapshot.getIsochronePrice60Minutes()).isEqualTo(10_000);
    assertThat(snapshot.getIsochronePrice120Minutes()).isEqualTo(15_000);
    assertThat(snapshot.getIsochronePrice180Minutes()).isEqualTo(20_000);
    assertThat(snapshot.getIsochronePrice240Minutes()).isEqualTo(25_000);
    assertThat(snapshot.getRestrictionZones()).isEmpty();

    when(receipts.findById(COMMAND)).thenReturn(Optional.of(receiptCaptor.getValue()));
    PlanningCapacitySnapshotResponse replayed = service.replace(WAREHOUSE, COMMAND, request);

    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.jobCount()).isEqualTo(2);
    verify(capacityFence).acquireWarehouseCapacity(WAREHOUSE);
  }

  @Test
  void rejectsReuseOfACommandKeyForDifferentCapacityFacts() {
    WarehouseCapacitySnapshotRepository repository = mock(WarehouseCapacitySnapshotRepository.class);
    WarehouseCapacityCommandReceiptRepository receipts =
        mock(WarehouseCapacityCommandReceiptRepository.class);
    WarehouseCapacitySnapshotResponseMapper mapper =
        mock(WarehouseCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    WarehouseCapacitySnapshotService service =
        new WarehouseCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK, new ObjectMapper());
    ReplacePlanningCapacitySnapshotRequest original = request("a".repeat(64), 1);
    List<PlanningCapacityJobRequest> sorted = original.jobs().stream().sorted(
        java.util.Comparator.comparing(PlanningCapacityJobRequest::deliveryDate)).toList();
    WarehouseCapacitySnapshot snapshot =
        WarehouseCapacitySnapshot.create(
            WAREHOUSE,
            original.sourceGeneration(),
            original.sourceRevision(),
            sorted.stream().map(WarehouseCapacitySnapshotServiceTest::facts).toList(),
            List.of(),
            List.of(),
            java.time.OffsetDateTime.now(CLOCK));
    WarehouseCapacityCommandReceipt receipt =
        WarehouseCapacityCommandReceipt.applied(
            COMMAND,
            snapshot,
            WarehouseCapacityChecksum.sha256(
                WAREHOUSE, original, sorted, List.of(), List.of()),
            java.time.OffsetDateTime.now(CLOCK));
    when(receipts.findById(COMMAND)).thenReturn(Optional.of(receipt));

    assertThatThrownBy(() -> service.replace(WAREHOUSE, COMMAND, request("b".repeat(64), 2)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo("PLANNING_CAPACITY_IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void rejectsAPreviouslyUnacceptedGenerationOlderThanTheActiveWarehouseState() {
    WarehouseCapacitySnapshotRepository repository = mock(WarehouseCapacitySnapshotRepository.class);
    WarehouseCapacityCommandReceiptRepository receipts =
        mock(WarehouseCapacityCommandReceiptRepository.class);
    WarehouseCapacitySnapshotResponseMapper mapper =
        mock(WarehouseCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    WarehouseCapacitySnapshotService service =
        new WarehouseCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK, new ObjectMapper());
    ReplacePlanningCapacitySnapshotRequest activeRequest =
        request(2, "b".repeat(64), 1);
    WarehouseCapacitySnapshot active =
        WarehouseCapacitySnapshot.create(
            WAREHOUSE,
            activeRequest.sourceGeneration(),
            activeRequest.sourceRevision(),
            activeRequest.jobs().stream()
                .map(WarehouseCapacitySnapshotServiceTest::facts)
                .toList(),
            List.of(),
            List.of(),
            java.time.OffsetDateTime.now(CLOCK));
    when(receipts.findById(COMMAND)).thenReturn(Optional.empty());
    when(receipts
            .findFirstByWarehouseIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                WAREHOUSE, 1, "a".repeat(64)))
        .thenReturn(Optional.empty());
    when(repository.findByWarehouseIdForUpdate(WAREHOUSE)).thenReturn(Optional.of(active));

    assertThatThrownBy(
            () -> service.replace(WAREHOUSE, COMMAND, request(1, "a".repeat(64), 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo("PLANNING_CAPACITY_GENERATION_STALE"));
  }

  @Test
  void reappliesAFormerRevisionWhenItReturnsInANewerGeneration() {
    WarehouseCapacitySnapshotRepository repository = mock(WarehouseCapacitySnapshotRepository.class);
    WarehouseCapacityCommandReceiptRepository receipts =
        mock(WarehouseCapacityCommandReceiptRepository.class);
    WarehouseCapacitySnapshotResponseMapper mapper =
        mock(WarehouseCapacitySnapshotResponseMapper.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    WarehouseCapacitySnapshotService service =
        new WarehouseCapacitySnapshotService(
            repository, receipts, mapper, locks, capacityFence, CLOCK, new ObjectMapper());
    ReplacePlanningCapacitySnapshotRequest activeRequest = request(2, "b".repeat(64), 1);
    WarehouseCapacitySnapshot active =
        WarehouseCapacitySnapshot.create(
            WAREHOUSE,
            activeRequest.sourceGeneration(),
            activeRequest.sourceRevision(),
            activeRequest.jobs().stream()
                .map(WarehouseCapacitySnapshotServiceTest::facts)
                .toList(),
            List.of(),
            List.of(),
            java.time.OffsetDateTime.now(CLOCK));
    ReplacePlanningCapacitySnapshotRequest returning = request(3, "a".repeat(64), 1);
    when(receipts.findById(COMMAND)).thenReturn(Optional.empty());
    when(receipts
            .findFirstByWarehouseIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                WAREHOUSE, 3, returning.sourceRevision()))
        .thenReturn(Optional.empty());
    when(repository.findByWarehouseIdForUpdate(WAREHOUSE)).thenReturn(Optional.of(active));
    when(repository.saveAndFlush(active)).thenReturn(active);
    when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.replace(WAREHOUSE, COMMAND, returning);

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
                        PlanningCapacityTaskType.DELIVERY,
                        LocalDate.of(2026, 8, 28).plusDays(jobs - index),
                        BigDecimal.valueOf(55.7 + index / 100.0),
                        BigDecimal.valueOf(37.6 + index / 100.0),
                        1,
                        LocalTime.of(9, 0),
                        LocalTime.of(12, 0),
                        30,
                        true,
                        0,
                        true))
            .toList();
    return new ReplacePlanningCapacitySnapshotRequest(
        sourceGeneration, revision, values, List.of(), List.of());
  }

  private static WarehouseCapacityJob.Facts facts(PlanningCapacityJobRequest request) {
    return new WarehouseCapacityJob.Facts(
        request.sourceJobId(),
        WarehouseCapacityTaskType.DELIVERY,
        request.deliveryDate(),
        request.latitude(),
        request.longitude(),
        request.cabinCount(),
        request.windowStart(),
        request.windowEnd(),
        request.serviceMinutes(),
        request.trailerAccessAllowed(),
        request.priority(),
        request.mandatory());
  }
}
