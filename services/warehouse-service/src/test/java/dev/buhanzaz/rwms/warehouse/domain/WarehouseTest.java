package dev.buhanzaz.rwms.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WarehouseTest {

  @Test
  void createsTheCanonicalUppercaseWarehouseCode() {
    Warehouse warehouse =
        Warehouse.create(" wh_north-1 ", " Северный ", " Санкт-Петербург ", " ", ZoneId.of("Europe/Moscow"), null);

    assertThat(warehouse.getCode()).isEqualTo("WH_NORTH-1");
    assertThat(warehouse.getName()).isEqualTo("Северный");
    assertThat(warehouse.getCity()).isEqualTo("Санкт-Петербург");
    assertThat(warehouse.getAddress()).isNull();
    assertThat(warehouse.isActive()).isTrue();
  }
}
