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
  private final GroupKpiEvidenceService kpiEvidence;

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
      TaskBoardProjectionWriter projectionWriter,
      GroupKpiEvidenceService kpiEvidence) {
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
    this.kpiEvidence = kpiEvidence;
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

  public WorkerDto setCurrentGroup(
      UUID warehouseId, UUID workerId, SetCurrentGroupRequest request) {
    return tx.execute(
        transaction -> {
          Worker worker = requireWorker(warehouseId, workerId);
          checkVersion(worker.getVersion(), request.expectedVersion(), "Рабочий");
          if (!taskAssignments
              .findAllByWorkerIdAndStatusIn(
                  workerId, java.util.Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
              .isEmpty()) {
            throw new ConflictException(
                "Нельзя менять текущую группу во время активного задания");
          }
          WorkerGroup selected =
              request.workerGroupId() == null
                  ? null
                  : requireGroup(warehouseId, request.workerGroupId());
          if (selected != null) {
            if (!selected.isActive()
                || selected.getOperationalStatus() != GroupOperationalStatus.AVAILABLE) {
              throw new ConflictException("Выбранная группа недоступна");
            }
            boolean member =
                members.findAllByWorkerGroupIdAndActiveTrue(selected.getId()).stream()
                    .anyMatch(value -> value.getWorker().getId().equals(workerId));
            if (!member) {
              throw new ConflictException(
                  "Рабочий не состоит в выбранной группе");
            }
          }
          UUID previousId =
              worker.getCurrentGroup() == null ? null : worker.getCurrentGroup().getId();
          UUID selectedId = selected == null ? null : selected.getId();
          if (java.util.Objects.equals(previousId, selectedId)) {
            return dto(worker);
          }
          long streamVersion =
              eventSourcing.lock(TaskBoardAggregateType.WORKER, workerId);
          OffsetDateTime changedAt =
              jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
          jdbc.update(
              """
              update worker_current_group_interval
                 set ended_at=?
               where worker_id=? and ended_at is null
              """,
              changedAt,
              workerId);
          if (selected != null) {
            jdbc.update(
                """
                insert into worker_current_group_interval(
                  id,worker_id,worker_group_id,started_at,ended_at)
                values (?,?,?, ?,null)
                """,
                UUID.randomUUID(),
                workerId,
                selected.getId(),
                changedAt);
          }
          worker.setCurrentGroup(selected);
          worker.touch();
          worker = projectionWriter.saveAndFlush(workers, worker);
          projectionWriter.refresh(worker);
          eventSourcing.workerChanged(
              worker, streamVersion, TaskBoardEventTypes.WORKER_CHANGED);
          if (previousId != null) {
            kpiEvidence.refreshGroup(warehouseId, previousId, changedAt);
          }
          if (selectedId != null) {
            kpiEvidence.refreshGroup(warehouseId, selectedId, changedAt);
          }
          return dto(worker);
        });
  }

  WorkerGroupDto disableGroupState(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    return tx.execute(
        transaction -> {
          WorkerGroup group = requireGroup(warehouseId, groupId);
          checkVersion(group.getVersion(), request.expectedVersion(), "Группа");
          if (group.getOperationalStatus() == GroupOperationalStatus.DISABLED) {
            throw new ConflictException("Группа уже отключена");
          }
          long streamVersion =
              eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, groupId);
          OffsetDateTime changedAt =
              jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
          closeAvailabilityInterval(groupId, changedAt);
          jdbc.update(
              """
              insert into worker_group_availability_interval(
                id,worker_group_id,status,reason,started_at,ended_at)
              values (?,?,'DISABLED',?,?,null)
              """,
              UUID.randomUUID(),
              groupId,
              normalize(request.reason()),
              changedAt);
          group.disable(changedAt, request.reason());
          group = projectionWriter.saveAndFlush(groups, group);
          projectionWriter.refresh(group);
          eventSourcing.groupChanged(
              group, streamVersion, TaskBoardEventTypes.WORKER_GROUP_CHANGED);
          return dto(group);
        });
  }

  WorkerGroupDto enableGroupState(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    return tx.execute(
        transaction -> {
          WorkerGroup group = requireGroup(warehouseId, groupId);
          checkVersion(group.getVersion(), request.expectedVersion(), "Группа");
          if (group.getOperationalStatus() == GroupOperationalStatus.AVAILABLE) {
            throw new ConflictException("Группа уже доступна");
          }
          long streamVersion =
              eventSourcing.lock(TaskBoardAggregateType.WORKER_GROUP, groupId);
          OffsetDateTime changedAt =
              jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
          closeAvailabilityInterval(groupId, changedAt);
          jdbc.update(
              """
              insert into worker_group_availability_interval(
                id,worker_group_id,status,reason,started_at,ended_at)
              values (?,?,'AVAILABLE',null,?,null)
              """,
              UUID.randomUUID(),
              groupId,
              changedAt);
          group.enable();
          group = projectionWriter.saveAndFlush(groups, group);
          projectionWriter.refresh(group);
          eventSourcing.groupChanged(
              group, streamVersion, TaskBoardEventTypes.WORKER_GROUP_CHANGED);
          return dto(group);
        });
  }

  public WorkerDto resetPassword(UUID warehouseId, UUID id, long expectedVersion, String password) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      CredentialReset prepared =
          tx.execute(
              s -> {
                var w = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(w);
                checkVersion(w.getVersion(), expectedVersion, "Рабочий");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                if (w.getAppLogin() == null)
                  throw new ConflictException("У рабочего не настроен логин");
                CredentialStatus completedStatus =
                    w.getCredentialStatus() == CredentialStatus.DISABLED
                        ? CredentialStatus.DISABLED
                        : CredentialStatus.ACTIVE;
                startCredentialOperation(w, CredentialOperationType.RESET);
                w = projectionWriter.save(workers, w);
                projectionWriter.flush();
                projectionWriter.refresh(w);
                eventSourcing.workerChanged(w, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return new CredentialReset(w, completedStatus);
              });
      try {
        credentials.reset(prepared.worker().getId(), password);
        markCredential(
            id,
            prepared.worker().getCredentialOperationId(),
            prepared.completedStatus(),
            null,
            prepared.worker().getAppLogin());
      } catch (RuntimeException ex) {
        markCredentialFailure(
            id,
            prepared.worker().getCredentialOperationId(),
            safeMessage(ex),
            prepared.worker().getAppLogin());
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
                if (w.getAppLogin() == null)
                  throw new ConflictException("У рабочего не настроен логин");
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

  public WorkerDto enableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    try (var ignored = credentialCoordinator.tryAcquire(id)) {
      Worker worker =
          tx.execute(
              s -> {
                var w = requireWorker(warehouseId, id);
                ensureCredentialOperationSettled(w);
                checkVersion(w.getVersion(), expectedVersion, "Рабочий");
                if (w.getAppLogin() == null)
                  throw new ConflictException("У рабочего не настроен логин");
                if (w.getCredentialStatus() != CredentialStatus.DISABLED)
                  throw new ConflictException("Вход рабочего уже включен или требует сверки");
                long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORKER, id);
                startCredentialOperation(w, CredentialOperationType.CONFIGURE);
                w = projectionWriter.save(workers, w);
                projectionWriter.flush();
                projectionWriter.refresh(w);
                eventSourcing.workerChanged(
                    w, streamVersion, TaskBoardEventTypes.WORKER_CREDENTIAL_AUDIT);
                return w;
              });
      try {
        credentials.enable(worker.getId());
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
        throw new ExternalServiceException("Не удалось включить учетные данные рабочего", ex);
      }
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
                boolean legacyProjection =
                    w.getCredentialStatus() == CredentialStatus.NOT_CONFIGURED
                        && w.getAppLogin() == null
                        && w.getCredentialOperationId() == null
                        && w.getCredentialOperationType() == null
                        && w.getCredentialOperationStartedAt() == null;
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
                } else if (!legacyProjection) {
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
          jdbc.update(
              """
              insert into worker_group_availability_interval(
                id,worker_group_id,status,reason,started_at,ended_at)
              values (?,?,'AVAILABLE',null,clock_timestamp(),null)
              """,
              UUID.randomUUID(),
              g.getId());
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
          if (members.existsByWorkerGroupId(id)
              || taskAssignments.existsByWorkerGroupId(id))
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
    var classIds =
        queueBindings.stream()
            .map(b -> b.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toSet());
    return active.stream()
        .filter(group -> classIds.contains(group.getWorkerClass().getId()))
        .toList();
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
          CredentialStatus.DISABLED,
          null,
          worker.getAppLogin());
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
    if (taskAssignments.existsByWorkerId(id) || events.existsByWorkerId(id))
      throw new ConflictException("Используемого рабочего можно только деактивировать");
  }

  private void ensureCredentialOperationSettled(Worker worker) {
    if (worker.getCredentialStatus() == CredentialStatus.PENDING) {
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
    var existing = qualifications.findAllByWorkerId(worker.getId());
    projectionWriter.deleteAll(qualifications, existing);
    // Hibernate inserts replacement rows before deferred deletes. Flush the
    // removals so retaining a qualification cannot violate the natural key.
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
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
    Map<UUID, GroupMemberRequest> requestedByWorker = new LinkedHashMap<>();
    if (requested != null) {
      requested.forEach(value -> requestedByWorker.put(value.workerId(), value));
    }
    for (Worker currentWorker : workers.findAllByCurrentGroupId(group.getId())) {
      GroupMemberRequest retained = requestedByWorker.get(currentWorker.getId());
      if (retained == null || !retained.active()) {
        throw new ConflictException(
            "Нельзя исключить рабочего, пока эта группа назначена ему текущей");
      }
    }
    var existing = members.findAllByWorkerGroupId(group.getId());
    projectionWriter.deleteAll(members, existing);
    // Hibernate orders entity inserts before deletes during the final flush.
    // Flush removals first so an unchanged member can be recreated without
    // violating the natural (worker_group_id, worker_id) identity.
    if (!existing.isEmpty()) {
      projectionWriter.flush();
    }
    if (requested == null) return;
    var unique = new LinkedHashMap<UUID, GroupMemberRequest>();
    requested.forEach(r -> unique.put(r.workerId(), r));
    for (var r : unique.values()) {
      var worker = requireWorker(group.getWarehouseId(), r.workerId());
      boolean qualified =
          qualifications.findAllByWorkerId(worker.getId()).stream()
              .anyMatch(
                  qualification ->
                      qualification.isActive()
                          && qualification.getWorkerClass().equals(group.getWorkerClass()));
      if (!qualified) {
        throw new ConflictException(
            "Рабочий "
                + worker.getDisplayName()
                + " не имеет квалификации "
                + group.getWorkerClass().getName());
      }
      var m = new WorkerGroupMember();
      m.setWorkerGroup(group);
      m.setWorker(worker);
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

  private void closeAvailabilityInterval(UUID groupId, OffsetDateTime endedAt) {
    int updated =
        jdbc.update(
            """
            update worker_group_availability_interval
               set ended_at=?
             where worker_group_id=? and ended_at is null
            """,
            endedAt,
            groupId);
    if (updated != 1) {
      throw new IllegalStateException(
          "Открытый интервал доступности группы отсутствует или неоднозначен");
    }
  }

  private String normalizeLogin(String value) {
    String normalized = normalize(value);
    return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
  }

  private String safeMessage(RuntimeException ex) {
    LOGGER.warn("Worker credential operation failed: {}", ex.getClass().getName());
    if (ex instanceof HttpClientErrorException.Conflict) {
      return "Логин приложения уже используется";
    }
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
        w.getCurrentGroup() == null ? null : w.getCurrentGroup().getId(),
        w.getCurrentGroup() == null ? null : w.getCurrentGroup().getName(),
        w.getCurrentGroup() == null
            ? GroupOperationalStatus.DISABLED
            : w.getCurrentGroup().getOperationalStatus(),
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
        g.getOperationalStatus(),
        g.getUnavailableSince(),
        g.getUnavailabilityReason(),
        members.findAllByWorkerGroupId(g.getId()).stream()
            .map(
                m ->
                    new GroupMemberDto(
                        m.getId(),
                        m.getVersion(),
                        m.getWorker().getId(),
                        m.getWorker().getDisplayName(),
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

  private record CredentialReset(Worker worker, CredentialStatus completedStatus) {}
}
