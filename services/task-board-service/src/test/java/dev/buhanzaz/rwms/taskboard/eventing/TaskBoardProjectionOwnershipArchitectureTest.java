package dev.buhanzaz.rwms.taskboard.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskBoardProjectionOwnershipArchitectureTest {
  private static final String REPOSITORY_PACKAGE =
      "dev.buhanzaz.rwms.taskboard.repository.";
  private static final Set<String> EVENT_SOURCED_REPOSITORIES =
      Set.of(
          "BoardTaskRepository",
          "QueueEntryRepository",
          "QueueUsageReferenceRepository",
          "TaskAssignmentRepository",
          "TaskAutoInterruptionRepository",
          "TaskTimeEventRepository",
          "WorkerClassAssignmentRepository",
          "WorkerClassRepository",
          "WorkerGroupMemberRepository",
          "WorkerGroupRepository",
          "WorkerRepository",
          "WorkQueueClassBindingRepository",
          "WorkQueueRepository");
  private static final String PROJECTION_WRITER = TaskBoardProjectionWriter.class.getName();
  private static final Set<String> REPOSITORY_MUTATIONS =
      Set.of(
          "save",
          "saveAll",
          "saveAndFlush",
          "saveAllAndFlush",
          "flush",
          "delete",
          "deleteById",
          "deleteAll",
          "deleteAllById",
          "deleteAllByIdInBatch",
          "deleteAllInBatch");

  @Test
  void onlyApprovedCommandServicesMayMutateEventSourcedJpaProjections() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.buhanzaz.rwms.taskboard");

    var mutationCalls =
        classes.stream()
            .flatMap(type -> type.getMethodCallsFromSelf().stream())
            .filter(this::isEventSourcedRepositoryMutation)
            .toList();
    var violations =
        mutationCalls.stream()
            .filter(call -> !PROJECTION_WRITER.equals(call.getOriginOwner().getName()))
            .map(JavaMethodCall::getDescription)
            .sorted()
            .toList();

    assertThat(violations)
        .as("event-sourced JPA projections must only be mutated by TaskBoardProjectionWriter")
        .isEmpty();
    assertThat(mutationCalls.stream().map(call -> call.getOriginOwner().getName()))
        .as("the classifier must detect the projection-writer canaries")
        .containsOnly(PROJECTION_WRITER);
  }

  private boolean isEventSourcedRepositoryMutation(JavaMethodCall call) {
    String owner = call.getTargetOwner().getName();
    if (owner.startsWith("org.springframework.data.")) {
      return PROJECTION_WRITER.equals(call.getOriginOwner().getName())
          && REPOSITORY_MUTATIONS.contains(call.getName());
    }
    if (!owner.startsWith(REPOSITORY_PACKAGE)) return false;
    String simpleName = owner.substring(REPOSITORY_PACKAGE.length());
    return EVENT_SOURCED_REPOSITORIES.contains(simpleName)
        && REPOSITORY_MUTATIONS.contains(call.getName());
  }
}
