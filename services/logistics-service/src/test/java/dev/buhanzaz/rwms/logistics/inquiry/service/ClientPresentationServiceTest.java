package dev.buhanzaz.rwms.logistics.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientPresentationServiceTest {
  @Test
  void publicPassportKeepsCharacteristicsAndRemovesTechnicalFieldsRecursively() {
    Map<String, Object> result =
        ClientPresentationService.publicPassport(
            Map.ofEntries(
                Map.entry("dimensions", "6 × 2,4"),
                Map.entry("legacyId", "spb-42"),
                Map.entry("legacyWarehouseId", "spb"),
                Map.entry("legacyNumber", "БЫТ-042"),
                Map.entry("source", "old-panel-rental-items-v1"),
                Map.entry("locationNodeId", "node-42"),
                Map.entry("hasPhotos", false),
                Map.entry("photoCount", 0),
                Map.entry("mainPhotoUrl", "https://old.invalid/42.jpg"),
                Map.entry("previewPhotoUrls", List.of("https://old.invalid/42-small.jpg")),
                Map.entry("authorAction", "manager-edit"),
                Map.entry(
                    "finish",
                    Map.of(
                        "wall",
                        "ДВП",
                        "updatedBy",
                        "internal-user",
                        "LegacyMaterialId",
                        "old-material",
                        "photoCount",
                        3,
                        "source",
                        "catalog")),
                Map.entry(
                    "options",
                    List.of(
                        Map.of(
                            "name",
                            "линолеум",
                            "source",
                            "old-panel-rental-items-v1",
                            "auditVersion",
                            8,
                            "legacyOptionId",
                            "old-option")))));

    assertThat(result)
        .containsEntry("dimensions", "6 × 2,4")
        .doesNotContainKeys(
            "legacyId",
            "legacyWarehouseId",
            "legacyNumber",
            "source",
            "locationNodeId",
            "hasPhotos",
            "photoCount",
            "mainPhotoUrl",
            "previewPhotoUrls",
            "authorAction");
    Map<?, ?> finish = (Map<?, ?>) result.get("finish");
    assertThat(finish.get("wall")).isEqualTo("ДВП");
    assertThat(finish.get("source")).isEqualTo("catalog");
    assertThat(finish.containsKey("updatedBy")).isFalse();
    assertThat(finish.containsKey("LegacyMaterialId")).isFalse();
    assertThat(finish.containsKey("photoCount")).isFalse();
    Map<?, ?> option = (Map<?, ?>) ((List<?>) result.get("options")).getFirst();
    assertThat(option.get("name")).isEqualTo("линолеум");
    assertThat(option.containsKey("source")).isFalse();
    assertThat(option.containsKey("auditVersion")).isFalse();
    assertThat(option.containsKey("legacyOptionId")).isFalse();
  }
}
