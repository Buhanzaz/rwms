package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.ContractorDriverResponse;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateContractorDriverRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.UpdateContractorDriverRequest;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.WorkerOperationalAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns warehouse-scoped contractor profiles independently from staff shifts and route capacity.
 *
 * <p>A profile is an address-book identity only. The selected logistics day and exact work are
 * fixed by downstream assignment commands, so this aggregate intentionally stores no availability
 * interval, vehicle, shift, or cycle settings.
 */
@Service
@RequiredArgsConstructor
public class ContractorDriverService {
  private final WorkerRepository workers;
  private final WorkerOperationalAssignmentRepository operationalAssignments;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  /** Creates an active contractor profile or returns an identical stable-ID replay. */
  @Transactional
  public ContractorDriverResponse create(
      UUID warehouseId, CreateContractorDriverRequest request) {
    Worker existing = workers.findByIdForUpdate(request.contractorId()).orElse(null);
    if (existing != null) {
      if (!sameContractor(existing, warehouseId, request)) {
        throw new ConflictException(
            "Идентификатор наёмного водителя уже использован с другими данными");
      }
      return response(existing);
    }

    var worker = new Worker();
    worker.assignReviewedId(request.contractorId());
    worker.setWarehouseId(warehouseId);
    worker.setDisplayName(request.displayName());
    worker.setActive(true);
    worker.setComment(request.comment());
    worker.configureContractor(request.phone());
    worker = projectionWriter.saveAndFlush(workers, worker);
    eventSourcing.created(worker);
    return response(worker);
  }

  /** Returns all contractor profiles owned by one warehouse, including inactive profiles. */
  @Transactional(readOnly = true)
  public List<ContractorDriverResponse> list(UUID warehouseId) {
    return workers.findAllByWarehouseIdOrderByDisplayNameAsc(warehouseId).stream()
        .filter(worker -> worker.getEmploymentType() == WorkerEmploymentType.CONTRACTOR)
        .map(ContractorDriverService::response)
        .toList();
  }

  /** Replaces editable contractor fields under the observed worker version fence. */
  @Transactional
  public ContractorDriverResponse update(
      UUID warehouseId, UUID workerId, UpdateContractorDriverRequest request) {
    Worker worker = requireOwnedContractor(warehouseId, workerId);
    checkVersion(worker.getVersion(), request.expectedVersion(), "Наёмный водитель");
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, workerId);
    worker.setDisplayName(request.displayName());
    worker.setComment(request.comment());
    worker.setActive(request.active());
    worker.configureContractor(request.phone());
    worker.touch();
    worker = projectionWriter.saveAndFlush(workers, worker);
    projectionWriter.refresh(worker);
    eventSourcing.workerChanged(worker, streamVersion, TaskBoardEventTypes.WORKER_CHANGED);
    return response(worker);
  }

  /**
   * Deletes an unused contractor profile under a version fence.
   *
   * <p>Transfer-backed history is retained, so a contractor referenced by an operational
   * assignment must be deactivated instead of deleted. Other unexpected database references are
   * translated to the same safe business conflict.
   */
  @Transactional
  public void delete(UUID warehouseId, UUID workerId, long expectedVersion) {
    Worker worker = requireOwnedContractor(warehouseId, workerId);
    checkVersion(worker.getVersion(), expectedVersion, "Наёмный водитель");
    if (operationalAssignments.existsByWorkerId(workerId)) {
      throw new ConflictException(
          "Наёмный водитель уже участвовал в перемещениях; его можно только сделать неактивным");
    }
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, workerId);
    eventSourcing.deleted(worker, streamVersion);
    try {
      projectionWriter.delete(workers, worker);
      projectionWriter.flush();
    } catch (DataIntegrityViolationException exception) {
      throw new ConflictException(
          "Наёмный водитель уже используется в заданиях; его можно только сделать неактивным");
    }
  }

  private Worker requireOwnedContractor(UUID warehouseId, UUID workerId) {
    Worker worker =
        workers
            .findByIdForUpdate(workerId)
            .orElseThrow(() -> new NotFoundException("Наёмный водитель не найден"));
    if (!worker.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Наёмный водитель не найден");
    }
    if (worker.getEmploymentType() != WorkerEmploymentType.CONTRACTOR) {
      throw new ConflictException("Выбранный рабочий не является наёмным водителем");
    }
    return worker;
  }

  private static boolean sameContractor(
      Worker worker, UUID warehouseId, CreateContractorDriverRequest request) {
    return worker.getEmploymentType() == WorkerEmploymentType.CONTRACTOR
        && worker.getWarehouseId().equals(warehouseId)
        && Objects.equals(worker.getDisplayName(), request.displayName().trim())
        && Objects.equals(worker.getPhone(), request.phone().trim())
        && Objects.equals(worker.getComment(), request.comment());
  }

  private static ContractorDriverResponse response(Worker worker) {
    return new ContractorDriverResponse(
        worker.getId(),
        worker.getVersion(),
        worker.getWarehouseId(),
        worker.getDisplayName(),
        worker.getPhone(),
        worker.getComment(),
        worker.isActive(),
        worker.getEmploymentType());
  }
}
