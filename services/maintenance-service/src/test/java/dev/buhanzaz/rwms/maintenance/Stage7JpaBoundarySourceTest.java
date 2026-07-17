package dev.buhanzaz.rwms.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Stage7JpaBoundarySourceTest {
  @Test
  void inventoryBoundaryBusinessSourcesCannotUseSpringJdbcOrEntityManagerSql() throws IOException {
    Path sourceRoot = projectRoot().resolve(
        "services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance");
    List<Path> sources = List.of(
        sourceRoot.resolve("api/MaintenanceInventoryController.java"),
        sourceRoot.resolve("service/InventoryMaintenanceService.java"),
        sourceRoot.resolve("service/InventoryRepairSourceOperationRegistrar.java"),
        sourceRoot.resolve("service/InventoryRepairReconciliationWriter.java"),
        sourceRoot.resolve("service/MaintenanceReconciliationStore.java"),
        sourceRoot.resolve("domain/MaintenanceReconciliation.java"),
        sourceRoot.resolve("repository/MaintenanceReconciliationRepository.java"),
        sourceRoot.resolve("repository/MaintenanceRepairRepository.java"));

    for (Path source : sources) {
      assertThat(Files.readString(source))
          .as(source.toString())
          .doesNotContain(
              "org.springframework.jdbc",
              "JdbcTemplate",
              "EntityManager",
              "createNativeQuery",
              "nativeQuery = true",
              "pg_advisory");
    }

    assertThat(
            Files.readString(
                sourceRoot.resolve("repository/MaintenanceReconciliationRepository.java")))
        .contains("org.springframework.data.jpa.repository.JpaRepository");

    assertThat(Files.readString(sourceRoot.resolve("service/MaintenanceApplicationService.java")))
        .doesNotContain("integration_reconciliation");
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Project root was not found");
    }
    return current;
  }
}
