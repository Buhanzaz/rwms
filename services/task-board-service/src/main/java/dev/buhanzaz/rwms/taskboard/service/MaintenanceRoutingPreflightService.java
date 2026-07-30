package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.repository.QueueDefinitionRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only prerequisite boundaries for maintenance catalog and warehouse routing. */
@Service
@RequiredArgsConstructor
public class MaintenanceRoutingPreflightService {
  private final QueueDefinitionRepository definitions;
  private final WorkQueueRepository queues;

  @Transactional(readOnly = true)
  public CatalogRoutingPreflightResponse catalogPreflight(
      CatalogRoutingPreflightRequest request) {
    LinkedHashSet<UUID> definitionIds = uniqueDefinitionIds(request.queues());
    Map<UUID, QueueDefinition> definitionsById = loadDefinitions(definitionIds);
    List<UUID> missingDefinitionIds = new ArrayList<>();
    List<CatalogRoutingMismatch> mismatches = new ArrayList<>();
    List<CatalogRoutingResolvedDefinition> resolved = new ArrayList<>();

    for (MaintenanceRoutingQueueRequirement requirement : request.queues()) {
      QueueDefinition definition = definitionsById.get(requirement.queueDefinitionId());
      if (definition == null) {
        missingDefinitionIds.add(requirement.queueDefinitionId());
        continue;
      }
      resolved.add(
          new CatalogRoutingResolvedDefinition(
              definition.getId(), definition.getName(), definition.getType()));
      if (requirement.type() != definition.getType()) {
        mismatches.add(
            new CatalogRoutingMismatch(
                definition.getId(), List.of(MaintenanceRoutingMismatchField.TYPE)));
      }
    }
    return new CatalogRoutingPreflightResponse(
        missingDefinitionIds.isEmpty() && mismatches.isEmpty(),
        missingDefinitionIds,
        mismatches,
        resolved);
  }

  @Transactional(readOnly = true)
  public MaintenanceRoutingPreflightResponse preflight(
      MaintenanceRoutingPreflightRequest request) {
    LinkedHashSet<UUID> definitionIds = uniqueDefinitionIds(request.queues());
    Map<UUID, QueueDefinition> definitionsById = loadDefinitions(definitionIds);
    Map<UUID, WorkQueue> bindingsByDefinitionId = new LinkedHashMap<>();
    queues.findAllOrderedByWarehouseId(request.warehouseId()).stream()
        .filter(queue -> definitionIds.contains(queue.getDefinition().getId()))
        .forEach(queue -> bindingsByDefinitionId.put(queue.getDefinition().getId(), queue));

    List<UUID> missingDefinitionIds = new ArrayList<>();
    List<UUID> missingBindingDefinitionIds = new ArrayList<>();
    List<MaintenanceRoutingMismatch> mismatches = new ArrayList<>();
    List<MaintenanceRoutingResolvedQueue> resolvedQueues = new ArrayList<>();
    for (MaintenanceRoutingQueueRequirement requirement : request.queues()) {
      QueueDefinition definition = definitionsById.get(requirement.queueDefinitionId());
      if (definition == null) {
        missingDefinitionIds.add(requirement.queueDefinitionId());
        continue;
      }
      WorkQueue binding = bindingsByDefinitionId.get(requirement.queueDefinitionId());
      if (binding == null) {
        missingBindingDefinitionIds.add(requirement.queueDefinitionId());
        continue;
      }

      resolvedQueues.add(
          new MaintenanceRoutingResolvedQueue(
              definition.getId(),
              binding.getId(),
              definition.getName(),
              definition.getType()));
      List<MaintenanceRoutingMismatchField> fields = new ArrayList<>();
      if (requirement.type() != definition.getType()) {
        fields.add(MaintenanceRoutingMismatchField.TYPE);
      }
      if (!binding.isActive()) {
        fields.add(MaintenanceRoutingMismatchField.ACTIVE);
      }
      if (binding.isHidden()) {
        fields.add(MaintenanceRoutingMismatchField.HIDDEN);
      }
      if (!fields.isEmpty()) {
        mismatches.add(
            new MaintenanceRoutingMismatch(requirement.queueDefinitionId(), fields));
      }
    }

    return new MaintenanceRoutingPreflightResponse(
        request.warehouseId(),
        missingDefinitionIds.isEmpty()
            && missingBindingDefinitionIds.isEmpty()
            && mismatches.isEmpty(),
        missingDefinitionIds,
        missingBindingDefinitionIds,
        mismatches,
        resolvedQueues);
  }

  private LinkedHashSet<UUID> uniqueDefinitionIds(
      List<MaintenanceRoutingQueueRequirement> requirements) {
    var definitionIds = new LinkedHashSet<UUID>();
    requirements.forEach(
        requirement -> {
          if (!definitionIds.add(requirement.queueDefinitionId())) {
            throw new IllegalArgumentException(
                "Routing preflight requires unique queue definition identifiers");
          }
        });
    return definitionIds;
  }

  private Map<UUID, QueueDefinition> loadDefinitions(LinkedHashSet<UUID> ids) {
    Map<UUID, QueueDefinition> currentById = new LinkedHashMap<>();
    definitions.findAllById(ids).forEach(value -> currentById.put(value.getId(), value));
    return currentById;
  }
}
