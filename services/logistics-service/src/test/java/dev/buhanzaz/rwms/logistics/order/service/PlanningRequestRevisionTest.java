package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies that every normalized dispatcher contact fact participates in planner revisioning. */
class PlanningRequestRevisionTest {
  @Test
  void clientTypeNameAndPhoneEachChangeTheSourceRevision() {
    RentalOrder order = mock(RentalOrder.class);
    OrderClient client = mock(OrderClient.class);
    when(order.getId()).thenReturn(UUID.randomUUID());
    when(order.getVersion()).thenReturn(7L);
    when(order.getOrderNumber()).thenReturn("ORD-7");
    when(order.getClient()).thenReturn(client);
    when(client.getDisplayName()).thenReturn("ИП Петров");
    when(order.getDeliveryAddress()).thenReturn("Великий Новгород");
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-09-01T09:00:00Z"));

    String base = revision(order, ClientType.SOLE_PROPRIETOR, "Пётр", "+79990000001");

    assertThat(revision(order, ClientType.LEGAL_ENTITY, "Пётр", "+79990000001")).isNotEqualTo(base);
    assertThat(revision(order, ClientType.SOLE_PROPRIETOR, "Анна", "+79990000001"))
        .isNotEqualTo(base);
    assertThat(revision(order, ClientType.SOLE_PROPRIETOR, "Пётр", "+79990000002"))
        .isNotEqualTo(base);
  }

  private static String revision(
      RentalOrder order, ClientType clientType, String contactName, String contactPhone) {
    return PlanningRequestRevision.sha256(
        order, List.of(), List.of(), null, null, null, clientType, contactName, contactPhone);
  }
}
