package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.config.DevTaskBoardBootstrap;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class TaskBoardDevSecurityIntegrationTest extends PostgresIntegrationTestSupport {
  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
  @org.springframework.beans.factory.annotation.Autowired DevTaskBoardBootstrap bootstrap;
  @org.springframework.beans.factory.annotation.Autowired WorkerClassRepository classes;
  @org.springframework.beans.factory.annotation.Autowired WorkQueueRepository queues;

  @org.springframework.beans.factory.annotation.Autowired
  WorkQueueClassBindingRepository bindings;

  @Test
  void permitsContentApiWithoutBearerTokenOnlyInDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/worker-classes")).andExpect(status().isOk());
  }

  @Test
  void keepsInternalApiAuthenticatedDuringDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/internal/work-queues")).andExpect(status().isUnauthorized());
  }

  @Test
  void developmentBootstrapPreservesAdminSettingsAndRestoresOnlyRequiredBinding()
      throws Exception {
    var workerClass = classes.findByCodeIgnoreCase("GENERAL_WORKER").orElseThrow();
    assertThat(workerClass.getCode()).isEqualTo("GENERAL_WORKER");
    assertThat(workerClass.isActive()).isTrue();
    for (var warehouseId :
        List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"))) {
      var warehouseQueues = queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId);
      assertThat(warehouseQueues).hasSize(1);
      var queue = warehouseQueues.getFirst();
      assertThat(queue.getType()).isEqualTo(QueueType.MOVEMENT);
      assertThat(queue.isActive()).isTrue();
      assertThat(queue.isHidden()).isFalse();
      var queueBindings = bindings.findAllByQueueId(queue.getId());
      assertThat(queueBindings).hasSize(1);
      assertThat(queueBindings.getFirst().getWorkerClass().getId()).isEqualTo(workerClass.getId());
    }

    workerClass.setName("Пользовательское название");
    workerClass.setDescription("Настроено администратором");
    workerClass.setSortOrder(77);
    workerClass.setActive(false);
    classes.save(workerClass);

    UUID spbId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var spbQueue = queues.findByWarehouseIdAndCodeIgnoreCase(spbId, "MOVEMENT").orElseThrow();
    spbQueue.setName("Своя очередь");
    spbQueue.setDescription("Пользовательское описание");
    spbQueue.setType(QueueType.REPAIR);
    spbQueue.setSortOrder(88);
    spbQueue.setActive(false);
    spbQueue.setHidden(true);
    spbQueue.setCollapsed(true);
    queues.save(spbQueue);

    bindings
        .findByQueueIdAndWorkerClassId(spbQueue.getId(), workerClass.getId())
        .ifPresent(bindings::delete);
    var additionalClass = new WorkerClass();
    additionalClass.setCode("CUSTOM_WORKER");
    additionalClass.setName("Дополнительный класс");
    additionalClass.setSortOrder(90);
    additionalClass.setActive(true);
    additionalClass = classes.save(additionalClass);
    var additionalBinding = new WorkQueueClassBinding();
    additionalBinding.setQueue(spbQueue);
    additionalBinding.setWorkerClass(additionalClass);
    additionalBinding.setStopTaskOnTake(true);
    bindings.save(additionalBinding);

    bootstrap.run(new DefaultApplicationArguments(new String[0]));

    var preservedClass = classes.findByCodeIgnoreCase("GENERAL_WORKER").orElseThrow();
    assertThat(preservedClass.getName()).isEqualTo("Пользовательское название");
    assertThat(preservedClass.getDescription()).isEqualTo("Настроено администратором");
    assertThat(preservedClass.getSortOrder()).isEqualTo(77);
    assertThat(preservedClass.isActive()).isFalse();
    var preservedQueue =
        queues.findByWarehouseIdAndCodeIgnoreCase(spbId, "MOVEMENT").orElseThrow();
    assertThat(preservedQueue.getName()).isEqualTo("Своя очередь");
    assertThat(preservedQueue.getDescription()).isEqualTo("Пользовательское описание");
    assertThat(preservedQueue.getType()).isEqualTo(QueueType.REPAIR);
    assertThat(preservedQueue.getSortOrder()).isEqualTo(88);
    assertThat(preservedQueue.isActive()).isFalse();
    assertThat(preservedQueue.isHidden()).isTrue();
    assertThat(preservedQueue.isCollapsed()).isTrue();
    assertThat(bindings.findAllByQueueId(preservedQueue.getId()))
        .extracting(binding -> binding.getWorkerClass().getId())
        .containsExactlyInAnyOrder(preservedClass.getId(), additionalClass.getId());
  }
}
