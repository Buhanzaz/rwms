package dev.buhanzaz.rwms.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Source-level regression guard for application god classes and disguised dependency hubs.
 *
 * <p>The policy combines explicit limits for refactored compatibility facades with conservative
 * structural signals for new production types. File length alone is not a violation: a large type
 * must also expose a wide dependency surface or an unusually broad executable method surface.
 */
final class GodClassSourcePolicy {
  private static final int THIN_FACADE_MAX_LINES = 1_000;
  private static final int THIN_FACADE_MAX_CONSTRUCTOR_PARAMETERS = 15;
  private static final int COLLABORATOR_MAX_DEPENDENCIES = 15;
  private static final int COLLABORATOR_MAX_LINES = 1_200;
  private static final int LARGE_EXECUTABLE_MAX_LINES = 1_500;
  private static final int LARGE_EXECUTABLE_DEPENDENCY_SIGNAL = 12;
  private static final int LARGE_EXECUTABLE_METHOD_SIGNAL = 60;
  private static final int KOTLIN_COORDINATOR_MAX_LINES = 1_000;

  private static final Map<String, Integer> REFACTORED_FACADES =
      Map.ofEntries(
          Map.entry(
              "services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "app/src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationReconciliationService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemHtmlImportService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/disposition/PropertyDispositionService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/integration/HttpMaintenanceDependencyGateway.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java",
              THIN_FACADE_MAX_LINES),
          Map.entry(
              "services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkforceService.java",
              THIN_FACADE_MAX_LINES));

  private static final Pattern EXECUTABLE_METHOD =
      Pattern.compile(
          "(?m)^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract)\\s+)*"
              + "[A-Za-z_$][A-Za-z0-9_$<>, ?.@\\[\\]]*\\s+"
              + "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^;{}]*\\)\\s*"
              + "(?:throws\\s+[^\\{]+)?\\{");
  private static final Pattern LATE_BOUND_COORDINATOR =
      Pattern.compile(
          "(?m)\\blateinit\\s+var\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*:\\s*"
              + "[A-Za-z_$][A-Za-z0-9_$]*Coordinator\\b");

  private GodClassSourcePolicy() {}

  /** Verifies the current production source tree without interpreting generated or test code. */
  static void assertSafe(Path repositoryRoot) throws IOException {
    if (!Files.isDirectory(repositoryRoot)) {
      return;
    }

    var violations = new ArrayList<String>();
    inspectRefactoredFacades(repositoryRoot, violations);
    inspectJavaSources(repositoryRoot, violations);
    inspectKotlinCoordinators(repositoryRoot, violations);
    if (!violations.isEmpty()) {
      throw new AssertionError(String.join(System.lineSeparator(), violations));
    }
  }

  private static void inspectRefactoredFacades(Path root, List<String> violations)
      throws IOException {
    for (Map.Entry<String, Integer> entry : REFACTORED_FACADES.entrySet()) {
      Path path = root.resolve(entry.getKey());
      if (!Files.isRegularFile(path)) {
        continue;
      }
      long lines = lineCount(path);
      if (lines > entry.getValue()) {
        violations.add(
            path
                + ": refactored compatibility facade has "
                + lines
                + " lines; maximum is "
                + entry.getValue());
      }
      if (path.toString().endsWith(".java")) {
        String source = Files.readString(path);
        long dependencies = directDependencyFields(source);
        if (dependencies > COLLABORATOR_MAX_DEPENDENCIES) {
          violations.add(
              path
                  + ": refactored facade exposes "
                  + dependencies
                  + " direct dependencies; maximum is "
                  + COLLABORATOR_MAX_DEPENDENCIES);
        }
        int constructorParameters =
            maxConstructorParameters(source, javaTypeName(path.getFileName().toString()));
        if (constructorParameters > THIN_FACADE_MAX_CONSTRUCTOR_PARAMETERS) {
          violations.add(
              path
                  + ": refactored facade exposes a constructor with "
                  + constructorParameters
                  + " parameters; maximum is "
                  + THIN_FACADE_MAX_CONSTRUCTOR_PARAMETERS);
        }
      }
    }
  }

