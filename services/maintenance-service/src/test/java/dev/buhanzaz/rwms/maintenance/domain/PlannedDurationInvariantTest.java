package dev.buhanzaz.rwms.maintenance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlannedDurationInvariantTest {
  @Test
  void catalogWorkRequiresAPositiveDuration() {
    assertThatThrownBy(() -> catalogNode("WORK", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("durationMinutes");
    assertThatThrownBy(() -> catalogNode("WORK", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("durationMinutes");

    assertThat(catalogNode("WORK", 1).getDurationMinutes()).isOne();
    assertThat(catalogNode("MATERIAL", 0).getDurationMinutes()).isZero();
  }

  @Test
  void catalogMaterialNeverRetainsAComment() {
    assertThat(catalogNode("MATERIAL", 0, "устаревший комментарий").getComment()).isNull();
    assertThat(catalogNode("WORK", 1, "Комментарий к работе").getComment())
        .isEqualTo("Комментарий к работе");
  }

  @Test
  void estimateMaterialNeverRetainsAComment() {
    EstimateLine line =
        new EstimateLine(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            0,
            null,
            "MATERIAL",
            "Материал",
            "шт.",
            BigDecimal.ONE,
            0,
            0,
            null,
            null,
            "устаревший комментарий",
            "[]");

    assertThat(line.getComment()).isNull();
  }

  @Test
  void workerSnapshotsRejectMissingNormativesAndPreserveCanonicalDurations() {
    assertThatThrownBy(() ->
        new MaintenanceDependencyGateway.TaskWork(
            UUID.randomUUID(), "Work", 1, "шт.", 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("snapshot");
    assertThatThrownBy(() ->
        new MaintenanceDependencyGateway.TaskStage(
            UUID.randomUUID(),
            0,
            RepairStageKind.REPAIR_WORK,
            "Repair",
            UUID.randomUUID(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planned duration");

    MaintenanceDependencyGateway.TaskWork work =
        new MaintenanceDependencyGateway.TaskWork(
            UUID.randomUUID(), "Work", 1, "шт.", 45, null);
    MaintenanceDependencyGateway.TaskStage stage =
        new MaintenanceDependencyGateway.TaskStage(
            UUID.randomUUID(),
            0,
            RepairStageKind.REPAIR_WORK,
            "Repair",
            UUID.randomUUID(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            45);

    assertThat(work.durationMinutes()).isEqualTo(45);
    assertThat(stage.plannedDurationMinutes()).isEqualTo(45);
  }

  private static CatalogNode catalogNode(String nodeType, Integer durationMinutes) {
    return catalogNode(nodeType, durationMinutes, null);
  }

  private static CatalogNode catalogNode(
      String nodeType, Integer durationMinutes, String comment) {
    return new CatalogNode(
        UUID.randomUUID(),
        UUID.randomUUID(),
        nodeType,
        "Catalog position",
        true,
        null,
        false,
        null,
        null,
        "шт.",
        0L,
        durationMinutes,
        true,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        comment);
  }
}
