package dev.buhanzaz.rwms.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class InventorySourcePolicy {
  private static final Pattern INVENTORY_PACKAGE =
      Pattern.compile(
          "(?m)^\\s*package\\s+dev\\.buhanzaz\\.rwms\\.inventory(?:\\.[A-Za-z_$][A-Za-z0-9_$.]*)?\\s*;");
  private static final Pattern FORBIDDEN_SERVICE_IMPORT =
      Pattern.compile(
          "(?m)^\\s*import\\s+(?:static\\s+)?dev\\.buhanzaz\\.rwms\\.(?:auth|taskboard|warehouse|asset|maintenance|media)\\.");
  private static final Pattern FORBIDDEN_SERVICE_PROJECT =
      Pattern.compile(
          "project\\s*\\([^)]*:services:(?:auth|task-board|warehouse|asset|maintenance|media)-service[^)]*\\)");
  private static final Pattern MAPPER =
      Pattern.compile("@(?:org\\.mapstruct\\.)?Mapper\\b");
  private static final Pattern MAPPING_TARGET =
      Pattern.compile("@(?:org\\.mapstruct\\.)?MappingTarget\\b");
  private static final Pattern LOW_LEVEL_SQL =
      Pattern.compile("org\\.springframework\\.jdbc|\\bJdbcTemplate\\b|\\bJdbcClient\\b|java\\.sql");
  private static final Pattern BUSINESS_TABLE_SQL =
      Pattern.compile(
          "(?is)\\b(?:from|join|into|update|delete\\s+from|merge\\s+into)\\s+(?:public\\.)?"
              + "(?:inventory_session|inventory_finding|inventory_media_fact_projection)\\b");
  private static final Set<String> LOW_LEVEL_SQL_ADAPTERS =
      Set.of(
          "dev/buhanzaz/rwms/inventory/eventing/InventoryDeadLetterRelay.java",
          "dev/buhanzaz/rwms/inventory/eventing/InventoryDeadLetterStore.java",
          "dev/buhanzaz/rwms/inventory/eventing/InventoryAssetInboxStore.java",
          "dev/buhanzaz/rwms/inventory/eventing/InventoryEventStore.java",
          "dev/buhanzaz/rwms/inventory/eventing/InventoryMediaInboxProcessor.java",
          "dev/buhanzaz/rwms/inventory/eventing/InventoryMediaRetryStore.java",
          "dev/buhanzaz/rwms/inventory/eventing/InventoryOutboxStore.java",
          "dev/buhanzaz/rwms/inventory/persistence/InventoryPostgresJsonbCanonicalizer.java");

  private InventorySourcePolicy() {}

  static void assertSafe(Path serviceRoot) throws IOException {
    if (!Files.exists(serviceRoot)) {
      return;
    }
    var violations = new ArrayList<String>();
    var sourceRoot = serviceRoot.resolve("src/main/java");
    if (Files.isDirectory(sourceRoot)) {
      try (var files = Files.walk(sourceRoot)) {
        files
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(path -> inspectJava(sourceRoot, path, read(path), violations));
      }
    }
    var buildFile = serviceRoot.resolve("build.gradle.kts");
    if (Files.isRegularFile(buildFile)) {
      var source = read(buildFile);
      if (FORBIDDEN_SERVICE_PROJECT.matcher(source).find()) {
        violations.add(
            buildFile + ": inventory-service must not depend on another service project");
      }
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(String.join(System.lineSeparator(), violations));
    }
  }

  private static void inspectJava(
      Path sourceRoot, Path path, String source, List<String> violations) {
    if (!INVENTORY_PACKAGE.matcher(source).find()) {
      violations.add(path + ": production source must use the inventory package root");
    }
    if (FORBIDDEN_SERVICE_IMPORT.matcher(source).find()) {
      violations.add(path + ": inventory source imports another service implementation");
    }
    var relative = sourceRoot.relativize(path).toString().replace('\\', '/');
    if (LOW_LEVEL_SQL.matcher(source).find() && !LOW_LEVEL_SQL_ADAPTERS.contains(relative)) {
      violations.add(
          path
              + ": low-level SQL is restricted to eight exact CAS/outbox/inbox/checkpoint/DLT/JSONB adapters");
    }
    if (LOW_LEVEL_SQL.matcher(source).find() && BUSINESS_TABLE_SQL.matcher(source).find()) {
      violations.add(
          path
              + ": even allowlisted technical adapters must use typed JPA for inventory business tables");
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
}
