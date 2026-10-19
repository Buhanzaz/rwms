package dev.buhanzaz.rwms.taskboard.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskBoardKafkaOutputChannelsConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(TaskBoardKafkaOutputChannelsConfiguration.class)
          .withPropertyValues("rwms.platform.kafka.enabled=true");

  @Test
  void registersAChannelForEveryCanonicalKafkaOutputDestination() {
    contextRunner.run(
        context -> {
          for (TaskBoardAggregateType aggregateType : TaskBoardAggregateType.values()) {
            for (String destination : aggregateType.outputDestinations()) {
              assertThat(context).hasBean(destination);
            }
          }
        });
  }
}
