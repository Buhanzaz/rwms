package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseSupportLink;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers representative-only full-day fallback decisions against owner-held support calendars. */
class RepresentativeDeliverySlotPolicyTest {
  private static final UUID SERVED =
      UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID SUPPORT =
      UUID.fromString("00000000-0000-0000-0000-000000000602");
  private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 28);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final RepresentativeDeliverySlotPolicy policy =
      new RepresentativeDeliverySlotPolicy(dependencies);

  @Test
  void ordinaryWarehouseKeepsLocalCapacityDecisionWithoutReadingSupportTopology() {
    var decision =
        policy.evaluate(
            warehouse(SERVED, false),
            FRIDAY,
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            true);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.flexibleSupport()).isFalse();
    verify(dependencies, never()).listWarehouseSupportNetwork(SERVED);
  }

  @Test
  void representativeRejectsFixedWindowEvenWhenLocalCapacityIsFeasible() {
    var decision =
        policy.evaluate(
            warehouse(SERVED, true),
            FRIDAY,
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            true);

    assertThat(decision.allowed()).isFalse();
    verify(dependencies, never()).listWarehouseSupportNetwork(SERVED);
  }

  @Test
  void eligibleIncomingEdgeAllowsFlexibleFullDayWithoutLocalShift() {
    when(dependencies.listWarehouseSupportNetwork(SERVED))
        .thenReturn(List.of(link(Set.of(), Set.of(), Set.of(), true, true, false, 10, 16)));

    var decision = fullDay(false);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.flexibleSupport()).isTrue();
  }

  @Test
  void excludedDateOverridesExplicitAllowedDateAndMatchingWeekday() {
    when(dependencies.listWarehouseSupportNetwork(SERVED))
        .thenReturn(
            List.of(
                link(
                    Set.of(DayOfWeek.FRIDAY),
                    Set.of(FRIDAY),
                    Set.of(FRIDAY),
                    true,
                    true,
                    true,
                    9,
                    18)));

    assertThat(fullDay(false).allowed()).isFalse();
  }

  @Test
  void nonMatchingWeekdayWithoutExplicitDateRejectsFlexibleDay() {
    when(dependencies.listWarehouseSupportNetwork(SERVED))
        .thenReturn(
            List.of(
                link(
                    Set.of(DayOfWeek.MONDAY),
                    Set.of(),
                    Set.of(),
                    true,
                    true,
                    false,
                    9,
                    18)));

    assertThat(fullDay(false).allowed()).isFalse();
  }

  @Test
  void explicitAllowedDateOverridesWrongWeekdayButServiceHoursMustOverlap() {
    when(dependencies.listWarehouseSupportNetwork(SERVED))
        .thenReturn(
            List.of(
                link(
                    Set.of(DayOfWeek.MONDAY),
                    Set.of(FRIDAY),
                    Set.of(),
                    false,
                    false,
                    true,
                    18,
                    20)));
    assertThat(fullDay(false).allowed()).isFalse();

    when(dependencies.listWarehouseSupportNetwork(SERVED))
        .thenReturn(
            List.of(
                link(
                    Set.of(DayOfWeek.MONDAY),
                    Set.of(FRIDAY),
                    Set.of(),
                    false,
                    false,
                    true,
                    17,
                    20)));

    assertThat(fullDay(false).allowed()).isTrue();
  }

  private RepresentativeDeliverySlotPolicy.Decision fullDay(boolean localCapacity) {
    return policy.evaluate(
        warehouse(SERVED, true),
        FRIDAY,
        CustomerDeliverySlotKind.DURING_DAY,
        LocalTime.of(9, 0),
        LocalTime.of(18, 0),
        localCapacity);
  }

  private static WarehouseSupportLink link(
      Set<DayOfWeek> weekdays,
      Set<LocalDate> allowedDates,
      Set<LocalDate> excludedDates,
      boolean allowDrivers,
      boolean allowVehicles,
      boolean contractorFallback,
      int serviceStart,
      int serviceEnd) {
    return new WarehouseSupportLink(
        UUID.randomUUID(),
        0,
        warehouse(SUPPORT, false),
        warehouse(SERVED, true),
        1,
        allowDrivers,
        allowVehicles,
        false,
        false,
        false,
        contractorFallback,
        weekdays,
        allowedDates,
        excludedDates,
        LocalTime.of(serviceStart, 0),
        LocalTime.of(serviceEnd, 0));
  }

  private static WarehouseIdentity warehouse(UUID id, boolean representative) {
    return new WarehouseIdentity(
        id,
        0,
        true,
        id.equals(SERVED) ? "Региональный склад" : "Опорный склад",
        "",
        null,
        null,
        null,
        "Europe/Moscow",
        representative);
  }
}
