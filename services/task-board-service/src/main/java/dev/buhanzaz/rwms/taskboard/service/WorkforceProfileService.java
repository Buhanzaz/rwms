package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClassAssignment;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns worker profile and qualification mutations.
 *
 * <p>It keeps the per-worker credential lock around a profile update and its remote completion,
 * while {@link WorkforceCredentialLifecycleService} supplies the narrow durable credential intent
 * and completion protocol. Group membership and availability remain outside this boundary.
 */
@Service
public class WorkforceProfileService {
  private final WorkerRepository workers;
  private final WorkerClassAssignmentRepository qualifications;
  private final RegistryService registry;
  private final TransactionTemplate tx;
  private final WorkerCredentialOperationCoordinator credentialCoordinator;
  private final WorkforceCredentialLifecycleService credentialLifecycle;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final WorkforceReadProjectionService projections;

  public WorkforceProfileService(
      WorkerRepository workers,
      WorkerClassAssignmentRepository qualifications,
      RegistryService registry,
      PlatformTransactionManager transactionManager,
      WorkerCredentialOperationCoordinator credentialCoordinator,
      WorkforceCredentialLifecycleService credentialLifecycle,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      WorkforceReadProjectionService projections) {
    this.workers = workers;
    this.qualifications = qualifications;
    this.registry = registry;
    this.tx = new TransactionTemplate(transactionManager);
    this.credentialCoordinator = credentialCoordinator;
    this.credentialLifecycle = credentialLifecycle;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.projections = projections;
  }

  List<WorkerDto> listWorkers(UUID warehouseId) {
    return tx.execute(
        ignored ->
            workers.findAllByWarehouseIdOrderByDisplayNameAsc(warehouseId).stream()
                .map(projections::workerDto)
                .toList());
  }

  WorkerDto createWorker(UUID warehouseId, WorkerRequest request) {
    credentialLifecycle.validateCredentialInput(request.appLogin(), request.password(), true);
    Worker saved =
        tx.execute(
            ignored -> {
              var worker = new Worker();
              worker.setWarehouseId(warehouseId);
              applyProfile(worker, request);
              worker.setAppLogin(credentialLifecycle.normalizeLogin(request.appLogin()));
              worker.setCredentialStatus(CredentialStatus.NOT_CONFIGURED);
              worker = projectionWriter.save(workers, worker);
              replaceQualifications(worker, request.qualifications());
              projectionWriter.flush();
              projectionWriter.refresh(worker);
              eventSourcing.created(worker);
              return worker;
            });
    if (saved.getAppLogin() != null) {
      credentialLifecycle.configureCreated(saved, request.password());
    }
    return tx.execute(ignored -> projections.workerDto(requireWorker(warehouseId, saved.getId())));
  }

  WorkerDto updateWorker(UUID warehouseId, UUID id, WorkerRequest request) {
    credentialLifecycle.validateCredentialInput(request.appLogin(), request.password(), false);
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      WorkforceCredentialProfileUpdate prepared =
          tx.execute(
              transaction -> {
                var worker = requireWorker(warehouseId, id);
                credentialLifecycle.ensureCredentialOperationSettled(worker);
                checkVersion(worker.getVersion(), request.version(), "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                WorkforceCredentialProfileUpdate update =
                    credentialLifecycle.prepareProfileUpdate(worker, request);
                applyProfile(worker, request);
                credentialLifecycle.startProfileCredentialOperation(worker, update);
                worker.touch();
                worker = projectionWriter.save(workers, worker);
                replaceQualifications(worker, request.qualifications());
                projectionWriter.flush();
                projectionWriter.refresh(worker);
                eventSourcing.workerChanged(worker, streamVersion, TaskBoardEventTypes.WORKER_CHANGED);
                return update.withWorker(worker);
              });
      credentialLifecycle.completeProfileUpdate(prepared, request.password());
      return tx.execute(transaction -> projections.workerDto(requireWorker(warehouseId, id)));
    }
  }

  Worker requireWorker(UUID warehouseId, UUID id) {
    var worker = workers.findById(id).orElseThrow(() -> new NotFoundException("Рабочий не найден"));
    if (!worker.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Рабочий не найден");
    }
    return worker;
  }

  List<WorkerClassAssignment> activeQualifications(UUID workerId) {
    return qualifications.findAllByWorkerId(workerId).stream()
        .filter(WorkerClassAssignment::isActive)
        .toList();
  }

  private void replaceQualifications(Worker worker, List<QualificationRequest> requested) {
    var existing = qualifications.findAllByWorkerId(worker.getId());
    projectionWriter.deleteAll(qualifications, existing);
    // Hibernate inserts replacement rows before deferred deletes. Flush the
    // removals so retaining a qualification cannot violate the natural key.
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
    if (requested == null) {
      return;
    }
    var unique = new LinkedHashMap<UUID, QualificationRequest>();
    requested.forEach(request -> unique.put(request.workerClassId(), request));
    for (var request : unique.values()) {
      var qualification = new WorkerClassAssignment();
      qualification.setWorker(worker);
      qualification.setWorkerClass(registry.requireClass(request.workerClassId()));
      qualification.setActive(request.active());
      qualification.setComment(request.comment());
      projectionWriter.save(qualifications, qualification);
    }
  }

  private void applyProfile(Worker worker, WorkerRequest request) {
    worker.setDisplayName(request.displayName());
    worker.setFirstName(request.firstName());
    worker.setLastName(request.lastName());
    worker.setMiddleName(request.middleName());
    worker.setActive(request.active());
    worker.setComment(request.comment());
  }
}
