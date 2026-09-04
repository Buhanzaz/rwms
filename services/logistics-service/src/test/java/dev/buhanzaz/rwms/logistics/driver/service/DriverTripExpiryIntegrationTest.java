package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves V97 persistence and bounded expiry/history queries against PostgreSQL, not mocks. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DriverTripExpiryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired DriverLogisticsTaskRepository tasks;
  @Autowired DriverTaskWorkflowStore workflow;
  @Autowired DriverTaskService service;

  @Test
  void committedIntentSurvivesReloadAndCancelledHistoryIsWarehouseScoped() {
    UUID warehouse = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 9, 4);
    var task =
        DriverLogisticsTask.create(
            warehouse,
            UUID.randomUUID(),
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            UUID.randomUUID(),
            DriverTaskKind.SHIPMENT,
            DriverTaskPlanningMode.FIXED_DATE,
            date,
            3,
            null,
            "Б-17",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    UUID boardTask = UUID.randomUUID();
    UUID entry = UUID.randomUUID();
    task.registerBoardTask(boardTask, 0, entry, "WAITING", "SCHEDULED", null);
    tasks.saveAndFlush(task);
    var page = PageRequest.of(0, 50);
    assertThat(tasks.findOverdueTripIds(warehouse, date, page)).isEmpty();
    assertThat(tasks.findOverdueTripIds(warehouse, date.plusDays(1), page))
        .containsExactly(task.getId());
    workflow.requestTripExpiry(task.getId(), date.plusDays(1));
    var loaded = tasks.findById(task.getId()).orElseThrow();
    assertThat(loaded.getTripExpiryRequestedAt()).isNotNull();
    assertThat(workflow.nextWork(loaded.getId()).orElseThrow())
        .isInstanceOf(DriverTaskWorkflowStore.ExpiryWork.class);
    assertThat(tasks.findOverdueTripIds(warehouse, date.plusDays(1), page)).isEmpty();
    loaded.observeBoardTask(
        boardTask, 1, entry, "CANCELLED", date, "SCHEDULED", "CANCELLED", OffsetDateTime.now());
    tasks.saveAndFlush(loaded);
    assertThat(
            tasks
                .findByWarehouseIdAndStateAndTripExpiryRequestedAtIsNotNullOrderByUpdatedAtDescIdDesc(
                    warehouse, DriverTaskState.CANCELLED, page))
        .extracting(DriverLogisticsTask::getFailureCode)
        .containsExactly("TRIP_DAY_EXPIRED");
    var manager =
        new OrderActor(
            UUID.randomUUID(),
            "RENTAL_MANAGER",
            "Manager",
            java.util.Set.of(warehouse),
            java.util.Set.of(),
            false,
            false,
            false,
            true);
    assertThat(service.expiredTripsForManager(manager))
        .extracting(notice -> notice.id())
        .containsExactly(task.getId());
    var otherWarehouse =
        new OrderActor(
            UUID.randomUUID(),
            "RENTAL_MANAGER",
            "Manager",
            java.util.Set.of(UUID.randomUUID()),
            java.util.Set.of(),
            false,
            false,
            false,
            true);
    assertThat(service.expiredTripsForManager(otherWarehouse)).isEmpty();
    var noRental =
        new OrderActor(
            UUID.randomUUID(),
            "RENTAL_MANAGER",
            "Manager",
            java.util.Set.of(warehouse),
            java.util.Set.of(),
            false,
            false,
            false,
            false);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.expiredTripsForManager(noRental))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(
            tasks
                .findByWarehouseIdAndStateAndTripExpiryRequestedAtIsNotNullOrderByUpdatedAtDescIdDesc(
                    UUID.randomUUID(), DriverTaskState.CANCELLED, page))
        .isEmpty();
  }
}
