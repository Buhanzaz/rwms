package dev.buhanzaz.rwms.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
