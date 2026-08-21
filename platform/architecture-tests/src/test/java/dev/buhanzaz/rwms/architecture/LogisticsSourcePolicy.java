package dev.buhanzaz.rwms.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Source-level guard for the isolated Stage 8 bounded context. */
final class LogisticsSourcePolicy {
  private static final Pattern LOGISTICS_PACKAGE =
      Pattern.compile(
          "(?m)^\\s*package\\s+dev\\.buhanzaz\\.rwms\\.logistics(?:\\.[A-Za-z_$][A-Za-z0-9_$.]*)?\\s*;");
  private static final Pattern FORBIDDEN_SERVICE_IMPORT =
      Pattern.compile(
          "(?m)^\\s*import\\s+(?:static\\s+)?dev\\.buhanzaz\\.rwms\\.(?:auth|taskboard|warehouse|asset|maintenance|inventory|media)\\.");
  private static final Pattern FORBIDDEN_RUNTIME_IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?(?:dev\\.buhanzaz\\.rwms\\.)?(?:panel|browser|legacy)\\.");
  private static final Pattern FORBIDDEN_RUNTIME_REFERENCE =
      Pattern.compile("(?i)wms-panel-old|old_db|localstorage|indexeddb");
  private static final Pattern FORBIDDEN_SERVICE_PROJECT =
      Pattern.compile(
          "project\\s*\\([^)]*:services:(?:auth|task-board|warehouse|asset|maintenance|inventory|media)-service[^)]*\\)");
  private static final Pattern FORBIDDEN_SCOPE_LITERAL =
      Pattern.compile(
          "(?i)\\b[A-Za-z0-9_]*scope\\b\\s*(?:=|\\()\\s*\\\"(?:asset\\.internal|media(?!\\.logistics\\\")[A-Za-z0-9_.-]*|reservation[A-Za-z0-9_.-]*|company[A-Za-z0-9_.-]*|location(?:-?correction)?[A-Za-z0-9_.-]*)\\\"");
  private static final Pattern MAPPER = Pattern.compile("@(?:org\\.mapstruct\\.)?Mapper\\b");
  private static final Pattern MAPPING_TARGET =
      Pattern.compile("@(?:org\\.mapstruct\\.)?MappingTarget\\b");
  private static final Pattern LOW_LEVEL_SQL =
      Pattern.compile(
          "org\\.springframework\\.jdbc|\\bJdbcTemplate\\b|\\bJdbcClient\\b|java\\.sql\\.(?:Connection|Statement|ResultSet)");
  private static final Pattern NATIVE_JPA_QUERY =
      Pattern.compile("\\bnativeQuery\\s*=\\s*true\\b");
  private static final Pattern APPROVED_ADVISORY_LOCK_QUERY =
      Pattern.compile(
          "(?s)@(?:org\\.springframework\\.data\\.jpa\\.repository\\.)?Query\\s*\\(\\s*"
              + "value\\s*=\\s*\"\"\"\\s*select 1\\s+from \\(\\s*"
              + "select lock_key\\s+from unnest\\(string_to_array\\(cast\\(:lockKeys as text\\), chr\\(31\\)\\)\\) as keys\\(lock_key\\)\\s+"
              + "order by lock_key\\s+\\) ordered\\s+cross join lateral pg_advisory_xact_lock\\(\\s*"
              + "hashtextextended\\(cast\\(ordered\\.lock_key as text\\), 0\\)\\) ignored\\s*\"\"\"\\s*,\\s*"
              + "nativeQuery\\s*=\\s*true\\s*\\)");
  private static final String ADVISORY_LOCK_REPOSITORY =
      "dev/buhanzaz/rwms/logistics/repository/LogisticsIdempotencyRecordRepository.java";
  private static final String OAUTH_HTTP_TRANSPORT =
      "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsOAuthHttpTransport.java";
  private static final String SINGLETON_TOKEN_SCOPE_INVARIANT =
      "getAccessToken().getScopes().equals(Set.of(requiredScope))";
  private static final Pattern SCOPE_DECLARATION =
      Pattern.compile(
          "(?m)private\\s+static\\s+final\\s+String\\s+[A-Z_]*_SCOPE\\s*=\\s*\\\"([^\\\"]+)\\\"");
  private static final Map<String, Set<String>> REQUIRED_OWNER_SCOPES =
      Map.of(
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsWarehouseDependencyClient.java",
          Set.of(
              "warehouse.logistics",
              "warehouse.timezone.read",
              "warehouse.operation.mark",
              "warehouse.lifecycle.read",
              "warehouse.lifecycle.confirm"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsAssetOperationsDependencyClient.java",
          Set.of("asset.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsAssetOrderPresentationDependencyClient.java",
          Set.of("asset.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsMaintenanceDependencyClient.java",
          Set.of("maintenance.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsMediaDependencyClient.java",
          Set.of("media.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsTaskBoardDependencyClient.java",
          Set.of("task-board.logistics"));
  private static final Set<String> LOW_LEVEL_SQL_ADAPTERS =
      Set.of(
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsOutboxStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsRecoveryObservationStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsReplayVerifier.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsSanitizedDltStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboxProcessor.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboundStagingStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboundObservationStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboundGapRecoveryService.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsKafkaConsumerRecoveryMonitor.java",
          "dev/buhanzaz/rwms/logistics/inquiry/eventing/RentalInquiryBookedOutboxStore.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java");
  private static final Set<String> REQUIRED_INFRASTRUCTURE_SOURCES =
      Set.of(
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsOutboxStore.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsReplayVerifier.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsSanitizedDltStore.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsDependencyGateway.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java");
  private static final Set<String> REQUIRED_SCHEMA_TABLES =
      Set.of(
          "event_stream_head",
          "domain_event",
          "projection_checkpoint",
          "outbox_event",
          "inbox_message",
          "consumer_aggregate_checkpoint",
          "version_gap_quarantine",
          "sanitized_dead_letter");

  private LogisticsSourcePolicy() {}

  static void assertSafe(Path serviceRoot) throws IOException {
    assertSourceBoundarySafe(serviceRoot);
    if (!Files.exists(serviceRoot)) {
      return;
    }

    var violations = new ArrayList<String>();
    for (String requiredSource : REQUIRED_INFRASTRUCTURE_SOURCES) {
      if (!Files.isRegularFile(serviceRoot.resolve(requiredSource))) {
        violations.add(serviceRoot.resolve(requiredSource) + ": required local Stage 8 infrastructure is absent");
      }
    }

    Path migration = serviceRoot.resolve("src/main/resources/db/migration/V1__logistics_schema.sql");
    if (!Files.isRegularFile(migration)) {
      violations.add(migration + ": logistics Flyway V1 is required");
    } else {
      String source = read(migration);
      for (String table : REQUIRED_SCHEMA_TABLES) {
        if (!source.contains("CREATE TABLE public." + table)) {
          violations.add(migration + ": missing local " + table + " infrastructure");
        }
      }
    }

    assertExactDependencyScopes(serviceRoot, violations);

    failIfNeeded(violations);
  }

  static void assertSourceBoundarySafe(Path serviceRoot) throws IOException {
    if (!Files.exists(serviceRoot)) {
      return;
    }

    var violations = new ArrayList<String>();
    Path sourceRoot = serviceRoot.resolve("src/main/java");
    if (Files.isDirectory(sourceRoot)) {
      try (var files = Files.walk(sourceRoot)) {
        files
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(path -> inspectJava(sourceRoot, path, read(path), violations));
      }
    }

    Path buildFile = serviceRoot.resolve("build.gradle.kts");
    if (Files.isRegularFile(buildFile) && FORBIDDEN_SERVICE_PROJECT.matcher(read(buildFile)).find()) {
      violations.add(buildFile + ": logistics-service must not depend on another service project");
    }

    failIfNeeded(violations);
  }

  private static void inspectJava(
      Path sourceRoot, Path path, String source, List<String> violations) {
    if (!LOGISTICS_PACKAGE.matcher(source).find()) {
      violations.add(path + ": production source must use the logistics package root");
    }
    if (FORBIDDEN_SERVICE_IMPORT.matcher(source).find()) {
      violations.add(path + ": logistics source imports another service implementation");
    }
    if (FORBIDDEN_RUNTIME_IMPORT.matcher(source).find()
        || FORBIDDEN_RUNTIME_REFERENCE.matcher(source).find()) {
      violations.add(path + ": logistics source must not depend on panel, browser, legacy or browser-store runtime");
    }
    if (FORBIDDEN_SCOPE_LITERAL.matcher(source).find()) {
      violations.add(path + ": logistics source requests a forbidden broad or unowned scope");
    }

    String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
    if (LOW_LEVEL_SQL.matcher(source).find() && !LOW_LEVEL_SQL_ADAPTERS.contains(relative)) {
      violations.add(
          path
              + ": low-level SQL is restricted to exact technical event/recovery-observation, "
              + "inquiry-outbox, warehouse-admission, warehouse-blocker and warehouse-mark adapters");
    }
    long nativeQueries = NATIVE_JPA_QUERY.matcher(source).results().count();
    if (nativeQueries > 0
        && (!ADVISORY_LOCK_REPOSITORY.equals(relative)
            || nativeQueries != 1
            || !APPROVED_ADVISORY_LOCK_QUERY.matcher(source).find())) {
      violations.add(
          path
              + ": native JPA SQL is restricted to the exact transaction advisory-lock repository query");
    }
    if (MAPPER.matcher(source).find()) {
      if (!relative.contains("/mapper/") && !relative.contains("/mapping/")) {
        violations.add(path + ": MapStruct mapper must live in mapper or mapping package");
      }
      if (MAPPING_TARGET.matcher(source).find()) {
        violations.add(path + ": MapStruct mapper must not mutate mapping targets");
      }
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read " + path, exception);
    }
  }

  private static void assertExactDependencyScopes(Path serviceRoot, List<String> violations) {
    Path transport = serviceRoot.resolve(OAUTH_HTTP_TRANSPORT);
    if (!Files.isRegularFile(transport)) {
      violations.add(transport + ": singleton-token transport is absent");
    } else if (!read(transport).contains(SINGLETON_TOKEN_SCOPE_INVARIANT)) {
      violations.add(transport + ": transport must verify one exact receiver scope per token");
    }

    for (Map.Entry<String, Set<String>> owner : REQUIRED_OWNER_SCOPES.entrySet()) {
      Path source = serviceRoot.resolve(owner.getKey());
      if (!Files.isRegularFile(source)) {
        violations.add(source + ": exact dependency-owner client is absent");
        continue;
      }
      Set<String> actualScopes = declaredScopes(read(source));
      if (!owner.getValue().equals(actualScopes)) {
        violations.add(
            source
                + ": declared dependency scopes must equal "
                + owner.getValue()
                + " but were "
                + actualScopes);
      }
    }
  }

  private static Set<String> declaredScopes(String source) {
    var scopes = new java.util.HashSet<String>();
    var matcher = SCOPE_DECLARATION.matcher(source);
    while (matcher.find()) {
      scopes.add(matcher.group(1));
    }
    return Set.copyOf(scopes);
  }

  private static void failIfNeeded(List<String> violations) {
    if (!violations.isEmpty()) {
      throw new AssertionError(String.join(System.lineSeparator(), violations));
    }
  }
}
