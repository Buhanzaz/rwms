package dev.buhanzaz.rwms.taskboard.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class TaskBoardKafkaOutputChannelsConfiguration {
  @Bean(name = "rwms.task-board.worker-class.v1") MessageChannel workerClass() { return channel(); }
  @Bean(name = "rwms.task-board.worker.v1") MessageChannel worker() { return channel(); }
  @Bean(name = "rwms.task-board.worker-group.v1") MessageChannel workerGroup() { return channel(); }
  @Bean(name = "rwms.task-board.work-queue.v1") MessageChannel workQueue() { return channel(); }
  @Bean(name = "rwms.task-board.queue-usage-reference.v1") MessageChannel reference() { return channel(); }
  @Bean(name = "rwms.task-board.board-task.v1") MessageChannel boardTask() { return channel(); }
  @Bean(name = "rwms.task-board.queue-entry.v1") MessageChannel queueEntry() { return channel(); }
  @Bean(name = "rwms.task-board.worker-class.v1.task-board-shadow-v1.dlt") MessageChannel workerClassDlt() { return channel(); }
  @Bean(name = "rwms.task-board.worker.v1.task-board-shadow-v1.dlt") MessageChannel workerDlt() { return channel(); }
  @Bean(name = "rwms.task-board.worker-group.v1.task-board-shadow-v1.dlt") MessageChannel workerGroupDlt() { return channel(); }
  @Bean(name = "rwms.task-board.work-queue.v1.task-board-shadow-v1.dlt") MessageChannel workQueueDlt() { return channel(); }
  @Bean(name = "rwms.task-board.queue-usage-reference.v1.task-board-shadow-v1.dlt") MessageChannel referenceDlt() { return channel(); }
  @Bean(name = "rwms.task-board.board-task.v1.task-board-shadow-v1.dlt") MessageChannel boardTaskDlt() { return channel(); }
  @Bean(name = "rwms.task-board.queue-entry.v1.task-board-shadow-v1.dlt") MessageChannel queueEntryDlt() { return channel(); }

  private MessageChannel channel() { return new DirectWithAttributesChannel(); }
}
