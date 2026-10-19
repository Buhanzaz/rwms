package dev.buhanzaz.rwms.asset.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import org.junit.jupiter.api.Test;

class EquipmentCatalogItemMapperTest {
  private final EquipmentCatalogItemMapper mapper = new EquipmentCatalogItemMapperImpl();

  @Test
  void mapsEntityReadToApiResponse() {
    EquipmentCatalogItem item =
        EquipmentCatalogItem.create("Heater", EquipmentCategory.ELECTRICAL, "220 V");

    var response = mapper.toResponse(item);

    assertThat(response.name()).isEqualTo("Heater");
    assertThat(response.category()).isEqualTo(EquipmentCategory.ELECTRICAL);
    assertThat(response.active()).isTrue();
    assertThat(response.comment()).isEqualTo("220 V");
  }
}
