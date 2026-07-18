package dev.buhanzaz.rwms.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Source-level guard for the Stage 9 append-only projection boundary. */
final class DossierSourcePolicy {
  private static final Pattern DOSSIER_PACKAGE =
      Pattern.compile(
          "(?m)^\\s*package\\s+dev\\.buhanzaz\\.rwms\\.dossier(?:\\.[A-Za-z_$][A-Za-z0-9_$.]*)?\\s*;");
  private static final Pattern FORBIDDEN_SERVICE_IMPORT =
      Pattern.compile(
          "(?m)^\\s*import\\s+(?:static\\s+)?dev\\.buhanzaz\\.rwms\\.(?:auth|taskboard|warehouse|asset|maintenance|inventory|logistics|media)\\.");
  private static final Pattern FORBIDDEN_SERVICE_PROJECT =
      Pattern.compile(
          "project\\s*\\([^)]*:services:(?:auth|task-board|warehouse|asset|maintenance|inventory|logistics|media)-service[^)]*\\)");
  private static final Pattern LOW_LEVEL_DATABASE_ACCESS =
      Pattern.compile(
          "org\\.springframework\\.jdbc|\\bJdbcTemplate\\b|\\bJdbcClient\\b|"
              + "java\\.sql\\.(?:Connection|Statement|PreparedStatement|ResultSet)|"
              + "javax?\\.sql\\.DataSource|org\\.postgresql\\.|"
              + "jakarta\\.persistence\\.(?:EntityManager|PersistenceContext)|\\bcreateNativeQuery\\b");
  private static final Pattern SPRING_DATA_JDBC =
      Pattern.compile(
          "org\\.springframework\\.data\\.(?:jdbc|relational)(?:\\.|;)|"
              + "org\\.springframework\\.data\\.repository\\.(?:CrudRepository|ListCrudRepository)");
  private static final Pattern NATIVE_JPA_QUERY =
      Pattern.compile("\\bnativeQuery\\s*=\\s*true\\b");
  private static final Pattern SYNCHRONOUS_SOURCE_CLIENT =
      Pattern.compile(
          "org\\.springframework\\.(?:web\\.client\\.(?:RestClient|RestTemplate)|"
              + "web\\.reactive\\.function\\.client\\.WebClient)|"
              + "org\\.springframework\\.cloud\\.openfeign|"
              + "java\\.net\\.http\\.HttpClient|java\\.net\\.(?:HttpURLConnection|URLConnection)|"
              + "okhttp3\\.|retrofit2\\.");
  private static final Pattern FORBIDDEN_CLIENT_DEPENDENCY =
      Pattern.compile(
          "(?i)(spring-cloud-starter-openfeign|spring-boot-starter-webflux|okhttp|retrofit)");
  private static final Pattern LEGACY_OR_BROWSER_REFERENCE =
      Pattern.compile("(?i)wms-panel-old|old_db|localstorage|indexeddb");
  private static final Pattern MAPPER = Pattern.compile("@(?:org\\.mapstruct\\.)?Mapper\\b");
  private static final Pattern MAPPING_TARGET =
      Pattern.compile("@(?:org\\.mapstruct\\.)?MappingTarget\\b");

  private DossierSourcePolicy() {}

  static void assertSafe(Path serviceRoot) throws IOException {
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
    if (Files.isRegularFile(buildFile)) {
      String build = read(buildFile);
      if (FORBIDDEN_SERVICE_PROJECT.matcher(build).find()) {
        violations.add(buildFile + ": dossier-service must not depend on another service project");
      }
      if (FORBIDDEN_CLIENT_DEPENDENCY.matcher(build).find()) {
        violations.add(buildFile + ": dossier-service must not add a synchronous source HTTP client");
      }
    }

    failIfNeeded(violations);
  }

  private static void inspectJava(
      Path sourceRoot, Path path, String source, List<String> violations) {
    if (!DOSSIER_PACKAGE.matcher(source).find()) {
      violations.add(path + ": production source must use the dossier package root");
    }
    if (FORBIDDEN_SERVICE_IMPORT.matcher(source).find()) {
      violations.add(path + ": dossier source imports another service implementation");
    }
    if (LOW_LEVEL_DATABASE_ACCESS.matcher(source).find()) {
      violations.add(path + ": dossier runtime persistence must use JPA, never low-level JDBC");
    }
    if (SPRING_DATA_JDBC.matcher(source).find()) {
      violations.add(path + ": Spring Data JDBC repositories and mappings are forbidden");
    }
    if (NATIVE_JPA_QUERY.matcher(source).find()) {
      violations.add(path + ": native JPA SQL is forbidden in dossier runtime persistence");
    }
    if (SYNCHRONOUS_SOURCE_CLIENT.matcher(source).find()) {
      violations.add(path + ": dossier must not call a producer HTTP API");
    }
    if (LEGACY_OR_BROWSER_REFERENCE.matcher(source).find()) {
      violations.add(path + ": dossier runtime must not depend on browser or legacy evidence");
    }

    String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
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

  private static void failIfNeeded(List<String> violations) {
    if (!violations.isEmpty()) {
      throw new AssertionError(String.join(System.lineSeparator(), violations));
    }
  }
}
