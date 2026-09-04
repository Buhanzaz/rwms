package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers fail-closed representative slot decisions based on confirmed route capacity. */
class RepresentativeDeliverySlotPolicyTest {
  private static final UUID SERVED =
      UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 28);
  private final RepresentativeDeliverySlotPolicy policy = new RepresentativeDeliverySlotPolicy();

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
  }

  @Test
  void ordinaryWarehouseStillRejectsAnInfeasibleCandidate() {
    var decision =
        policy.evaluate(
            warehouse(SERVED, false),
            FRIDAY,
            CustomerDeliverySlotKind.DURING_DAY,
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            false);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.flexibleSupport()).isFalse();
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
  }

  @Test
  void activeIncomingSupportLinkDoesNotCreateCapacityForFullDayOffer() {
    var decision = fullDay(false);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.flexibleSupport()).isFalse();
  }

  @Test
  void representativeFullDayRequiresConfirmedFeasibleCapacity() {
    var decision = fullDay(true);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.flexibleSupport()).isFalse();
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
