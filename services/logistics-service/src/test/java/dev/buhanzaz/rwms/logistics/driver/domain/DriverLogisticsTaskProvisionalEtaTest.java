package dev.buhanzaz.rwms.logistics.driver.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Covers the bounded lifecycle of an approximate planner-owned future-task preview. */
class DriverLogisticsTaskProvisionalEtaTest {
  @Test
  void newerPlanMayReplaceOrClearThePreviewButOlderPlanCannot() {
    DriverLogisticsTask task = sharedTask();
    UUID firstPlan = UUID.randomUUID();
    OffsetDateTime firstEta = OffsetDateTime.parse("2026-09-04T09:30:00Z");
    task.setProvisionalEta(firstEta, firstPlan, 5);

    assertThatThrownBy(
            () ->
                task.replaceProvisionalEta(
                    task.getVersion(), firstEta.plusMinutes(5), firstPlan, 4))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("backwards");

    UUID newerPlan = UUID.randomUUID();
    task.replaceProvisionalEta(task.getVersion(), firstEta.plusMinutes(15), newerPlan, 1);
    assertThat(task.getProvisionalEta()).isEqualTo(firstEta.plusMinutes(15));
    assertThat(task.getProvisionalEtaSourcePlanId()).isEqualTo(newerPlan);
    assertThat(task.getProvisionalEtaSourcePlanVersion()).isEqualTo(1L);

    ReflectionTestUtils.setField(task, "version", 9L);
    assertThatThrownBy(() -> task.replaceProvisionalEta(8, firstEta.plusMinutes(20), newerPlan, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("task version");

    task.replaceProvisionalEta(task.getVersion(), null, newerPlan, 2);
    assertThat(task.getProvisionalEta()).isNull();
    assertThat(task.getProvisionalEtaSourcePlanId()).isNull();
    assertThat(task.getProvisionalEtaSourcePlanVersion()).isNull();
  }

  @Test
  void claimAssignmentStartAndCancellationClearThePreview() {
    OffsetDateTime eta = OffsetDateTime.parse("2026-09-04T09:30:00Z");

    DriverLogisticsTask assigned = sharedTask();
    assigned.setProvisionalEta(eta, UUID.randomUUID(), 1);
    assigned.observeAudience(DriverTaskAudienceMode.ASSIGNED_DRIVER, UUID.randomUUID(), "Водитель");
    assertThat(assigned.getProvisionalEta()).isNull();

    DriverLogisticsTask started = sharedTask();
    started.setProvisionalEta(eta, UUID.randomUUID(), 1);
    started.registerBoardTask(UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "CURRENT", null);
    assertThat(started.getProvisionalEta()).isNull();

    DriverLogisticsTask cancelled = sharedTask();
    cancelled.setProvisionalEta(eta, UUID.randomUUID(), 1);
    cancelled.cancelBeforeExternalRegistration();
    assertThat(cancelled.getProvisionalEta()).isNull();
  }

  private static DriverLogisticsTask sharedTask() {
    return DriverLogisticsTask.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        DriverTaskSourceType.LOGISTICS_DOCUMENT,
        UUID.randomUUID(),
        DriverTaskKind.SHIPMENT,
        DriverTaskPlanningMode.FIXED_DATE,
        LocalDate.of(2026, 9, 4),
        3,
        null,
        "БЫТ-101",
        UUID.randomUUID(),
        DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
        null,
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
  }
}
