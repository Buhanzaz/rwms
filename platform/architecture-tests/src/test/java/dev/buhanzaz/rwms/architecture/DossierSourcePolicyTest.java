package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DossierSourcePolicyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void currentDossierSourcesKeepTheStageNineBoundary() {
    Path root = Path.of(System.getProperty("rwms.root.dir"));

    assertDoesNotThrow(
        () -> DossierSourcePolicy.assertSafe(root.resolve("services/dossier-service")));
  }

  @Test
  void acceptsLocalJpaRepositoryAndReadProjectionMapper() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/dossier/repository/ActivityRepository.java",
        """
        package dev.buhanzaz.rwms.dossier.repository;

        interface ActivityRepository extends
            org.springframework.data.jpa.repository.JpaRepository<Activity, java.util.UUID> {}

        final class Activity {}
        """);
    write(
        "src/main/java/dev/buhanzaz/rwms/dossier/mapper/ActivityReadMapper.java",
        """
        package dev.buhanzaz.rwms.dossier.mapper;

        @org.mapstruct.Mapper
        interface ActivityReadMapper {
          Response toResponse(Projection source);

          record Projection(String id) {}
          record Response(String id) {}
        }
        """);

    assertDoesNotThrow(() -> DossierSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void rejectsAllJdbcNativeSqlAndSpringDataJdbcPersistence() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/dossier/repository/UnsafeRepository.java",
        """
        package dev.buhanzaz.rwms.dossier.repository;

        import java.sql.Connection;
        import jakarta.persistence.EntityManager;
        import org.springframework.data.jdbc.repository.query.Query;
        import org.springframework.jdbc.core.JdbcTemplate;

        interface UnsafeRepository extends org.springframework.data.repository.CrudRepository<Row, String> {
          @org.springframework.data.jpa.repository.Query(value = "select * from source_fact", nativeQuery = true)
          Object nativeRead();
        }

        final class Row {
          JdbcTemplate jdbc;
          Connection connection;
          EntityManager entityManager;
        }
        """);

    AssertionError failure =
        assertThrows(AssertionError.class, () -> DossierSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("never low-level JDBC"));
    assertTrue(failure.getMessage().contains("Spring Data JDBC"));
    assertTrue(failure.getMessage().contains("native JPA SQL"));
  }

  @Test
  void rejectsProducerModelProjectAndHttpClientDependencies() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/dossier/service/UnsafeSourceReader.java",
        """
        package dev.buhanzaz.rwms.dossier.service;

        import dev.buhanzaz.rwms.asset.domain.RentalItem;
        import org.springframework.web.client.RestClient;

        final class UnsafeSourceReader {
          private RentalItem item;
          private RestClient assetClient;
        }
        """);
    write(
        "build.gradle.kts",
        """
        dependencies {
          implementation(project(":services:asset-service"))
          implementation("org.springframework.boot:spring-boot-starter-webflux")
        }
        """);

    AssertionError failure =
        assertThrows(AssertionError.class, () -> DossierSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("another service implementation"));
    assertTrue(failure.getMessage().contains("producer HTTP API"));
    assertTrue(failure.getMessage().contains("another service project"));
    assertTrue(failure.getMessage().contains("synchronous source HTTP client"));
  }

  @Test
  void rejectsBrowserEvidenceAndEntityMutationMapper() throws Exception {
    write(
        "src/main/java/dev/buhanzaz/rwms/dossier/mapper/UnsafeMapper.java",
        """
        package dev.buhanzaz.rwms.dossier.mapper;

        @org.mapstruct.Mapper
        interface UnsafeMapper {
          String SOURCE = "old_db";

          void update(Request request, @org.mapstruct.MappingTarget Entity target);

          record Request(String id) {}
          final class Entity {}
        }
        """);

    AssertionError failure =
        assertThrows(AssertionError.class, () -> DossierSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("browser or legacy evidence"));
    assertTrue(failure.getMessage().contains("must not mutate mapping targets"));
  }

  private void write(String relativePath, String source) throws Exception {
    Path path = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
