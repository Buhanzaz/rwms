package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerClassDto;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueueUsageReference;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.mapstruct.Context;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface QueueRegistryMapper {
  QueueDefinitionDto toQueueDefinitionDto(QueueDefinition definition);

  @Mapping(
      target = "logisticsPrimary",
      expression = "java(logisticsPrimaryClassIds.contains(workerClass.getId()))")
  WorkerClassDto toWorkerClassDto(
      WorkerClass workerClass, @Context Set<UUID> logisticsPrimaryClassIds);

  @Mapping(target = "order", source = "bindingOrder")
  @Mapping(target = "primary", expression = "java(binding.getBindingOrder() == 0)")
  QueueBindingDto toQueueBindingDto(
      WorkQueueClassBinding binding, @Context Set<UUID> logisticsPrimaryClassIds);

  @Mapping(target = "definitionId", source = "queue.definition.id")
  @Mapping(target = "definitionVersion", source = "queue.definition.version")
  @Mapping(target = "bindings", source = "queueBindings")
  WorkQueueDto toWorkQueueDto(
      WorkQueue queue,
      List<WorkQueueClassBinding> queueBindings,
      @Context Set<UUID> logisticsPrimaryClassIds);

  @Mapping(target = "queueDefinitionId", source = "definition.id")
  @Mapping(target = "type", source = "referenceType")
  QueueReferenceDto toQueueReferenceDto(QueueUsageReference reference);
}