  private static void inspectJavaSources(Path root, List<String> violations) throws IOException {
    try (var files = Files.walk(root)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> productionPath(root, path))
          .forEach(path -> inspectJava(path, read(path), violations));
    }
  }

  private static void inspectJava(Path path, String source, List<String> violations) {
    String filename = path.getFileName().toString();
    long lines = source.lines().count();
    long dependencies = directDependencyFields(source);
    long methods = EXECUTABLE_METHOD.matcher(source).results().count();
    boolean collaborator =
        filename.endsWith("Support.java")
            || filename.endsWith("UseCases.java")
            || filename.endsWith("Coordinator.java");

    if (collaborator && dependencies > COLLABORATOR_MAX_DEPENDENCIES) {
      violations.add(
          path
              + ": collaborator exposes "
              + dependencies
              + " direct dependencies; maximum is "
              + COLLABORATOR_MAX_DEPENDENCIES);
    }
    if (collaborator && lines > COLLABORATOR_MAX_LINES) {
      violations.add(
          path
              + ": collaborator has "
              + lines
              + " lines; maximum is "
              + COLLABORATOR_MAX_LINES);
    }

    if (!declarativeContainer(filename)
        && lines > LARGE_EXECUTABLE_MAX_LINES
        && (dependencies > LARGE_EXECUTABLE_DEPENDENCY_SIGNAL
            || methods > LARGE_EXECUTABLE_METHOD_SIGNAL)) {
      violations.add(
          path
              + ": large executable type has "
              + lines
              + " lines, "
              + dependencies
              + " direct dependencies and "
              + methods
              + " methods; perform a cohesion review and split independent workflows");
    }
  }

  private static void inspectKotlinCoordinators(Path root, List<String> violations)
      throws IOException {
    try (var files = Files.walk(root)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".kt"))
          .filter(path -> productionPath(root, path))
          .forEach(
              path -> {
                String source = read(path);
                long lines = source.lines().count();
                if (path.getFileName().toString().endsWith("Coordinator.kt")
                    && lines > KOTLIN_COORDINATOR_MAX_LINES) {
                  violations.add(
                      path
                          + ": coordinator has "
                          + lines
                          + " lines; maximum is "
                          + KOTLIN_COORDINATOR_MAX_LINES);
                }
                if (source.contains("abstract class ManagerWorkflowCoordinator")) {
                  violations.add(
                      path
                          + ": universal ManagerWorkflowCoordinator inheritance is forbidden; use narrow composition");
                }
                if (managerUiSource(root, path)
                    && LATE_BOUND_COORDINATOR.matcher(source).find()) {
                  violations.add(
                      path
                          + ": late-bound coordinator registry is forbidden; route through explicit narrow dependencies");
                }
              });
    }
  }

  private static boolean productionPath(Path root, Path path) {
    String relative = root.relativize(path).toString().replace('\\', '/');
    return relative.contains("/src/main/")
        && !relative.contains("/build/")
        && !relative.contains("/generated/");
  }

  private static boolean managerUiSource(Path root, Path path) {
    String relative = root.relativize(path).toString().replace('\\', '/');
    return relative.startsWith("app/src/main/java/dev/buhanzaz/rwms/manager/ui/");
  }

  private static long directDependencyFields(String source) {
    return source
        .lines()
        .map(String::strip)
        .filter(
            line ->
                (line.startsWith("private final ") || line.startsWith("protected final "))
                    && !line.startsWith("private final class ")
                    && !line.startsWith("protected final class "))
        .count();
  }

  private static int maxConstructorParameters(String source, String typeName) {
    Pattern constructor =
        Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private)\\s+)?"
                + Pattern.quote(typeName)
                + "\\s*\\(");
    int maximum = 0;
    var matches = constructor.matcher(source);
    while (matches.find()) {
      int parametersStart = matches.end() - 1;
      int parenthesisDepth = 0;
      int angleDepth = 0;
      int commas = 0;
      boolean content = false;
      for (int index = parametersStart; index < source.length(); index++) {
        char value = source.charAt(index);
        if (value == '(') {
          parenthesisDepth++;
          continue;
        }
        if (value == ')') {
          parenthesisDepth--;
          if (parenthesisDepth == 0) {
            maximum = Math.max(maximum, content ? commas + 1 : 0);
            break;
          }
          continue;
        }
        if (parenthesisDepth != 1) {
          continue;
        }
        if (value == '<') {
          angleDepth++;
        } else if (value == '>' && angleDepth > 0) {
          angleDepth--;
        } else if (value == ',' && angleDepth == 0) {
          commas++;
        } else if (!Character.isWhitespace(value)) {
          content = true;
        }
      }
    }
    return maximum;
  }

  private static String javaTypeName(String filename) {
    return filename.substring(0, filename.length() - ".java".length());
  }

  private static boolean declarativeContainer(String filename) {
    return filename.endsWith("ApiModels.java")
        || filename.endsWith("ApiModel.java")
        || filename.endsWith("Dtos.java")
        || filename.endsWith("Dto.java");
  }

  private static long lineCount(Path path) throws IOException {
    try (var lines = Files.lines(path)) {
      return lines.count();
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
