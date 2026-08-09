package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the source-level regression policy that keeps decomposed workflow boundaries narrow. */
class GodClassSourcePolicyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void currentProductionSourcesKeepTheDecomposedApplicationBoundaries() {
    Path root = Path.of(System.getProperty("rwms.root.dir"));

    assertDoesNotThrow(() -> GodClassSourcePolicy.assertSafe(root));
  }

  @Test
  void rejectsLargeExecutableTypesWithWideDependencies() throws Exception {
    StringBuilder fields = new StringBuilder();
    for (int index = 0; index < 13; index++) {
      fields
          .append("  private final Object dependency")
          .append(index)
          .append(" = new Object();\n");
    }
    write(
        "services/example/src/main/java/dev/example/LargeWorkflowService.java",
        """
        package dev.example;

        final class LargeWorkflowService {
        """
            + fields
            + "// workflow filler\n".repeat(1_510)
            + "}\n");

    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> GodClassSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("large executable type"));
  }

  @Test
  void rejectsSupportDependencyBagsEvenWhenTheyAreShort() throws Exception {
    StringBuilder fields = new StringBuilder();
    for (int index = 0; index < 16; index++) {
      fields
          .append("  protected final Object dependency")
          .append(index)
          .append(" = new Object();\n");
    }
    write(
        "services/example/src/main/java/dev/example/UniversalWorkflowSupport.java",
        """
        package dev.example;

        abstract class UniversalWorkflowSupport {
        """
            + fields
            + "}\n");

    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> GodClassSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("direct dependencies"));
  }

  @Test
  void rejectsWideCompatibilityConstructorsBehindNarrowFacadeFields() throws Exception {
    StringBuilder parameters = new StringBuilder();
    for (int index = 0; index < 16; index++) {
      if (index > 0) {
        parameters.append(", ");
      }
      parameters.append("Object dependency").append(index);
    }
    write(
        "services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetService.java",
        """
        package dev.buhanzaz.rwms.asset.service;

        final class AssetService {
          private final Object collaborator = new Object();

          AssetService(
        """
            + parameters
            + ") {}\n}\n");

    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> GodClassSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("constructor with 16 parameters"));
  }

  @Test
  void rejectsMegaAndLateBoundKotlinCoordinators() throws Exception {
    write(
        "app/src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerWorkflowCoordinator.kt",
        """
        package dev.buhanzaz.rwms.manager.ui.coordinator

        abstract class ManagerWorkflowCoordinator

        class ManagerHost {
            private lateinit var workflow: ExampleCoordinator
        }
        """
            + "// coordinator filler\n".repeat(1_010));

    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> GodClassSourcePolicy.assertSafe(temporaryDirectory));
    assertTrue(failure.getMessage().contains("coordinator has"));
    assertTrue(failure.getMessage().contains("universal ManagerWorkflowCoordinator"));
    assertTrue(failure.getMessage().contains("late-bound coordinator registry"));
  }

  @Test
  void acceptsHiltInjectedCoordinatorOnAnAndroidFrameworkComponent() throws Exception {
    write(
        "worker-app/app/src/main/java/dev/example/WorkerFirebaseMessagingService.kt",
        """
        package dev.example

        class WorkerFirebaseMessagingService {
            @Inject lateinit var push: WorkerPushCoordinator
        }
        """);

    assertDoesNotThrow(() -> GodClassSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void acceptsLargeDeclarativeContainersAndNarrowExecutableTypes() throws Exception {
    write(
        "services/example/src/main/java/dev/example/ExampleApiModels.java",
        """
        package dev.example;

        final class ExampleApiModels {
        """
            + "// declarative vocabulary\n".repeat(1_600)
            + "}\n");
    write(
        "services/example/src/main/java/dev/example/CohesiveService.java",
        """
        package dev.example;

        final class CohesiveService {
          private final Object repository = new Object();
        """
            + "// cohesive algorithm\n".repeat(1_600)
            + "}\n");

    assertDoesNotThrow(() -> GodClassSourcePolicy.assertSafe(temporaryDirectory));
  }

  private void write(String relativePath, String source) throws Exception {
    Path path = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
