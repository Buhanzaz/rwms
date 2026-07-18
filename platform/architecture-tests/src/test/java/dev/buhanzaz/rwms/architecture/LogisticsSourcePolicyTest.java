package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogisticsSourcePolicyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void currentLogisticsSourcesKeepTheStageEightBoundaryAndInfrastructure() {
    Path root = Path.of(System.getProperty("rwms.root.dir"));

    assertDoesNotThrow(
        () -> LogisticsSourcePolicy.assertSafe(root.resolve("services/logistics-service")));
  }

  @Test
  void acceptsLocalTransportAndReadOnlyMapperSources() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/integration/SafeGateway.java",
        """
        package dev.buhanzaz.rwms.logistics.integration;

        final class SafeGateway {
          private static final String SCOPE = "asset.logistics";
        }
        """);
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/mapper/SafeReadMapper.java",
        """
        package dev.buhanzaz.rwms.logistics.mapper;

        @org.mapstruct.Mapper
        interface SafeReadMapper {
          Response toResponse(Projection source);

          record Projection(String id) {}
          record Response(String id) {}
        }
        """);

    assertDoesNotThrow(() -> LogisticsSourcePolicy.assertSourceBoundarySafe(temporaryDirectory));
  }

  @Test
  void rejectsServicePanelLegacyAndProjectLeaks() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/service/UnsafeLogisticsService.java",
        """
        package dev.buhanzaz.rwms.logistics.service;

        import dev.buhanzaz.rwms.asset.domain.RentalItem;
        import dev.buhanzaz.rwms.panel.runtime.PanelStore;

        final class UnsafeLogisticsService {
          private RentalItem item;
          private PanelStore store;
        }
        """);
    write(
        "build.gradle.kts",
        """
        dependencies {
          implementation(project(":services:asset-service"))
        }
        """);

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> LogisticsSourcePolicy.assertSourceBoundarySafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("another service implementation"));
    assertTrue(failure.getMessage().contains("panel, browser, legacy"));
    assertTrue(failure.getMessage().contains("must not depend on another service project"));
  }

  @Test
  void rejectsBroadScopesAndRequestToEntityMutation() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/mapper/UnsafeScopeMapper.java",
        """
        package dev.buhanzaz.rwms.logistics.mapper;

        @org.mapstruct.Mapper
        interface UnsafeScopeMapper {
          String SCOPE = "asset.internal";

          void update(CreateReturnRequest request,
              @org.mapstruct.MappingTarget ReturnEntity entity);

          record CreateReturnRequest(String id) {}
          final class ReturnEntity {}
        }
        """);

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> LogisticsSourcePolicy.assertSourceBoundarySafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("forbidden broad or unowned scope"));
    assertTrue(failure.getMessage().contains("must not mutate mapping targets"));
  }

  @Test
  void rejectsForbiddenScopePassedToAScopeMethod() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/integration/UnsafeScopeGateway.java",
        """
        package dev.buhanzaz.rwms.logistics.integration;

        final class UnsafeScopeGateway {
          void requestScope(String scope) {}

          void unsafe() {
            requestScope("media.read");
          }
        }
        """);

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> LogisticsSourcePolicy.assertSourceBoundarySafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("forbidden broad or unowned scope"));
  }

  @Test
  void requiresLocalEventAndExactScopeInfrastructure() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/LogisticsServiceApplication.java",
        """
        package dev.buhanzaz.rwms.logistics;

        final class LogisticsServiceApplication {}
        """);

    AssertionError failure =
        assertThrows(AssertionError.class, () -> LogisticsSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("LogisticsEventStore.java"));
    assertTrue(failure.getMessage().contains("V1__logistics_schema.sql"));
  }

  @Test
  void rejectsLowLevelSqlOutsideTheNarrowTechnicalAdapters() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/logistics/service/UnsafeJdbcService.java",
        """
        package dev.buhanzaz.rwms.logistics.service;

        import org.springframework.jdbc.core.JdbcTemplate;

        final class UnsafeJdbcService {
          private JdbcTemplate jdbc;
        }
        """);

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> LogisticsSourcePolicy.assertSourceBoundarySafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("low-level SQL is restricted"));
  }

  private void write(String relativePath, String source) throws Exception {
    Path path = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
