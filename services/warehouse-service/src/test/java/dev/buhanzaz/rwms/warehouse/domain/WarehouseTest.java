package dev.buhanzaz.rwms.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WarehouseTest {

  @Test
  void createsTheCanonicalUppercaseWarehouseCode() {
    Warehouse warehouse =
        Warehouse.create(" Северный ", " Санкт-Петербург ", " ", ZoneId.of("Europe/Moscow"), null);

    assertThat(warehouse.getName()).isEqualTo("Северный");
    assertThat(warehouse.getCity()).isEqualTo("Санкт-Петербург");
    assertThat(warehouse.getAddress()).isNull();
    assertThat(warehouse.isActive()).isTrue();
  }
}
