package dev.buhanzaz.rwms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InventoryPersistenceSourcePolicyTest {
  private static final Path JAVA = Path.of("src/main/java/dev/buhanzaz/rwms/inventory");
  private static final Set<String> LOW_LEVEL_SQL_ADAPTERS =
      Set.of(
          "eventing/InventoryDeadLetterRelay.java",
          "eventing/InventoryDeadLetterStore.java",
          "eventing/InventoryEventStore.java",
          "eventing/InventoryMediaInboxProcessor.java",
          "eventing/InventoryMediaRetryStore.java",
          "eventing/InventoryOutboxStore.java");
  private static final Pattern BUSINESS_TABLE_SQL =
      Pattern.compile(
          "(?is)\\b(?:from|join|into|update|delete\\s+from|merge\\s+into)\\s+(?:public\\.)?"
              + "(?:inventory_session|inventory_finding|inventory_media_fact_projection)\\b");

  @Test
  void onlySixExactTechnicalEventingAdaptersMayUseLowLevelSql() throws IOException {
    Set<String> lowLevelSqlUsers = new TreeSet<>();
    try (Stream<Path> files = Files.walk(JAVA)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        if (usesLowLevelSql(source)) {
          lowLevelSqlUsers.add(JAVA.relativize(file).toString().replace('\\', '/'));
        }
      }
    }
    assertThat(lowLevelSqlUsers)
        .as(
            "Only exact CAS/outbox/inbox/checkpoint/DLT adapters may use Spring JDBC or java.sql")
        .containsExactlyInAnyOrderElementsOf(LOW_LEVEL_SQL_ADAPTERS);
  }

  @Test
  void repositoryPackageUsesSpringDataJpa() throws IOException {
    try (Stream<Path> files = Files.walk(JAVA.resolve("repository"))) {
      for (Path file : files.filter(path -> path.toString().endsWith("Repository.java")).toList()) {
        assertThat(Files.readString(file))
            .as("Inventory repositories must be typed Spring Data JPA repositories: %s", file)
            .contains("org.springframework.data.jpa");
      }
    }
  }

  @Test
  void technicalSqlAdaptersCannotReadOrMutateInventoryBusinessTables() throws IOException {
    for (String relative : LOW_LEVEL_SQL_ADAPTERS) {
      Path source = JAVA.resolve(relative);
      assertThat(BUSINESS_TABLE_SQL.matcher(Files.readString(source)).find())
          .as("Technical SQL adapter must use typed JPA for business tables: %s", source)
          .isFalse();
    }
  }

  private static boolean usesLowLevelSql(String source) {
    return source.contains("org.springframework.jdbc")
        || source.contains("JdbcTemplate")
        || source.contains("JdbcClient")
        || source.contains("java.sql");
  }
}
