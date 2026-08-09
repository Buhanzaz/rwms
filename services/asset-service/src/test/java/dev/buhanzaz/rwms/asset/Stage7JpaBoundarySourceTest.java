package dev.buhanzaz.rwms.asset;

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
        "services/asset-service/src/main/java/dev/buhanzaz/rwms/asset");
    List<Path> sources = List.of(
        sourceRoot.resolve("api/InventoryAssetController.java"),
        sourceRoot.resolve("service/InventoryAssetService.java"),
        sourceRoot.resolve("service/InventoryAssetBoundaryRegistrar.java"),
        sourceRoot.resolve("service/InventoryAssetCaptureService.java"),
        sourceRoot.resolve("service/InventoryAssetProjectionService.java"),
        sourceRoot.resolve("service/InventoryFurnitureReconciliationService.java"),
        sourceRoot.resolve("service/InventoryAssetSourceService.java"));
    Path snapshotTransaction =
        sourceRoot.resolve("service/InventoryAssetSnapshotTransaction.java");

    for (Path source : sources) {
      assertThat(Files.readString(source))
          .as(source.toString())
          .doesNotContain(
              "org.springframework.jdbc",
              "JdbcTemplate",
              "EntityManager",
              "createNativeQuery",
              "pg_advisory");
    }

    assertThat(Files.readString(snapshotTransaction))
        .contains(
            "TransactionDefinition.PROPAGATION_REQUIRES_NEW",
            "TransactionDefinition.ISOLATION_REPEATABLE_READ",
            "captureSnapshotTransaction.setReadOnly(true)")
        .doesNotContain(
            "org.springframework.jdbc",
            "JdbcTemplate",
            "EntityManager",
            "createNativeQuery",
            "pg_advisory",
            "nativeQuery",
            "lock table");
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
