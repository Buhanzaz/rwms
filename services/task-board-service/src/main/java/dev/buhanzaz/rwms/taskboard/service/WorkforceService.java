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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkforceService {
  private static final Logger LOGGER = LoggerFactory.getLogger(WorkforceService.class);
  private final WorkerRepository workers;
  private final WorkerClassAssignmentRepository qualifications;
  private final WorkerGroupRepository groups;
  private final WorkerGroupMemberRepository members;
  private final TaskAssignmentRepository taskAssignments;
  private final TaskTimeEventRepository events;
  private final WorkerDeletionIntentRepository deletionIntents;
  private final RegistryService registry;
  private final WorkerCredentialGateway credentials;
  private final TransactionTemplate tx;
  private final TaskBoardClientProperties clientProperties;
  private final JdbcTemplate jdbc;
  private final WorkerCredentialOperationCoordinator credentialCoordinator;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  public WorkforceService(
      WorkerRepository workers,
      WorkerClassAssignmentRepository qualifications,
      WorkerGroupRepository groups,
      WorkerGroupMemberRepository members,
      TaskAssignmentRepository taskAssignments,
      TaskTimeEventRepository events,
      WorkerDeletionIntentRepository deletionIntents,
      RegistryService registry,
      WorkerCredentialGateway credentials,
      PlatformTransactionManager transactionManager,
      TaskBoardClientProperties clientProperties,
      JdbcTemplate jdbc,
      WorkerCredentialOperationCoordinator credentialCoordinator,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter) {
    this.workers = workers;
    this.qualifications = qualifications;
    this.groups = groups;
    this.members = members;
    this.taskAssignments = taskAssignments;
    this.events = events;
    this.deletionIntents = deletionIntents;
    this.registry = registry;
    this.credentials = credentials;
    this.tx = new TransactionTemplate(transactionManager);
    this.clientProperties = clientProperties;
    this.jdbc = jdbc;
    this.credentialCoordinator = credentialCoordinator;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
  }

  public List<WorkerDto> listWorkers(UUID warehouseId) {
    return tx.execute(
        s ->
            workers.findAllByWarehouseIdOrderByDisplayNameAsc(warehouseId).stream()
                .map(this::dto)
                .toList());
  }

  public WorkerDto createWorker(UUID warehouseId, WorkerRequest request) {
    validateCredentialInput(request.appLogin(), request.password(), true);
    Worker saved =
        tx.execute(
            s -> {
              var w = new Worker();
              w.setWarehouseId(warehouseId);
              applyProfile(w, request);
              w.setAppLogin(normalizeLogin(request.appLogin()));
              w.setCredentialStatus(CredentialStatus.NOT_CONFIGURED);
              w = projectionWriter.save(workers, w);
              replaceQualifications(w, request.qualifications());
              projectionWriter.flush();
              projectionWriter.refresh(w);
              eventSourcing.created(w);
              return w;
            });
    if (saved.getAppLogin() != null) configureCreated(saved, request.password());
    return tx.execute(s -> dto(requireWorker(warehouseId, saved.getId())));
  }

  public WorkerDto updateWorker(UUID warehouseId, UUID id, WorkerRequest request) {
    validateCredentialInput(request.appLogin(), request.password(), false);
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      CredentialUpdate prepared =
          tx.execute(
              s -> {
                var w = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(w);
                checkVersion(w.getVersion(), request.version(), "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                String oldLogin = normalizeLogin(w.getAppLogin());
                String requestedLogin = normalizeLogin(request.appLogin());
                boolean changed = !java.util.Objects.equals(oldLogin, requestedLogin);
                if (changed && requestedLogin != null && normalize(request.password()) == null)
                  throw new ConflictException("Для изменения логина нужен новый пароль");
                if (requestedLogin != null
                    && workers.existsByAppLoginIgnoreCaseAndIdNot(requestedLogin, id))
                  throw new ConflictException("Логин рабочего уже используется");
                CredentialOperation operation =
                    changed
                        ? requestedLogin == null
                            ? CredentialOperation.CLEAR
                            : CredentialOperation.CONFIGURE
                        : normalize(request.password()) == null
                            ? CredentialOperation.NONE
                            : CredentialOperation.CONFIGURE;
                applyProfile(w, request);
                if (operation != CredentialOperation.NONE) {
                  startCredentialOperation(w, operation.type());
                }
                w.touch();
                w = projectionWriter.save(workers, w);
                replaceQualifications(w, request.qualifications());
                projectionWriter.flush();
                projectionWriter.refresh(w);
                eventSourcing.workerChanged(w, streamVersion, TaskBoardEventTypes.WORKER_CHANGED);
                return new CredentialUpdate(w, operation, oldLogin, requestedLogin);
              });
      if (prepared.operation() == CredentialOperation.CONFIGURE) {
        configureUpdate(prepared, request.password());
      } else if (prepared.operation() == CredentialOperation.CLEAR) {
        clearCredentials(prepared);
      }
      return tx.execute(s -> dto(requireWorker(warehouseId, id)));
    }
  }

  public WorkerDto resetPassword(UUID warehouseId, UUID id, long expectedVersion, String password) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      Worker worker =
          tx.execute(
              s -> {
                var w = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(w);
                checkVersion(w.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                if (w.getAppLogin() == null)
                  throw new ConflictException("У рабочего не настроен логин");
                startCredentialOperation(w, CredentialOperationType.RESET);
                w = projectionWriter.save(workers, w);
                projectionWriter.flush();
                projectionWriter.refresh(w);
                eventSourcing.workerChanged(w, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return w;
              });
      try {
        credentials.reset(worker.getId(), password);
        markCredential(
            id,
            worker.getCredentialOperationId(),
            CredentialStatus.ACTIVE,
            null,
            worker.getAppLogin());
      } catch (RuntimeException ex) {
        markCredentialFailure(
            id,
            worker.getCredentialOperationId(),
            safeMessage(ex),
            worker.getAppLogin());
        throw new ExternalServiceException("Не удалось сбросить учетные данные рабочего", ex);
      }
      return tx.execute(s -> dto(requireWorker(warehouseId, id)));
    }
  }

  public WorkerDto disableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      Worker worker =
          tx.execute(
              s -> {
                var w = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(w);
                checkVersion(w.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                startCredentialOperation(w, CredentialOperationType.DISABLE);
                w = projectionWriter.save(workers, w);
                projectionWriter.flush();
                projectionWriter.refresh(w);
                eventSourcing.workerChanged(w, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return w;
              });
      disableCredentials(worker);
      return tx.execute(s -> dto(requireWorker(warehouseId, id)));
    }
  }

  public WorkerDto reconcileDisableCredentials(
      UUID warehouseId, UUID id, long expectedVersion) {
    try (var lock = credentialCoordinator.tryAcquire(id)) {
      OffsetDateTime databaseNow = lock.databaseNow();
      CredentialReconciliation prepared =
          tx.execute(
              s -> {
                var w = requireWorker(warehouseId, id);
                checkVersion(w.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                CredentialOperationType interruptedType = w.getCredentialOperationType();
                if (w.getCredentialStatus() == CredentialStatus.PENDING
                    || w.getCredentialStatus() == CredentialStatus.ERROR) {
                  boolean legacyOperation =
                      w.getCredentialOperationId() == null
                          && w.getCredentialOperationType() == null
                          && w.getCredentialOperationStartedAt() == null;
                  if (!legacyOperation
                      && (w.getCredentialOperationId() == null
                          || w.getCredentialOperationType() == null
                          || w.getCredentialOperationStartedAt() == null
                          || w.getCredentialOperationStartedAt()
                              .plus(clientProperties.credentialOperationTimeout())
                              .isAfter(databaseNow))) {
                    throw new ConflictException(
                        "Незавершенная операция учетных данных еще не истекла");
                  }
                } else {
                  throw new ConflictException(
                      "Сверка учетных данных доступна только после ошибки или истечения операции");
                }
                startCredentialOperation(w, CredentialOperationType.RECONCILE_DISABLE);
                w.touch();
                w = projectionWriter.save(workers, w);
                projectionWriter.flush();
                projectionWriter.refresh(w);
                eventSourcing.workerChanged(w, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return new CredentialReconciliation(w, interruptedType);
              });
      reconcileCredentials(prepared);
      return tx.execute(s -> dto(requireWorker(warehouseId, id)));
    }
  }

  public void deleteWorker(UUID warehouseId, UUID id, long expectedVersion) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      tx.executeWithoutResult(
          s -> {
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
      } catch (RuntimeException ex) {
        markDeletionIntent(id, WorkerDeletionStatus.ERROR, safeMessage(ex));
        throw new ExternalServiceException(
            "Auth-service не удалил учетную запись; рабочий не изменен", ex);
      }
      markDeletionIntent(id, WorkerDeletionStatus.AUTH_DELETED, null);
      try {
        tx.executeWithoutResult(
            s -> {
              Worker worker = requireWorker(warehouseId, id);
              checkVersion(worker.getVersion(), expectedVersion, "Рабочий");
              long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
              ensureWorkerEmpty(id);
              WorkerDeletionIntent intent =
                  deletionIntents
                      .findByWorkerId(id)
                      .orElseThrow(() -> new ConflictException("Намерение удаления потеряно"));
              deletionIntents.delete(intent);
              eventSourcing.deleted(worker, streamVersion);
              projectionWriter.delete(workers, worker);
              projectionWriter.flush();
            });
      } catch (RuntimeException ex) {
        markDeletionIntent(
            id, WorkerDeletionStatus.AUTH_DELETED, "Повторите reconciliation: " + safeMessage(ex));
        throw ex;
      }
    }
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

  public List<WorkerGroupDto> listGroups(UUID warehouseId) {
    return tx.execute(
        s ->
            groups.findAllByWarehouseIdOrderByNameAsc(warehouseId).stream()
                .map(this::dto)
                .toList());
  }

  public WorkerGroupDto createGroup(UUID warehouseId, WorkerGroupRequest request) {
    return tx.execute(
        s -> {
          var g = new WorkerGroup();
          g.setWarehouseId(warehouseId);
          apply(g, request);
          g = projectionWriter.save(groups, g);
          replaceMembers(g, request.members());
          projectionWriter.flush();
          projectionWriter.refresh(g);
          eventSourcing.created(g);
          return dto(g);
        });
  }

  public WorkerGroupDto updateGroup(UUID warehouseId, UUID id, WorkerGroupRequest request) {
    return tx.execute(
        s -> {
          var g = requireGroup(warehouseId, id);
          checkVersion(g.getVersion(), request.version(), "Группа");
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, id);
          apply(g, request);
          g.touch();
          g = projectionWriter.save(groups, g);
          replaceMembers(g, request.members());
          projectionWriter.flush();
          projectionWriter.refresh(g);
          eventSourcing.groupChanged(g, streamVersion, TaskBoardEventTypes.WORKER_GROUP_CHANGED);
          return dto(g);
        });
  }

  public void deleteGroup(UUID warehouseId, UUID id, long expectedVersion) {
    tx.executeWithoutResult(
        s -> {
          var g = requireGroup(warehouseId, id);
          checkVersion(g.getVersion(), expectedVersion, "Группа");
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, id);
          if (members.existsByWorkerGroupId(id) || taskAssignments.existsByWorkerGroupId(id))
            throw new ConflictException("Используемую группу можно только деактивировать");
          eventSourcing.deleted(g, streamVersion);
          projectionWriter.delete(groups, g);
          projectionWriter.flush();
        });
  }

  public List<WorkerGroup> eligibleGroups(
      WorkQueue queue, List<WorkQueueClassBinding> queueBindings) {
    var active = groups.findAllByWarehouseIdAndActiveTrueOrderByNameAsc(queue.getWarehouseId());
    if (queueBindings.isEmpty()) return active;
    var ids =
        queueBindings.stream()
            .map(b -> b.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toSet());
    return active.stream().filter(g -> ids.contains(g.getWorkerClass().getId())).toList();
  }

  public Worker requireWorker(UUID warehouseId, UUID id) {
    var w = workers.findById(id).orElseThrow(() -> new NotFoundException("Рабочий не найден"));
    if (!w.getWarehouseId().equals(warehouseId)) throw new NotFoundException("Рабочий не найден");
    return w;
  }

  public WorkerGroup requireGroup(UUID warehouseId, UUID id) {
    var g = groups.findById(id).orElseThrow(() -> new NotFoundException("Группа не найдена"));
    if (!g.getWarehouseId().equals(warehouseId)) throw new NotFoundException("Группа не найдена");
    return g;
  }

  public List<WorkerClassAssignment> activeQualifications(UUID workerId) {
    return qualifications.findAllByWorkerId(workerId).stream()
        .filter(WorkerClassAssignment::isActive)
        .toList();
  }

  private void configureCreated(Worker created, String password) {
    try (var ignored = credentialCoordinator.tryAcquire(created.getId())) {
      Worker worker =
          tx.execute(
              transaction -> {
                var current = requireWorker(created.getWarehouseId(), created.getId());
                ensureCredentialOperationSettled(current);
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, current.getId());
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
      } catch (RuntimeException ex) {
        markCredentialFailure(
            worker.getId(),
            worker.getCredentialOperationId(),
            safeMessage(ex),
            worker.getAppLogin());
      }
    }
  }

  private void configureUpdate(CredentialUpdate prepared, String password) {
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
    } catch (RuntimeException ex) {
      if (externalConfigured) {
        try {
          credentials.disable(prepared.worker().getId());
        } catch (RuntimeException compensationFailure) {
          ex.addSuppressed(compensationFailure);
        }
      }
      markCredentialFailure(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          safeMessage(ex),
          prepared.oldLogin());
    }
  }

  private void clearCredentials(CredentialUpdate prepared) {
    try {
      credentials.disable(prepared.worker().getId());
      markCredential(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          CredentialStatus.NOT_CONFIGURED,
          null,
          null);
    } catch (RuntimeException ex) {
      markCredentialFailure(
          prepared.worker().getId(),
          prepared.worker().getCredentialOperationId(),
          safeMessage(ex),
          prepared.oldLogin());
      throw new ExternalServiceException("Не удалось отключить учетные данные рабочего", ex);
    }
  }

  private void disableCredentials(Worker worker) {
    try {
      credentials.disable(worker.getId());
      markCredential(
          worker.getId(),
          worker.getCredentialOperationId(),
          CredentialStatus.NOT_CONFIGURED,
          null,
          null);
    } catch (RuntimeException ex) {
      markCredentialFailure(
          worker.getId(),
          worker.getCredentialOperationId(),
          safeMessage(ex),
          worker.getAppLogin());
      throw new ExternalServiceException("Не удалось отключить учетные данные рабочего", ex);
    }
  }

  private void reconcileCredentials(CredentialReconciliation prepared) {
    Worker worker = prepared.worker();
    try {
      if (prepared.interruptedType() == CredentialOperationType.CONFIGURE
          || prepared.interruptedType() == CredentialOperationType.RESET) {
        WorkerCredentialGateway.WorkerCredentialSnapshot status =
            credentials.status(worker.getId(), worker.getWarehouseId());
        if (status.status() == WorkerCredentialGateway.WorkerCredentialStatus.ACTIVE) {
          markCredential(
              worker.getId(),
              worker.getCredentialOperationId(),
              CredentialStatus.ACTIVE,
              null,
              normalizeLogin(status.appLogin()));
          return;
        }
      }
      credentials.disable(worker.getId());
      markCredential(
          worker.getId(),
          worker.getCredentialOperationId(),
          CredentialStatus.NOT_CONFIGURED,
          null,
          null);
    } catch (RuntimeException ex) {
      markCredentialFailure(
          worker.getId(),
          worker.getCredentialOperationId(),
          safeMessage(ex),
          worker.getAppLogin());
      throw new ExternalServiceException("Не удалось сверить учетные данные рабочего", ex);
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
        s -> {
          var w = workers.findById(id).orElseThrow();
          if (!java.util.Objects.equals(w.getCredentialOperationId(), expectedOperationId)) {
            LOGGER.info(
                "Ignoring stale worker credential completion for worker {} and operation {}",
                id,
                expectedOperationId);
            return false;
          }
          long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
          w.setCredentialStatus(status);
          w.setCredentialError(error);
          w.setAppLogin(appLogin);
          clearCredentialOperation(w);
          w = projectionWriter.saveAndFlush(workers, w);
          projectionWriter.refresh(w);
          eventSourcing.workerChanged(w, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
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
              if (!java.util.Objects.equals(
                  worker.getCredentialOperationId(), expectedOperationId)) {
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

  private void ensureWorkerEmpty(UUID id) {
    if (members.existsByWorkerId(id)
        || qualifications.existsByWorkerId(id)
        || taskAssignments.existsByWorkerId(id)
        || events.existsByWorkerId(id))
      throw new ConflictException("Используемого рабочего можно только деактивировать");
  }

  private void ensureCredentialOperationSettled(Worker worker) {
    if (worker.getCredentialStatus() == CredentialStatus.PENDING
        || (worker.getCredentialStatus() == CredentialStatus.ERROR
            && worker.getCredentialOperationId() != null)) {
      throw new ConflictException(
          "Операция с учетными данными еще выполняется; дождитесь завершения или выполните сверку");
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

  private void replaceQualifications(Worker worker, List<QualificationRequest> requested) {
    projectionWriter.deleteAll(qualifications, qualifications.findAllByWorkerId(worker.getId()));
    if (requested == null) return;
    var unique = new LinkedHashMap<UUID, QualificationRequest>();
    requested.forEach(r -> unique.put(r.workerClassId(), r));
    for (var r : unique.values()) {
      var q = new WorkerClassAssignment();
      q.setWorker(worker);
      q.setWorkerClass(registry.requireClass(r.workerClassId()));
      q.setActive(r.active());
      q.setComment(r.comment());
      projectionWriter.save(qualifications, q);
    }
  }

  private void replaceMembers(WorkerGroup group, List<GroupMemberRequest> requested) {
    projectionWriter.deleteAll(members, members.findAllByWorkerGroupId(group.getId()));
    if (requested == null) return;
    var unique = new LinkedHashMap<UUID, GroupMemberRequest>();
    requested.forEach(r -> unique.put(r.workerId(), r));
    for (var r : unique.values()) {
      var worker = requireWorker(group.getWarehouseId(), r.workerId());
      var m = new WorkerGroupMember();
      m.setWorkerGroup(group);
      m.setWorker(worker);
      m.setRoleInGroup(r.roleInGroup());
      m.setActive(r.active());
      projectionWriter.save(members, m);
    }
  }

  private void applyProfile(Worker w, WorkerRequest r) {
    w.setDisplayName(r.displayName());
    w.setFirstName(r.firstName());
    w.setLastName(r.lastName());
    w.setMiddleName(r.middleName());
    w.setActive(r.active());
    w.setComment(r.comment());
  }

  private void apply(WorkerGroup g, WorkerGroupRequest r) {
    g.setWorkerClass(registry.requireClass(r.workerClassId()));
    g.setName(r.name().trim());
    g.setDescription(r.description());
    g.setActive(r.active());
  }

  private void validateCredentialInput(String login, String password, boolean create) {
    boolean hasLogin = normalizeLogin(login) != null;
    boolean hasPassword = password != null && !password.isBlank();
    if (hasPassword && !hasLogin) throw new ConflictException("Пароль нельзя задать без логина");
    if (create && hasLogin && !hasPassword)
      throw new ConflictException("Для нового логина нужен пароль");
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String normalizeLogin(String value) {
    String normalized = normalize(value);
    return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
  }

  private String safeMessage(RuntimeException ex) {
    LOGGER.warn("Worker credential operation failed: {}", ex.getClass().getName());
    return "AUTH_SERVICE_UNAVAILABLE";
  }

  private WorkerDto dto(Worker w) {
    return new WorkerDto(
        w.getId(),
        w.getVersion(),
        w.getWarehouseId(),
        w.getDisplayName(),
        w.getFirstName(),
        w.getLastName(),
        w.getMiddleName(),
        w.isActive(),
        w.getComment(),
        w.getAppLogin(),
        w.getCredentialStatus(),
        w.getCredentialError(),
        qualifications.findAllByWorkerId(w.getId()).stream()
            .map(
                q ->
                    new QualificationDto(
                        q.getId(),
                        q.getVersion(),
                        registry.dto(q.getWorkerClass()),
                        q.isActive(),
                        q.getComment()))
            .toList());
  }

  private WorkerGroupDto dto(WorkerGroup g) {
    return new WorkerGroupDto(
        g.getId(),
        g.getVersion(),
        g.getWarehouseId(),
        registry.dto(g.getWorkerClass()),
        g.getName(),
        g.getDescription(),
        g.isActive(),
        members.findAllByWorkerGroupId(g.getId()).stream()
            .map(
                m ->
                    new GroupMemberDto(
                        m.getId(),
                        m.getVersion(),
                        m.getWorker().getId(),
                        m.getWorker().getDisplayName(),
                        m.getRoleInGroup(),
                        m.isActive()))
            .toList());
  }

  private enum CredentialOperation {
    NONE(null),
    CONFIGURE(CredentialOperationType.CONFIGURE),
    CLEAR(CredentialOperationType.CLEAR);

    private final CredentialOperationType type;

    CredentialOperation(CredentialOperationType type) {
      this.type = type;
    }

    CredentialOperationType type() {
      return type;
    }
  }

  private record CredentialUpdate(
      Worker worker, CredentialOperation operation, String oldLogin, String requestedLogin) {}

  private record CredentialReconciliation(
      Worker worker, CredentialOperationType interruptedType) {}
}
