package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityPriceZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityRestrictionZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityPriceZoneRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityRestrictionKind;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityRestrictionZoneRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityShiftRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityTaskType;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningGeoJsonMultiPolygon;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningCapacitySnapshotRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
/**
 * Proves generation-fenced, immutable-receipt warehouse replacement against the Flyway/JPA
 * PostgreSQL model, including an A-to-B-to-A source revision sequence.
 */
class WarehouseCapacitySnapshotPersistenceIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID SOURCE_JOB =
      UUID.fromString("00000000-0000-0000-0000-000000000602");
  private static final UUID SOURCE_PICKUP =
      UUID.fromString("00000000-0000-0000-0000-000000000610");
  private static final UUID SOURCE_SHIFT =
      UUID.fromString("00000000-0000-0000-0000-000000000611");
  private static final UUID SOURCE_PRICE_ZONE =
      UUID.fromString("00000000-0000-0000-0000-000000000612");
  private static final UUID SOURCE_RESTRICTION_ZONE =
      UUID.fromString("00000000-0000-0000-0000-000000000613");
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired WarehouseCapacitySnapshotService service;
  @Autowired WarehouseCapacitySnapshotRepository snapshots;
  @Autowired WarehouseCapacityCommandReceiptRepository receipts;
  @Autowired WarehouseCapacityJobRepository jobs;
  @Autowired WarehouseCapacityShiftRepository shifts;
  @Autowired WarehouseCapacityIsochroneTariffRepository isochroneTariffs;
  @Autowired WarehouseCapacityPriceZoneRepository priceZones;
  @Autowired WarehouseCapacityRestrictionZoneRepository restrictionZones;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void resetCapacityProjection() {
    jdbc.execute(
        "truncate table customer_warehouse_capacity_command_receipt, "
            + "customer_warehouse_capacity_snapshot cascade");
  }

  @Test
  void replacesOneWarehouseSnapshotAndReusesAStableSourceJobIdentity() {
    var created =
        service.replace(
            WAREHOUSE,
            UUID.fromString("00000000-0000-0000-0000-000000000604"),
            request(1, "a".repeat(64), LocalTime.of(9, 0), 1));
    var replaced =
        service.replace(
            WAREHOUSE,
            UUID.fromString("00000000-0000-0000-0000-000000000606"),
            request(2, "b".repeat(64), LocalTime.of(12, 0), 2));
    var returned =
        service.replace(
            WAREHOUSE,
            UUID.fromString("00000000-0000-0000-0000-000000000609"),
            request(3, "a".repeat(64), LocalTime.of(15, 0), 3));
    var delayedReplay =
        service.replace(
            WAREHOUSE,
            UUID.fromString("00000000-0000-0000-0000-000000000604"),
            request(1, "a".repeat(64), LocalTime.of(9, 0), 1));

    assertThatThrownBy(
            () ->
                service.replace(
                    WAREHOUSE,
                    UUID.fromString("00000000-0000-0000-0000-000000000608"),
                    request(1, "c".repeat(64), LocalTime.of(15, 0), 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo("PLANNING_CAPACITY_GENERATION_STALE"));

    assertThat(created.replayed()).isFalse();
    assertThat(created.jobCount()).isEqualTo(2);
    assertThat(created.shiftCount()).isEqualTo(1);
    assertThat(created.isochroneTariffCount()).isEqualTo(5);
    assertThat(created.priceZoneCount()).isEqualTo(1);
    assertThat(created.restrictionZoneCount()).isEqualTo(1);
    assertThat(replaced.replayed()).isFalse();
    assertThat(replaced.priceZoneCount()).isZero();
    assertThat(replaced.restrictionZoneCount()).isZero();
    assertThat(returned.replayed()).isFalse();
    assertThat(delayedReplay.replayed()).isTrue();
    assertThat(delayedReplay.sourceRevision()).isEqualTo("a".repeat(64));
    assertThat(replaced.version()).isGreaterThan(created.version());
    assertThat(returned.version()).isGreaterThan(replaced.version());
    assertThat(replaced.sourceGeneration()).isEqualTo(2);
    assertThat(returned.sourceGeneration()).isEqualTo(3);
    assertThat(snapshots.count()).isEqualTo(1);
    assertThat(snapshots.findAll())
        .extracting(snapshot -> snapshot.getSourceRevision())
        .containsExactly("a".repeat(64));
    assertThat(receipts.count()).isEqualTo(3);
    assertThat(jobs.findCapacityWorkload(WAREHOUSE, LocalDate.of(2026, 8, 29)))
        .hasSize(2)
        .filteredOn(job -> job.getSourceJobId().equals(SOURCE_JOB))
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.getSourceJobId()).isEqualTo(SOURCE_JOB);
              assertThat(job.getCabinCount()).isEqualTo(3);
              assertThat(job.getWindowStart()).isEqualTo(LocalTime.of(15, 0));
            });
    assertThat(jobs.findCapacityWorkload(WAREHOUSE, LocalDate.of(2026, 8, 29)))
        .filteredOn(job -> job.getSourceJobId().equals(SOURCE_PICKUP))
        .singleElement()
        .satisfies(
            pickup -> {
              assertThat(pickup.getTaskType().name()).isEqualTo("PICKUP");
              assertThat(pickup.isMandatory()).isFalse();
              assertThat(pickup.getServiceMinutes()).isEqualTo(60);
            });
    assertThat(shifts.findCapacityShifts(WAREHOUSE, LocalDate.of(2026, 8, 29)))
        .singleElement()
        .satisfies(
            shift -> {
              assertThat(shift.getSourceShiftId()).isEqualTo(SOURCE_SHIFT);
              assertThat(shift.getCabinCapacity()).isEqualTo(2);
            });
    assertThat(isochroneTariffs.findTariffs(WAREHOUSE))
        .extracting(tariff -> tariff.getTravelMinutes(), tariff -> tariff.getPriceRubles())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(60, 11_003L),
            org.assertj.core.groups.Tuple.tuple(120, 16_003L),
            org.assertj.core.groups.Tuple.tuple(180, 21_003L),
            org.assertj.core.groups.Tuple.tuple(240, 26_003L),
            org.assertj.core.groups.Tuple.tuple(300, 31_003L));
    assertThat(priceZones.findTariffZones(WAREHOUSE))
        .singleElement()
        .satisfies(
            zone -> {
              assertThat(zone.getSourceZoneId()).isEqualTo(SOURCE_PRICE_ZONE);
              assertThat(zone.getSourceZoneVersion()).isEqualTo(3);
              assertThat(zone.getDeliveryPriceRubles()).isEqualTo(9_003);
            });
    assertThat(restrictionZones.findRestrictionZones(WAREHOUSE))
        .singleElement()
        .satisfies(
            zone -> {
              assertThat(zone.getSourceZoneId()).isEqualTo(SOURCE_RESTRICTION_ZONE);
              assertThat(zone.getSourceZoneVersion()).isEqualTo(3);
              assertThat(zone.getKind().name()).isEqualTo("NO_TRAILER");
            });
  }

  private static ReplacePlanningCapacitySnapshotRequest request(
      long sourceGeneration, String revision, LocalTime windowStart, int cabinCount) {
    return new ReplacePlanningCapacitySnapshotRequest(
        sourceGeneration,
        revision,
        List.of(
            new PlanningCapacityJobRequest(
                SOURCE_JOB,
                PlanningCapacityTaskType.DELIVERY,
                LocalDate.of(2026, 8, 29),
                BigDecimal.valueOf(55.75),
                BigDecimal.valueOf(37.61),
                cabinCount,
                windowStart,
                windowStart.plusHours(3),
                30,
                true,
                0,
                true),
            new PlanningCapacityJobRequest(
                SOURCE_PICKUP,
                PlanningCapacityTaskType.PICKUP,
                LocalDate.of(2026, 8, 29),
                BigDecimal.valueOf(59.80),
                BigDecimal.valueOf(30.40),
                1,
                LocalTime.of(12, 0),
                LocalTime.of(18, 0),
                60,
                true,
                1,
                false)),
        List.of(
            new PlanningCapacityShiftRequest(
                SOURCE_SHIFT,
                LocalDate.of(2026, 8, 29),
                LocalTime.of(8, 0),
                LocalTime.of(20, 0),
                30,
                2)),
        List.of(
            new PlanningCapacityIsochroneTariff(60, 11_000L + sourceGeneration),
            new PlanningCapacityIsochroneTariff(120, 16_000L + sourceGeneration),
            new PlanningCapacityIsochroneTariff(180, 21_000L + sourceGeneration),
            new PlanningCapacityIsochroneTariff(240, 26_000L + sourceGeneration),
            new PlanningCapacityIsochroneTariff(300, 31_000L + sourceGeneration)),
        sourceGeneration == 2
            ? List.of()
            : List.of(
                new PlanningCapacityPriceZoneRequest(
                    SOURCE_PRICE_ZONE,
                    sourceGeneration,
                    9_000L + sourceGeneration,
                    4_000L + sourceGeneration,
                    geometry())),
        sourceGeneration == 2
            ? List.of()
            : List.of(
                new PlanningCapacityRestrictionZoneRequest(
                    SOURCE_RESTRICTION_ZONE,
                    sourceGeneration,
                    PlanningCapacityRestrictionKind.NO_TRAILER,
                    geometry())));
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
}
