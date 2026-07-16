package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

class TaskBoardRabbitRetirementArchitectureTest {
  private static final List<String> RETIRED_RUNTIME_TYPES =
      List.of(
          "org.springframework.amqp.rabbit.core.RabbitTemplate",
          "org.springframework.amqp.rabbit.annotation.RabbitListener",
          "dev.buhanzaz.rwms.taskboard.integration.TaskBoardEventRecorder",
          "dev.buhanzaz.rwms.taskboard.integration.TaskBoardOutboxRelay",
          "dev.buhanzaz.rwms.taskboard.integration.TaskBoardDeliveryAuditListener",
          "dev.buhanzaz.rwms.taskboard.integration.TaskBoardMessagingConfiguration");

  @Test
  void taskBoardRuntimeClasspathContainsNoAmqpOrLegacyRabbitRuntime() {
    ClassLoader classLoader = getClass().getClassLoader();

    assertThat(RETIRED_RUNTIME_TYPES)
        .allSatisfy(
            type ->
                assertThat(ClassUtils.isPresent(type, classLoader))
                    .as("retired runtime type %s", type)
                    .isFalse());
  }

  @Test
  void taskCommandsHaveNoLegacyDualWriteDependency() {
    assertThat(
            Arrays.stream(TaskBoardService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getName))
        .noneMatch(type -> type.startsWith("dev.buhanzaz.rwms.taskboard.integration."));
  }
}
