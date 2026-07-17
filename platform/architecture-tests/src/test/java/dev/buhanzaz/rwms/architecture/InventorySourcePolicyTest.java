package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventorySourcePolicyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void currentOrProspectiveInventorySourcesFollowTheBoundary() {
    var root = Path.of(System.getProperty("rwms.root.dir"));

    assertDoesNotThrow(
        () -> InventorySourcePolicy.assertSafe(root.resolve("services/inventory-service")));
  }

  @Test
  void acceptsConstructorInjectionAndReadOnlyMappingFixtures() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryReader.java",
        """
        package dev.buhanzaz.rwms.inventory.service;

        final class InventoryReader {
          private final ExpectedItemReader expectedItemReader;

          InventoryReader(ExpectedItemReader expectedItemReader) {
            this.expectedItemReader = expectedItemReader;
          }

          interface ExpectedItemReader {}
        }
        """);
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/mapping/ExpectedItemReadMapper.java",
        """
        package dev.buhanzaz.rwms.inventory.mapping;

        @org.mapstruct.Mapper
        interface ExpectedItemReadMapper {
          ExpectedItemResponse toResponse(ExpectedItemProjection source);

          record ExpectedItemProjection(String id) {}
          record ExpectedItemResponse(String id) {}
        }
        """);
    write(
        "build.gradle.kts",
        """
        dependencies {
            implementation(project(\":platform:technical-contracts\"))
        }
        """);

    assertDoesNotThrow(() -> InventorySourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void rejectsAnotherServiceSourceAndProjectDependencies() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/service/UnsafeInventoryReader.java",
        """
        package dev.buhanzaz.rwms.inventory.service;

        import dev.buhanzaz.rwms.asset.domain.RentalItem;

        final class UnsafeInventoryReader {
          private RentalItem item;
        }
        """);
    write(
        "build.gradle.kts",
        """
        dependencies {
            implementation(project(\":services:asset-service\"))
        }
        """);

    assertThrows(AssertionError.class, () -> InventorySourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void rejectsRequestToEntityMutationMapper() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/mapper/UnsafeRequestMapper.java",
        """
        package dev.buhanzaz.rwms.inventory.mapper;

        @org.mapstruct.Mapper
        interface UnsafeRequestMapper {
          void update(CreateInventoryRequest request,
              @org.mapstruct.MappingTarget InventorySessionEntity entity);

          record CreateInventoryRequest(String id) {}
          final class InventorySessionEntity {}
        }
        """);

    assertThrows(AssertionError.class, () -> InventorySourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void acceptsLowLevelSqlOnlyInAnExactTechnicalAdapter() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryEventStore.java",
        """
        package dev.buhanzaz.rwms.inventory.eventing;

        import org.springframework.jdbc.core.JdbcTemplate;

        final class InventoryEventStore {
          private final JdbcTemplate jdbc;

          InventoryEventStore(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
          }
        }
        """);

    assertDoesNotThrow(() -> InventorySourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void rejectsBusinessTableSqlEvenInsideAnAllowlistedTechnicalAdapter() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryMediaInboxProcessor.java",
        """
        package dev.buhanzaz.rwms.inventory.eventing;

        import org.springframework.jdbc.core.JdbcTemplate;

        final class InventoryMediaInboxProcessor {
          private final JdbcTemplate jdbc;

          InventoryMediaInboxProcessor(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
          }

          void unsafe() {
            jdbc.update("update inventory_media_fact_projection set media_status='READY'");
          }
        }
        """);

    AssertionError failure =
        assertThrows(AssertionError.class, () -> InventorySourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("typed JPA for inventory business tables"));
  }

  @Test
  void rejectsLowLevelSqlInBusinessAndUnlistedEventingClasses() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/service/UnsafeInventoryService.java",
        """
        package dev.buhanzaz.rwms.inventory.service;

        import java.sql.Connection;

        final class UnsafeInventoryService {
          private Connection connection;
        }
        """);
    write(
        "src/main/java/dev/buhanzaz/rwms/inventory/eventing/UnsafeEventingAdapter.java",
        """
        package dev.buhanzaz.rwms.inventory.eventing;

        import org.springframework.jdbc.core.JdbcClient;

        final class UnsafeEventingAdapter {
          private JdbcClient jdbc;
        }
        """);

    AssertionError failure =
        assertThrows(AssertionError.class, () -> InventorySourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("UnsafeInventoryService.java"));
    assertTrue(failure.getMessage().contains("UnsafeEventingAdapter.java"));
  }

  private void write(String relativePath, String source) throws Exception {
    var path = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
