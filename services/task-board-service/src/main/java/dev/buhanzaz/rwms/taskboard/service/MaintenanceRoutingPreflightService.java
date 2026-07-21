package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingMismatch;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingMismatchField;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightResponse;

import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only prerequisite boundary for maintenance catalog routing snapshots. */
@Service
@RequiredArgsConstructor
public class MaintenanceRoutingPreflightService {
  private final WorkQueueRepository queues;

  @Transactional(readOnly = true)
  public MaintenanceRoutingPreflightResponse preflight(
      MaintenanceRoutingPreflightRequest request) {
    var queueIds = new LinkedHashSet<UUID>();
    request.queues().forEach(
        requirement -> {
          if (!queueIds.add(requirement.queueId())) {
            throw new IllegalArgumentException(
                "Routing preflight requires unique queue identifiers");
          }
        });

    Map<UUID, WorkQueue> currentById = new LinkedHashMap<>();
    queues.findAllById(queueIds).forEach(queue -> currentById.put(queue.getId(), queue));

    List<UUID> missingQueueIds = new ArrayList<>();
    List<MaintenanceRoutingMismatch> mismatches = new ArrayList<>();
    for (var requirement : request.queues()) {
      WorkQueue current = currentById.get(requirement.queueId());
      if (current == null) {
        missingQueueIds.add(requirement.queueId());
        continue;
      }

      List<MaintenanceRoutingMismatchField> fields = new ArrayList<>();
      if (!request.warehouseId().equals(current.getWarehouseId())) {
        fields.add(MaintenanceRoutingMismatchField.WAREHOUSE_ID);
      }
      if (!Objects.equals(requirement.code(), current.getCode())) {
        fields.add(MaintenanceRoutingMismatchField.CODE);
      }
      if (requirement.type() != current.getType()) {
        fields.add(MaintenanceRoutingMismatchField.TYPE);
      }
      if (!current.isActive()) {
        fields.add(MaintenanceRoutingMismatchField.ACTIVE);
      }
      if (current.isHidden()) {
        fields.add(MaintenanceRoutingMismatchField.HIDDEN);
      }
      if (!fields.isEmpty()) {
        mismatches.add(new MaintenanceRoutingMismatch(requirement.queueId(), fields));
      }
    }

    return new MaintenanceRoutingPreflightResponse(
        request.warehouseId(),
        missingQueueIds.isEmpty() && mismatches.isEmpty(),
        missingQueueIds,
        mismatches);
  }
}
