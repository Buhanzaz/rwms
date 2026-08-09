package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.JsonNode;

/** Enables task-board scheduling and registers exact payload-safety policy for Kafka publication. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({TaskBoardOutboxProperties.class, RwmsKafkaProperties.class})
public class TaskBoardEventingConfiguration {
  @org.springframework.context.annotation.Bean
  static BeanDefinitionRegistryPostProcessor taskBoardPayloadValidators() {
    return registry -> TaskBoardEventTypes.BY_AGGREGATE.forEach((type, eventTypes) ->
        eventTypes.forEach(eventType -> registerValidator(registry, type, eventType)));
  }

  private static void registerValidator(
      BeanDefinitionRegistry registry, TaskBoardAggregateType type, String eventType) {
    BeanDefinition definition = BeanDefinitionBuilder
        .genericBeanDefinition(TaskBoardPayloadSafetyValidator.class)
        .addConstructorArgValue(eventType)
        .addConstructorArgValue(type)
        .addConstructorArgReference("taskBoardEventPayloadPolicy")
        .getBeanDefinition();
    registry.registerBeanDefinition(
        "taskBoardPayloadValidator_" + eventType.replace('.', '_').replace('-', '_'), definition);
  }
}

/** Rejects serialized task-board payloads that do not match their declared aggregate family. */
final class TaskBoardPayloadSafetyValidator implements RwmsKafkaPayloadSafetyValidator {
  private final String eventType;
  private final TaskBoardAggregateType aggregateType;
  private final TaskBoardEventPayloadPolicy policy;

  TaskBoardPayloadSafetyValidator(
      String eventType, TaskBoardAggregateType aggregateType, TaskBoardEventPayloadPolicy policy) {
    this.eventType = eventType;
    this.aggregateType = aggregateType;
    this.policy = policy;
  }

  @Override
  public String eventType() { return eventType; }

  @Override
  public void validate(JsonNode payload) {
    String idField = switch (aggregateType) {
      case WORKER_CLASS -> "workerClassId";
      case WORKER -> "workerId";
      case WORKER_GROUP -> "workerGroupId";
      case WORK_QUEUE -> "workQueueId";
      case QUEUE_USAGE_REFERENCE -> "queueUsageReferenceId";
      case BOARD_TASK -> "boardTaskId";
      case QUEUE_ENTRY -> "queueEntryId";
      case TASK_BOARD_ENTRY_OWNER_PROOF -> "ownerId";
      case TASK_EVIDENCE -> "evidenceId";
      case GROUP_KPI_DAY -> "evidenceId";
    };
    JsonNode identity = payload.get(idField);
    if (identity == null || !identity.isTextual())
      throw new IllegalArgumentException("Missing aggregate identity");
    policy.validateNode(eventType, aggregateType, java.util.UUID.fromString(identity.stringValue()), payload);
  }
}
