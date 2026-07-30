package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RentalPassportSanitizerTest {
  @Test
  void removesLegacyFieldsRecursivelyAndKeepsCurrentPassportData() {
    Map<String, Object> sanitized =
        RentalPassportSanitizer.sanitize(
            Map.of(
                "legacyId",
                "spb-42",
                "legacyWarehouseId",
                "spb",
                "source",
                "old-panel-rental-items-v1",
                "locationNodeId",
                "node-42",
                "hasPhotos",
                false,
                "photoCount",
                0,
                "tenant",
                "ООО Строй",
                "price",
                45000,
                "details",
                Map.of(
                    "finish",
                    "ДВП",
                    "source",
                    "catalog",
                    "photoCount",
                    2,
                    "legacyNumber",
                    "БЫТ-042"),
                "options",
                List.of(
                    Map.of(
                        "name",
                        "Линолеум",
                        "source",
                        "old-panel-rental-items-v1",
                        "LEGACYSource",
                        "old"))));

    assertThat(sanitized)
        .containsEntry("tenant", "ООО Строй")
        .containsEntry("price", 45000)
        .doesNotContainKeys(
            "legacyId",
            "legacyWarehouseId",
            "source",
            "locationNodeId",
            "hasPhotos",
            "photoCount");
    Map<?, ?> details = (Map<?, ?>) sanitized.get("details");
    assertThat(details.get("finish")).isEqualTo("ДВП");
    assertThat(details.get("source")).isEqualTo("catalog");
    assertThat(details.containsKey("photoCount")).isFalse();
    assertThat(details.containsKey("legacyNumber")).isFalse();
    Map<?, ?> option = (Map<?, ?>) ((List<?>) sanitized.get("options")).getFirst();
    assertThat(option.get("name")).isEqualTo("Линолеум");
    assertThat(option.containsKey("source")).isFalse();
    assertThat(option.containsKey("LEGACYSource")).isFalse();
  }
}
