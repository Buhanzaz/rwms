package dev.buhanzaz.rwms.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WarehouseTest {

  @Test
  void createsAWhitespaceFoldedDisplayNameAndUnicodeNormalizedIdentity() {
    Warehouse warehouse =
        Warehouse.create(
            " \u00a0СЕВЕРНЫЙ\t\n Склад\u00a0 ",
            " Санкт-Петербург ",
            " ",
            ZoneId.of("Europe/Moscow"),
            null);

    assertThat(warehouse.getName()).isEqualTo("СЕВЕРНЫЙ Склад");
    assertThat(warehouse.getNormalizedName()).isEqualTo("северный склад");
    assertThat(warehouse.getCity()).isEqualTo("Санкт-Петербург");
    assertThat(warehouse.getAddress()).isNull();
    assertThat(warehouse.isActive()).isTrue();
  }

  @Test
  void acceptsCanonicalIanaTimezoneButRejectsFixedOffsetAliases() {
    assertThat(Warehouse.requireCanonicalTimeZone("Europe/Samara").getId())
        .isEqualTo("Europe/Samara");
    assertThatThrownBy(() -> Warehouse.requireCanonicalTimeZone("+04:00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical IANA");
  }

  @Test
  void lifecycleIsOneWayAndSeparatesIncomingFromOutgoingAdmission() {
    Warehouse warehouse =
        Warehouse.create("Lifecycle", "Москва", null, ZoneId.of("Europe/Moscow"), null);

    assertThat(warehouse.getLifecycleState()).isEqualTo(WarehouseLifecycleState.ACTIVE);
    assertThat(warehouse.isActive()).isTrue();
    assertThat(warehouse.allowsIncomingOperations()).isTrue();
    assertThat(warehouse.allowsOutgoingOperations()).isTrue();
    assertThat(warehouse.startDraining()).isTrue();
    assertThat(warehouse.getLifecycleState()).isEqualTo(WarehouseLifecycleState.DRAINING);
    assertThat(warehouse.isActive()).isFalse();
    assertThat(warehouse.allowsIncomingOperations()).isFalse();
    assertThat(warehouse.allowsOutgoingOperations()).isTrue();
    assertThat(warehouse.startDraining()).isFalse();
    assertThat(warehouse.completeInactivation()).isTrue();
    assertThat(warehouse.getLifecycleState()).isEqualTo(WarehouseLifecycleState.INACTIVE);
    assertThat(warehouse.allowsIncomingOperations()).isFalse();
    assertThat(warehouse.allowsOutgoingOperations()).isFalse();
    assertThat(warehouse.completeInactivation()).isFalse();
  }
}
