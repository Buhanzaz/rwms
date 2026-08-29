package dev.buhanzaz.rwms.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
    assertThat(warehouse.isRepresentative()).isFalse();
  }

  @Test
  void representativeCharacteristicChangesWithoutAffectingLifecycleAdmission() {
    Warehouse warehouse =
        Warehouse.create(
            "Великий Новгород",
            "Великий Новгород",
            null,
            ZoneId.of("Europe/Moscow"),
            null,
            true);

    assertThat(warehouse.isRepresentative()).isTrue();
    assertThat(warehouse.isActive()).isTrue();
    assertThat(warehouse.allowsIncomingOperations()).isTrue();
    assertThat(warehouse.allowsOutgoingOperations()).isTrue();
    assertThat(
            warehouse.replace(
                warehouse.getName(),
                warehouse.getCity(),
                warehouse.getAddress(),
                warehouse.getSortOrder(),
                false))
        .isEqualTo(Warehouse.Mutation.CHANGED);
    assertThat(warehouse.isRepresentative()).isFalse();
    assertThat(warehouse.getLifecycleState()).isEqualTo(WarehouseLifecycleState.ACTIVE);
  }

  @Test
  void coordinatesAreAnOptionalValidatedAllOrNonePair() {
    Warehouse warehouse =
        Warehouse.create(
            "Regional",
            "Регион",
            null,
            new BigDecimal("58.521475"),
            new BigDecimal("31.275475"),
            ZoneId.of("Europe/Moscow"),
            null,
            true);

    assertThat(warehouse.getAddress()).isNull();
    assertThat(warehouse.getLatitude()).isEqualByComparingTo("58.521475");
    assertThat(warehouse.getLongitude()).isEqualByComparingTo("31.275475");
    assertThatThrownBy(
            () ->
                Warehouse.create(
                    "Broken pair",
                    "Регион",
                    null,
                    new BigDecimal("58.500000"),
                    null,
                    ZoneId.of("Europe/Moscow"),
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supplied together");
    assertThatThrownBy(
            () ->
                Warehouse.create(
                    "Broken range",
                    "Регион",
                    null,
                    new BigDecimal("91.000000"),
                    new BigDecimal("31.000000"),
                    ZoneId.of("Europe/Moscow"),
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WGS84 range");
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
