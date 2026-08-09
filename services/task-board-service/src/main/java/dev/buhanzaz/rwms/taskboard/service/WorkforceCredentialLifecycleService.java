package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.config.TaskBoardClientProperties;
import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Owns the durable worker-credential lifecycle across task-board and auth-service.
 *
 * <p>Each remote operation first writes a pending, versioned worker intent, then applies only the
 * matching completion or failure. The physical credential lock spans profile completion as well as
 * direct credential commands, preserving the original serialization and reconciliation behavior.
 * Auth-coupled worker deletion belongs here because its durable deletion intent is the recovery
 * boundary between the external account and local aggregate removal.
 */
@Service
public class WorkforceCredentialLifecycleService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(WorkforceCredentialLifecycleService.class);

  private final WorkerRepository workers;
  private final WorkerClassAssignmentRepository qualifications;
  private final WorkerGroupRepository groups;
  private final WorkerGroupMemberRepository members;
  private final TaskAssignmentRepository taskAssignments;
  private final TaskTimeEventRepository events;
  private final WorkerDeletionIntentRepository deletionIntents;
  private final WorkerCredentialGateway credentials;
  private final TransactionTemplate tx;
  private final TaskBoardClientProperties clientProperties;
  private final JdbcTemplate jdbc;
  private final WorkerCredentialOperationCoordinator credentialCoordinator;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final WorkforceReadProjectionService projections;

  public WorkforceCredentialLifecycleService(
      WorkerRepository workers,
      WorkerClassAssignmentRepository qualifications,
      WorkerGroupRepository groups,
      WorkerGroupMemberRepository members,
      TaskAssignmentRepository taskAssignments,
      TaskTimeEventRepository events,
      WorkerDeletionIntentRepository deletionIntents,
      WorkerCredentialGateway credentials,
      PlatformTransactionManager transactionManager,
      TaskBoardClientProperties clientProperties,
      JdbcTemplate jdbc,
      WorkerCredentialOperationCoordinator credentialCoordinator,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      WorkforceReadProjectionService projections) {
    this.workers = workers;
    this.qualifications = qualifications;
    this.groups = groups;
    this.members = members;
    this.taskAssignments = taskAssignments;
    this.events = events;
    this.deletionIntents = deletionIntents;
    this.credentials = credentials;
    this.tx = new TransactionTemplate(transactionManager);
    this.clientProperties = clientProperties;
    this.jdbc = jdbc;
    this.credentialCoordinator = credentialCoordinator;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.projections = projections;
  }

  /** Starts a version-fenced credential reset without exposing the secret in emitted facts. */
  WorkerDto resetPassword(UUID warehouseId, UUID id, long expectedVersion, String password) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      CredentialReset prepared =
          tx.execute(
              transaction -> {
                var worker = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(worker);
                checkVersion(worker.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                if (worker.getAppLogin() == null) {
                  throw new ConflictException("У рабочего не настроен логин");
                }
                CredentialStatus completedStatus =
                    worker.getCredentialStatus() == CredentialStatus.DISABLED
                        ? CredentialStatus.DISABLED
                        : CredentialStatus.ACTIVE;
                startCredentialOperation(worker, CredentialOperationType.RESET);
                worker = projectionWriter.save(workers, worker);
                projectionWriter.flush();
                projectionWriter.refresh(worker);
                eventSourcing.workerChanged(
                    worker, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return new CredentialReset(worker, completedStatus);
              });
      try {
        credentials.reset(prepared.worker().getId(), password);
        markCredential(
            id,
            prepared.worker().getCredentialOperationId(),
            prepared.completedStatus(),
            null,
            prepared.worker().getAppLogin());
      } catch (RuntimeException exception) {
        markCredentialFailure(
            id,
            prepared.worker().getCredentialOperationId(),
            safeMessage(exception),
            prepared.worker().getAppLogin());
        throw new ExternalServiceException("Не удалось сбросить учетные данные рабочего", exception);
      }
      return tx.execute(transaction -> projections.workerDto(requireWorker(warehouseId, id)));
    }
  }

  /** Starts durable credential disabling for a version-fenced worker. */
  WorkerDto disableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      Worker worker =
          tx.execute(
              transaction -> {
                var current = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(current);
                checkVersion(current.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                if (current.getAppLogin() == null) {
                  throw new ConflictException("У рабочего не настроен логин");
                }
                startCredentialOperation(current, CredentialOperationType.DISABLE);
                current = projectionWriter.save(workers, current);
                projectionWriter.flush();
                projectionWriter.refresh(current);
                eventSourcing.workerChanged(
                    current, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return current;
              });
      disableCredentials(worker);
      return tx.execute(transaction -> projections.workerDto(requireWorker(warehouseId, id)));
    }
  }

  /** Starts durable credential enabling for a version-fenced worker. */
  WorkerDto enableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      Worker worker =
          tx.execute(
              transaction -> {
                var current = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(current);
                checkVersion(current.getVersion(), expectedVersion, "Рабочий");
                if (current.getAppLogin() == null) {
                  throw new ConflictException("У рабочего не настроен логин");
                }
                if (current.getCredentialStatus() != CredentialStatus.DISABLED) {
                  throw new ConflictException("Вход рабочего уже включен или требует сверки");
                }
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                startCredentialOperation(current, CredentialOperationType.CONFIGURE);
                current = projectionWriter.save(workers, current);
                projectionWriter.flush();
                projectionWriter.refresh(current);
                eventSourcing.workerChanged(
                    current, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return current;
              });
      try {
        credentials.enable(worker.getId());
        markCredential(
            worker.getId(),
            worker.getCredentialOperationId(),
            CredentialStatus.ACTIVE,
            null,
            worker.getAppLogin());
      } catch (RuntimeException exception) {
        markCredentialFailure(
            worker.getId(),
            worker.getCredentialOperationId(),
            safeMessage(exception),
            worker.getAppLogin());
        throw new ExternalServiceException("Не удалось включить учетные данные рабочего", exception);
      }
      return tx.execute(transaction -> projections.workerDto(requireWorker(warehouseId, id)));
    }
  }

  /** Reconciles a failed or pending credential-disable operation. */
  WorkerDto reconcileDisableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    try (var lock = credentialCoordinator.tryAcquire(id)) {
      OffsetDateTime databaseNow = lock.databaseNow();
      CredentialReconciliation prepared =
          tx.execute(
              transaction -> {
                var worker = requireWorker(warehouseId, id);
                checkVersion(worker.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                CredentialOperationType interruptedType = worker.getCredentialOperationType();
                boolean legacyProjection =
                    worker.getCredentialStatus() == CredentialStatus.NOT_CONFIGURED
                        && worker.getAppLogin() == null
                        && worker.getCredentialOperationId() == null
                        && worker.getCredentialOperationType() == null
                        && worker.getCredentialOperationStartedAt() == null;
                if (worker.getCredentialStatus() == CredentialStatus.PENDING
                    || worker.getCredentialStatus() == CredentialStatus.ERROR) {
                  boolean legacyOperation =
                      worker.getCredentialOperationId() == null
                          && worker.getCredentialOperationType() == null
                          && worker.getCredentialOperationStartedAt() == null;
                  if (!legacyOperation
                      && (worker.getCredentialOperationId() == null
                          || worker.getCredentialOperationType() == null
                          || worker.getCredentialOperationStartedAt() == null
                          || worker.getCredentialOperationStartedAt()
                              .plus(clientProperties.credentialOperationTimeout())
                              .isAfter(databaseNow))) {
                    throw new ConflictException(
                        "Незавершенная операция учетных данных еще не истекла");
                  }
                } else if (!legacyProjection) {
                  throw new ConflictException(
                      "Сверка учетных данных доступна только после ошибки или истечения операции");
                }
                startCredentialOperation(worker, CredentialOperationType.RECONCILE_DISABLE);
                worker.touch();
                worker = projectionWriter.save(workers, worker);
                projectionWriter.flush();
                projectionWriter.refresh(worker);
                eventSourcing.workerChanged(
                    worker, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return new CredentialReconciliation(worker, interruptedType);
              });
      reconcileCredentials(prepared);
      return tx.execute(ignored -> projections.workerDto(requireWorker(warehouseId, id)));
    }
  }

  /** Deletes an unreferenced worker after the credential-side operation is settled. */
  void deleteWorker(UUID warehouseId, UUID id, long expectedVersion) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      tx.executeWithoutResult(
          transaction -> {
            Worker worker = requireWorker(warehouseId, id);
            ensureCredentialOperationSettled(worker);
            checkVersion(worker.getVersion(), expectedVersion, "Рабочий");
            ensureWorkerEmpty(id);
            WorkerDeletionIntent intent =
                deletionIntents.findByWorkerId(id).orElseGet(WorkerDeletionIntent::new);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (intent.getId() == null) {
              intent.setWorker(worker);
              intent.setCreatedAt(now);
            }
            intent.setStatus(WorkerDeletionStatus.PENDING_AUTH);
            intent.setLastError(null);
            intent.setUpdatedAt(now);
            deletionIntents.save(intent);
          });
      try {
        credentials.delete(id);
      } catch (RuntimeException exception) {
        markDeletionIntent(id, WorkerDeletionStatus.ERROR, safeMessage(exception));
        throw new ExternalServiceException(
            "Auth-service не удалил учетную запись; рабочий не изменен", exception);
      }
      markDeletionIntent(id, WorkerDeletionStatus.AUTH_DELETED, null);
      try {
        tx.executeWithoutResult(
            transaction -> {
              Worker worker = requireWorker(warehouseId, id);
              checkVersion(worker.getVersion(), expectedVersion, "Рабочий");
              long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
              ensureWorkerEmpty(id);
              WorkerDeletionIntent intent =
                  deletionIntents
                      .findByWorkerId(id)
                      .orElseThrow(() -> new ConflictException("Намерение удаления потеряно"));
              List<WorkerGroupMember> ownedMembers = members.findAllByWorkerId(id);
              List<WorkerGroup> affectedGroups =
                  ownedMembers.stream()
                      .map(WorkerGroupMember::getWorkerGroup)
                      .distinct()
                      .sorted(Comparator.comparing(group -> group.getId().toString()))
                      .toList();
              Map<UUID, Long> groupStreamVersions = new LinkedHashMap<>();
              affectedGroups.forEach(
                  group -> {
                    groupStreamVersions.put(
                        group.getId(),
                        eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, group.getId()));
                    group.touch();
                    projectionWriter.save(groups, group);
                  });
              deletionIntents.delete(intent);
              projectionWriter.deleteAll(members, ownedMembers);
              projectionWriter.deleteAll(
                  qualifications, qualifications.findAllByWorkerId(id));
              projectionWriter.flush();
              affectedGroups.forEach(
                  group -> {
                    projectionWriter.refresh(group);
                    eventSourcing.groupChanged(
                        group,
                        groupStreamVersions.get(group.getId()),
                        TaskBoardEventTypes.WORKER_GROUP_MEMBERS_CHANGED);
                  });
              eventSourcing.deleted(worker, streamVersion);
              projectionWriter.delete(workers, worker);
              projectionWriter.flush();
            });
      } catch (RuntimeException exception) {
        markDeletionIntent(
            id, WorkerDeletionStatus.AUTH_DELETED, "Повторите reconciliation: " + safeMessage(exception));
        throw exception;
      }
    }
  }

  void validateCredentialInput(String login, String password, boolean create) {
    boolean hasLogin = normalizeLogin(login) != null;
    boolean hasPassword = password != null && !password.isBlank();
    if (hasPassword && !hasLogin) {
      throw new ConflictException("Пароль нельзя задать без логина");
    }
    if (create && hasLogin && !hasPassword) {
      throw new ConflictException("Для нового логина нужен пароль");
    }
  }

  WorkforceCredentialProfileUpdate prepareProfileUpdate(Worker worker, WorkerRequest request) {
    String oldLogin = normalizeLogin(worker.getAppLogin());
    String requestedLogin = normalizeLogin(request.appLogin());
    boolean changed = !java.util.Objects.equals(oldLogin, requestedLogin);
    if (changed && requestedLogin != null && normalize(request.password()) == null) {
      throw new ConflictException("Для изменения логина нужен новый пароль");
    }
    if (requestedLogin != null
        && workers.existsByAppLoginIgnoreCaseAndIdNot(requestedLogin, worker.getId())) {
      throw new ConflictException("Логин рабочего уже используется");
    }
    CredentialOperationType operation =
        changed
            ? requestedLogin == null ? CredentialOperationType.CLEAR : CredentialOperationType.CONFIGURE
            : normalize(request.password()) == null ? null : CredentialOperationType.CONFIGURE;
    return new WorkforceCredentialProfileUpdate(worker, operation, oldLogin, requestedLogin);
  }

  /**
   * Applies a previously validated credential intent after profile fields are replaced and before
   * the enclosing worker-change event is persisted.
   */
  void startProfileCredentialOperation(
      Worker worker, WorkforceCredentialProfileUpdate prepared) {
    if (prepared.operation() != null) {
      startCredentialOperation(worker, prepared.operation());
    }
  }

  void configureCreated(Worker created, String password) {
    try (var ignored = credentialCoordinator.tryAcquire(created.getId())) {
      Worker worker =
          tx.execute(
              transaction -> {
                var current = requireWorker(created.getWarehouseId(), created.getId());
                ensureCredentialOperationSettled(current);
                long streamVersion =
                    eventSourcing.lock(TaskBoardAggregateType.WORKER, current.getId());
                startCredentialOperation(current, CredentialOperationType.CONFIGURE);
                current = projectionWriter.save(workers, current);
                projectionWriter.flush();
                projectionWriter.refresh(current);
                eventSourcing.workerChanged(
                    current, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return current;
              });
      try {
        credentials.configure(
            worker.getId(), worker.getWarehouseId(), worker.getAppLogin(), password);
        markCredential(
            worker.getId(),
            worker.getCredentialOperationId(),
            CredentialStatus.ACTIVE,
            null,
            worker.getAppLogin());
      } catch (RuntimeException exception) {
        markCredentialFailure(
            worker.getId(),
            worker.getCredentialOperationId(),
            safeMessage(exception),
            worker.getAppLogin());
      }
    }
  }

  void completeProfileUpdate(WorkforceCredentialProfileUpdate prepared, String password) {
    if (prepared.operation() == CredentialOperationType.CONFIGURE) {
      configureUpdate(prepared, password);
    } else if (prepared.operation() == CredentialOperationType.CLEAR) {
      clearCredentials(prepared);
    }
  }

  void ensureCredentialOperationSettled(Worker worker) {
    if (worker.getCredentialStatus() == CredentialStatus.PENDING) {
      throw new ConflictException(
          "Операция с учетными данными еще выполняется; дождитесь завершения или выполните сверку");
    }
  }

  String normalizeLogin(String value) {
    String normalized = normalize(value);
    return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
  }

  private void configureUpdate(WorkforceCredentialProfileUpdate prepared, String password) {
    boolean externalConfigured = false;
    try {
      credentials.configure(
          prepared.worker().getId(),
          prepared.worker().getWarehouseId(),
          prepared.requestedLogin(),
          password);
      externalConfigured = true;
      markCredential(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          CredentialStatus.ACTIVE,
          null,
          prepared.requestedLogin());
    } catch (RuntimeException exception) {
      if (externalConfigured) {
        try {
          credentials.disable(prepared.worker().getId());
        } catch (RuntimeException compensationFailure) {
          exception.addSuppressed(compensationFailure);
        }
      }
      markCredentialFailure(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          safeMessage(exception),
          prepared.oldLogin());
    }
  }

  private void clearCredentials(WorkforceCredentialProfileUpdate prepared) {
    try {
      credentials.disable(prepared.worker().getId());
      markCredential(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          CredentialStatus.NOT_CONFIGURED,
          null,
          null);
    } catch (RuntimeException exception) {
      markCredentialFailure(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          safeMessage(exception),
          prepared.oldLogin());
      throw new ExternalServiceException("Не удалось отключить учетные данные рабочего", exception);
    }
  }

  private void disableCredentials(Worker worker) {
    try {
      credentials.disable(worker.getId());
      markCredential(
          worker.getId(),
          worker.getCredentialOperationId(),
          CredentialStatus.DISABLED,
          null,
          worker.getAppLogin());
    } catch (RuntimeException exception) {
      markCredentialFailure(
          worker.getId(),
          worker.getCredentialOperationId(),
          safeMessage(exception),
          worker.getAppLogin());
      throw new ExternalServiceException("Не удалось отключить учетные данные рабочего", exception);
    }
  }

  private void reconcileCredentials(CredentialReconciliation prepared) {
    Worker worker = prepared.worker();
    try {
      WorkerCredentialGateway.WorkerCredentialSnapshot status =
          credentials.status(worker.getId(), worker.getWarehouseId());
      if (prepared.interruptedType() == CredentialOperationType.CONFIGURE
          || prepared.interruptedType() == CredentialOperationType.RESET) {
        if (status.status() == WorkerCredentialGateway.WorkerCredentialStatus.ACTIVE) {
          markCredential(
              worker.getId(),
              worker.getCredentialOperationId(),
              CredentialStatus.ACTIVE,
              null,
              normalizeLogin(status.appLogin()));
          return;
        }
        if (status.status() == WorkerCredentialGateway.WorkerCredentialStatus.DISABLED) {
          markCredential(
              worker.getId(),
              worker.getCredentialOperationId(),
              CredentialStatus.DISABLED,
              null,
              normalizeLogin(status.appLogin()));
          return;
        }
        markCredential(
            worker.getId(),
            worker.getCredentialOperationId(),
            CredentialStatus.NOT_CONFIGURED,
            null,
            null);
        return;
      }
      if (status.status() == WorkerCredentialGateway.WorkerCredentialStatus.ACTIVE) {
        credentials.disable(worker.getId());
      }
      boolean preservesLogin =
          prepared.interruptedType() == CredentialOperationType.DISABLE
              || (prepared.interruptedType() == null && status.appLogin() != null);
      markCredential(
          worker.getId(),
          worker.getCredentialOperationId(),
          preservesLogin ? CredentialStatus.DISABLED : CredentialStatus.NOT_CONFIGURED,
          null,
          preservesLogin ? normalizeLogin(status.appLogin()) : null);
    } catch (RuntimeException exception) {
      markCredentialFailure(
          worker.getId(),
          worker.getCredentialOperationId(),
          safeMessage(exception),
          worker.getAppLogin());
      throw new ExternalServiceException("Не удалось сверить учетные данные рабочего", exception);
    }
  }

  private boolean markCredential(
      UUID id,
      UUID expectedOperationId,
      CredentialStatus status,
      String error,
      String appLogin) {
    Boolean applied =
        tx.execute(
            transaction -> {
              var worker = workers.findById(id).orElseThrow();
              if (!java.util.Objects.equals(worker.getCredentialOperationId(), expectedOperationId)) {
                LOGGER.info(
                    "Ignoring stale worker credential completion for worker {} and operation {}",
                    id,
                    expectedOperationId);
                return false;
              }
              long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
              worker.setCredentialStatus(status);
              worker.setCredentialError(error);
              worker.setAppLogin(appLogin);
              clearCredentialOperation(worker);
              worker = projectionWriter.saveAndFlush(workers, worker);
              projectionWriter.refresh(worker);
              eventSourcing.workerChanged(
                  worker, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
              return true;
            });
    return Boolean.TRUE.equals(applied);
  }

  private boolean markCredentialFailure(
      UUID id, UUID expectedOperationId, String error, String appLogin) {
    Boolean applied =
        tx.execute(
            transaction -> {
              var worker = workers.findById(id).orElseThrow();
              if (!java.util.Objects.equals(worker.getCredentialOperationId(), expectedOperationId)) {
                LOGGER.info(
                    "Ignoring stale worker credential failure for worker {} and operation {}",
                    id,
                    expectedOperationId);
                return false;
              }
              long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
              worker.setCredentialStatus(CredentialStatus.ERROR);
              worker.setCredentialError(error);
              worker.setAppLogin(appLogin);
              worker = projectionWriter.saveAndFlush(workers, worker);
              projectionWriter.refresh(worker);
              eventSourcing.workerChanged(
                  worker, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
              return true;
            });
    return Boolean.TRUE.equals(applied);
  }

  private void markDeletionIntent(UUID workerId, WorkerDeletionStatus status, String error) {
    tx.executeWithoutResult(
        ignored -> {
          WorkerDeletionIntent intent =
              deletionIntents
                  .findByWorkerId(workerId)
                  .orElseThrow(() -> new ConflictException("Намерение удаления потеряно"));
          intent.setStatus(status);
          intent.setLastError(error);
          intent.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
          deletionIntents.save(intent);
        });
  }

  private Worker requireWorker(UUID warehouseId, UUID id) {
    var worker = workers.findById(id).orElseThrow(() -> new NotFoundException("Рабочий не найден"));
    if (!worker.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Рабочий не найден");
    }
    return worker;
  }

  private void ensureWorkerEmpty(UUID id) {
    if (taskAssignments.existsByWorkerId(id) || events.existsByWorkerId(id)) {
      throw new ConflictException("Используемого рабочего можно только деактивировать");
    }
  }

  private void startCredentialOperation(Worker worker, CredentialOperationType type) {
    worker.setCredentialStatus(CredentialStatus.PENDING);
    worker.setCredentialError(null);
    worker.setCredentialOperationId(UUID.randomUUID());
    worker.setCredentialOperationType(type);
    worker.setCredentialOperationStartedAt(
        jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class));
  }

  private void clearCredentialOperation(Worker worker) {
    worker.setCredentialOperationId(null);
    worker.setCredentialOperationType(null);
    worker.setCredentialOperationStartedAt(null);
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String safeMessage(RuntimeException exception) {
    LOGGER.warn("Worker credential operation failed: {}", exception.getClass().getName());
    if (exception instanceof HttpClientErrorException.Conflict) {
      return "Логин приложения уже используется";
    }
    return "AUTH_SERVICE_UNAVAILABLE";
  }

  /**
   * Carries the locked worker and interrupted credential intent from local preparation into the
   * auth-service reconciliation call.
   */
  private record CredentialReconciliation(Worker worker, CredentialOperationType interruptedType) {}

  /**
   * Carries a reset operation's locked worker and the credential status to persist only after the
   * matching auth-service effect succeeds.
   */
  private record CredentialReset(Worker worker, CredentialStatus completedStatus) {}
}

/**
 * Credential intent calculated during a profile mutation and completed while its credential lock
 * remains held.
 */
record WorkforceCredentialProfileUpdate(
    Worker worker, CredentialOperationType operation, String oldLogin, String requestedLogin) {
  WorkforceCredentialProfileUpdate withWorker(Worker updatedWorker) {
    return new WorkforceCredentialProfileUpdate(updatedWorker, operation, oldLogin, requestedLogin);
  }
}
