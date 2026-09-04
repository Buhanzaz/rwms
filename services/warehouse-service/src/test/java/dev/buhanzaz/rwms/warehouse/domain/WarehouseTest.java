package dev.buhanzaz.rwms.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WarehouseTest {

  @Test
  void createsWhitespaceFoldedIdentityAndDefaultMainWarehouseClassification() {
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
    assertThat(warehouse.isProduction()).isFalse();
    assertThat(warehouse.isMainWarehouse()).isTrue();
    assertThat(warehouse.isRepresentative()).isFalse();
  }

  @Test
  void keepsProductionAndMainClassificationsIndependentAndReservesParentsForRepresentatives() {
    Warehouse dualPurpose =
        Warehouse.create(
            "Москва",
            "Москва",
            null,
            null,
            null,
            ZoneId.of("Europe/Moscow"),
            null,
            true,
            true,
            null);
    assertThat(dualPurpose.isProduction()).isTrue();
    assertThat(dualPurpose.isMainWarehouse()).isTrue();
    assertThat(dualPurpose.isRepresentative()).isFalse();

    UUID parentId = UUID.randomUUID();
    Warehouse representative =
        Warehouse.create(
            "Тверь",
            "Тверь",
            null,
            null,
            null,
            ZoneId.of("Europe/Moscow"),
            null,
            false,
            false,
            parentId);
    assertThat(representative.isProduction()).isFalse();
    assertThat(representative.isMainWarehouse()).isFalse();
    assertThat(representative.isRepresentative()).isTrue();
    assertThat(representative.getRepresentativeParentWarehouseId()).isEqualTo(parentId);
  }

  @Test
  void rejectsInvalidClassificationShapes() {
    assertThatThrownBy(
            () ->
                Warehouse.create(
                    "Без классификации",
                    "Москва",
                    null,
                    null,
                    null,
                    ZoneId.of("Europe/Moscow"),
                    null,
                    false,
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be production");
    assertThatThrownBy(
            () ->
                Warehouse.create(
                    "Представительство с флагом",
                    "Москва",
                    null,
                    null,
                    null,
                    ZoneId.of("Europe/Moscow"),
                    null,
                    true,
                    false,
                    UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot also be production");
  }

  @Test
  void convertsRepresentativeToProductionWithTheCurrentClassificationShape() {
    Warehouse warehouse =
        Warehouse.create(
            "Великий Новгород",
            "Великий Новгород",
            null,
            null,
            null,
            ZoneId.of("Europe/Moscow"),
            null,
            false,
            false,
            UUID.randomUUID());

    assertThat(
            warehouse.replace(
                warehouse.getName(),
                warehouse.getCity(),
                warehouse.getAddress(),
                warehouse.getLatitude(),
                warehouse.getLongitude(),
                warehouse.getSortOrder(),
                true,
                false,
                null))
        .isEqualTo(Warehouse.Mutation.CHANGED);
    assertThat(warehouse.isRepresentative()).isFalse();
    assertThat(warehouse.isProduction()).isTrue();
    assertThat(warehouse.isMainWarehouse()).isFalse();
    assertThat(warehouse.getRepresentativeParentWarehouseId()).isNull();
  }

  @Test
  void rejectsSelfRepresentativeParentOnceIdentityIsAssigned() {
    UUID warehouseId = UUID.randomUUID();
    Warehouse warehouse =
        Warehouse.create(
            "Сам себе родитель",
            "Москва",
            null,
            null,
            null,
            ZoneId.of("Europe/Moscow"),
            null,
            false,
            false,
            warehouseId);
    ReflectionTestUtils.setField(warehouse, "id", warehouseId);

    assertThatThrownBy(warehouse::beforeInsert)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be its own representative parent");
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
            false,
            true,
            null);
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
                    false,
                    true,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supplied together");
    assertThatThrownBy(
            () ->
                Warehouse.create(
                    "Unset coordinates",
                    "Регион",
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    ZoneId.of("Europe/Moscow"),
                    null,
                    false,
                    true,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not both be zero");
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

    assertThat(warehouse.startDraining()).isTrue();
    assertThat(warehouse.getLifecycleState()).isEqualTo(WarehouseLifecycleState.DRAINING);
    assertThat(warehouse.allowsIncomingOperations()).isFalse();
    assertThat(warehouse.allowsOutgoingOperations()).isTrue();
    assertThat(warehouse.completeInactivation()).isTrue();
    assertThat(warehouse.getLifecycleState()).isEqualTo(WarehouseLifecycleState.INACTIVE);
    assertThat(warehouse.allowsIncomingOperations()).isFalse();
    assertThat(warehouse.allowsOutgoingOperations()).isFalse();
  }
}
