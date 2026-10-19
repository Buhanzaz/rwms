package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.mapper.WarehouseCapacitySnapshotResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityPriceZoneRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityRestrictionKind;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityRestrictionZoneRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityTaskType;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningGeoJsonMultiPolygon;
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
  private static final UUID PRICE_ZONE =
      UUID.fromString("00000000-0000-0000-0000-000000000404");
  private static final UUID RESTRICTION_ZONE =
      UUID.fromString("00000000-0000-0000-0000-000000000405");
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
    ReplacePlanningCapacitySnapshotRequest request = requestWithZones(9_000);
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
                  snapshot.getIsochroneTariffs().size(),
                  snapshot.getPriceZones().size(),
                  snapshot.getRestrictionZones().size(),
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
                  receipt.getIsochroneTariffCount(),
                  receipt.getPriceZoneCount(),
                  receipt.getRestrictionZoneCount(),
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
    assertThat(snapshot.getIsochroneTariffs())
        .extracting(
            WarehouseCapacityIsochroneTariff::getTravelMinutes,
            WarehouseCapacityIsochroneTariff::getPriceRubles)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(60, 10_000L),
            org.assertj.core.groups.Tuple.tuple(120, 15_000L),
            org.assertj.core.groups.Tuple.tuple(180, 20_000L),
            org.assertj.core.groups.Tuple.tuple(240, 25_000L));
    assertThat(snapshot.getPriceZones())
        .singleElement()
        .satisfies(
            zone -> {
              assertThat(zone.getSourceZoneId()).isEqualTo(PRICE_ZONE);
              assertThat(zone.getDeliveryPriceRubles()).isEqualTo(9_000);
            });
    assertThat(snapshot.getRestrictionZones())
        .singleElement()
        .satisfies(zone -> assertThat(zone.getSourceZoneId()).isEqualTo(RESTRICTION_ZONE));
    assertThat(receiptCaptor.getValue().getPriceZoneCount()).isEqualTo(1);
    assertThat(receiptCaptor.getValue().getRestrictionZoneCount()).isEqualTo(1);

    when(receipts.findById(COMMAND)).thenReturn(Optional.of(receiptCaptor.getValue()));
    PlanningCapacitySnapshotResponse replayed = service.replace(WAREHOUSE, COMMAND, request);

    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.jobCount()).isEqualTo(2);
    assertThat(replayed.priceZoneCount()).isEqualTo(1);
    assertThat(replayed.restrictionZoneCount()).isEqualTo(1);
    verify(capacityFence).acquireWarehouseCapacity(WAREHOUSE);
  }

  @Test
  void normalizesOmittedPolicyArraysAndIncludesPolicyFactsInTheReplayChecksum() {
    ReplacePlanningCapacitySnapshotRequest omitted =
        new ReplacePlanningCapacitySnapshotRequest(
            1,
            "a".repeat(64),
            List.of(),
            List.of(),
            tariffs(),
            null,
            null);

    assertThat(omitted.priceZones()).isEmpty();
    assertThat(omitted.restrictionZones()).isEmpty();

    ReplacePlanningCapacitySnapshotRequest baseline = requestWithZones(9_000);
    ReplacePlanningCapacitySnapshotRequest changed = requestWithZones(9_500);
    assertThat(checksum(changed)).isNotEqualTo(checksum(baseline));
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
    List<PlanningCapacityJobRequest> sorted =
        original.jobs().stream()
            .sorted(java.util.Comparator.comparing(PlanningCapacityJobRequest::deliveryDate))
            .toList();
    WarehouseCapacitySnapshot snapshot =
        WarehouseCapacitySnapshot.create(
            WAREHOUSE,
            original.sourceGeneration(),
            original.sourceRevision(),
            sorted.stream().map(WarehouseCapacitySnapshotServiceTest::facts).toList(),
            List.of(),
            tariffFacts(),
            java.time.OffsetDateTime.now(CLOCK));
    WarehouseCapacityCommandReceipt receipt =
        WarehouseCapacityCommandReceipt.applied(
            COMMAND,
            snapshot,
            WarehouseCapacityChecksum.sha256(
                WAREHOUSE, original, sorted, List.of(), original.isochroneTariffs()),
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
            tariffFacts(),
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
            tariffFacts(),
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

  @Test
  void rejectsMissingOrNonContiguousIsochroneTariffsAtTheTransportBoundary() {
    assertThatThrownBy(
            () ->
                new ReplacePlanningCapacitySnapshotRequest(
                    1, "a".repeat(64), List.of(), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires isochrone tariffs");

    assertThatThrownBy(
            () ->
                new ReplacePlanningCapacitySnapshotRequest(
                    1,
                    "a".repeat(64),
                    List.of(),
                    List.of(),
                    List.of(
                        new PlanningCapacityIsochroneTariff(60, 10_000),
                        new PlanningCapacityIsochroneTariff(180, 20_000))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contiguous hourly tiers");
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
        sourceGeneration, revision, values, List.of(), tariffs());
  }

  private static ReplacePlanningCapacitySnapshotRequest requestWithZones(long specialPrice) {
    ReplacePlanningCapacitySnapshotRequest base = request("a".repeat(64), 2);
    return new ReplacePlanningCapacitySnapshotRequest(
        base.sourceGeneration(),
        base.sourceRevision(),
        base.jobs(),
        base.shifts(),
        base.isochroneTariffs(),
        List.of(
            new PlanningCapacityPriceZoneRequest(
                PRICE_ZONE, 3, specialPrice, 4_500, geometry())),
        List.of(
            new PlanningCapacityRestrictionZoneRequest(
                RESTRICTION_ZONE,
                7,
                PlanningCapacityRestrictionKind.NO_TRAILER,
                geometry())));
  }

  private static String checksum(ReplacePlanningCapacitySnapshotRequest request) {
    return WarehouseCapacityChecksum.sha256(
        WAREHOUSE,
        request,
        request.jobs().stream()
            .sorted(
                java.util.Comparator.comparing(PlanningCapacityJobRequest::deliveryDate)
                    .thenComparing(PlanningCapacityJobRequest::windowStart)
                    .thenComparing(PlanningCapacityJobRequest::sourceJobId))
            .toList(),
        request.shifts(),
        request.isochroneTariffs(),
        request.priceZones(),
        request.restrictionZones());
  }

  private static PlanningGeoJsonMultiPolygon geometry() {
    return new PlanningGeoJsonMultiPolygon(
        "MultiPolygon",
        List.of(
            List.of(
                List.of(
                    List.of(37.0, 55.0),
                    List.of(38.0, 55.0),
                    List.of(38.0, 56.0),
                    List.of(37.0, 55.0)))));
  }

  private static List<PlanningCapacityIsochroneTariff> tariffs() {
    return List.of(
        new PlanningCapacityIsochroneTariff(60, 10_000),
        new PlanningCapacityIsochroneTariff(120, 15_000),
        new PlanningCapacityIsochroneTariff(180, 20_000),
        new PlanningCapacityIsochroneTariff(240, 25_000));
  }

  private static List<WarehouseCapacityIsochroneTariff.Facts> tariffFacts() {
    return tariffs().stream()
        .map(
            tariff ->
                new WarehouseCapacityIsochroneTariff.Facts(
                    tariff.travelMinutes(), tariff.priceRubles()))
        .toList();
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
