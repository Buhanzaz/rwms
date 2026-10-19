package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.TaskRequirementApiModels.*;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.*;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns missing resources, exact recovery selections and one-time completed work accounting. */
@Service
@RequiredArgsConstructor
public class TaskRequirementService {
  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final TaskSyncSourceRepository sources;
  private final MaintenanceTaskRequirementsGateway maintenance;
  private final ObjectMapper json;
  private final TaskBoardQueuePositionCoordinator positions;
  private final TaskBoardEventSourcing events;
  private final TaskBoardProjectionWriter writer;

  @Transactional(readOnly = true)
  public TaskRequirements get(UUID warehouseId, UUID taskId) {
    BoardTask task = requireTask(warehouseId, taskId);
    return new TaskRequirements(taskId, task.getVersion(), task.isHasProblem(), task.isIncomplete(),
        task.getCompletedWorkPercent(), resolve(task).stream().map(item ->
            new Item(item.itemId(), item.kind(), item.name(), item.state(), item.linkedItemIds())).toList());
  }

  public String state(UUID taskId, UUID itemId) {
    return tasks.findById(taskId).stream().flatMap(task -> stored(task).stream())
        .filter(item -> item.itemId().equals(itemId)).map(TaskRequirement::state)
        .findFirst().orElse("AVAILABLE");
  }

  /** Called after worker/lease authorization, in the report transaction. */
  public void lockEntryMutation(UUID entryId) {
    QueueEntry entry = entries.findById(entryId)
        .orElseThrow(() -> new NotFoundException("Задание не найдено"));
    positions.lockQueueMutation(entry.getTask().getWarehouseId());
    writer.refresh(entry);
    writer.refresh(entry.getTask());
  }

  /** Called after worker/lease authorization, in the report transaction. */
  public List<MissingItem> report(UUID warehouseId, UUID taskId, UUID entryId,
      Long expectedVersion, List<UUID> requestedIds) {
    positions.lockQueueMutation(warehouseId);
    BoardTask task = requireTask(warehouseId, taskId);
    QueueEntry entry = entries.findById(entryId).orElseThrow();
    if (task.getStatus() != TaskStatus.ACTIVE || task.isSuspended()
        || entry.getStatus() != EntryStatus.IN_PROGRESS) {
      throw new ConflictException("Сообщить о проблеме можно только в активном задании");
    }
    if (!requestedIds.isEmpty()) {
      if (expectedVersion == null) throw new IllegalArgumentException("Для отметки позиции нужен expectedVersion");
      RegistryService.checkVersion(entry.getVersion(), expectedVersion, "Этап");
    }
    long version = events.lock(TaskBoardAggregateType.BOARD_TASK, taskId);
    List<TaskRequirement> items = requestedIds.isEmpty() ? stored(task) : resolve(task);
    Set<UUID> requested = new LinkedHashSet<>(requestedIds);
    if (requested.size() != requestedIds.size()) throw new IllegalArgumentException("Позиции должны быть уникальными");
    List<QueueEntry> route = entries.findAllByTaskIdOrderByRouteIndexAsc(taskId);
    Set<UUID> visible = visiblePackageEntryIds(entry, route);
    for (UUID id : requested) {
      TaskRequirement item = item(items, id);
      if (item.entryIds().stream().noneMatch(visible::contains)
          || "COMPLETED".equals(item.state())) {
        throw new ConflictException("Позиция не относится к текущей невыполненной работе");
      }
    }
    Set<UUID> missing = closure(items, requested);
    List<TaskRequirement> changed = items.stream().map(item -> missing.contains(item.itemId())
        && !"COMPLETED".equals(item.state()) ? item.withState("MISSING") : item).toList();
    if (!requested.isEmpty()) saveItems(task, changed);
    task.reportProblem();
    writer.saveAndFlush(tasks, task);
    events.taskChanged(task, version, TaskBoardEventTypes.BOARD_TASK_CHANGED);
    // Entry versions fence a second resource report and offline COMPLETE against the changed state.
    touchEntries(route);
    return changed.stream().filter(item -> missing.contains(item.itemId()) && "MISSING".equals(item.state()))
        .map(item -> new MissingItem(item.itemId(), item.kind(), item.name())).toList();
  }

  /** Undoes an active worker's mark; AVAILABLE differs from a manager-confirmed RESTORED row. */
  public void restoreActive(UUID warehouseId, UUID taskId, UUID entryId,
      long expectedVersion, UUID itemId) {
    positions.lockQueueMutation(warehouseId);
    BoardTask task = requireTask(warehouseId, taskId);
    QueueEntry entry = entries.findById(entryId).orElseThrow();
    if (task.getStatus() != TaskStatus.ACTIVE || task.isSuspended()
        || entry.getStatus() != EntryStatus.IN_PROGRESS) {
      throw new ConflictException("Изменить отметку можно только в активном задании");
    }
    RegistryService.checkVersion(entry.getVersion(), expectedVersion, "Этап");
    List<TaskRequirement> items = resolve(task);
    TaskRequirement selected = item(items, itemId);
    List<QueueEntry> route = entries.findAllByTaskIdOrderByRouteIndexAsc(taskId);
    Set<UUID> visible = visiblePackageEntryIds(entry, route);
    if (!"MISSING".equals(selected.state())
        || selected.entryIds().stream().noneMatch(visible::contains)) {
      throw new ConflictException("Отменить можно только отметку отсутствующей позиции текущей работы");
    }
    Set<UUID> group = closure(items, Set.of(itemId));
    long version = events.lock(TaskBoardAggregateType.BOARD_TASK, taskId);
    saveItems(task, items.stream().map(item -> group.contains(item.itemId())
        && "MISSING".equals(item.state()) ? item.withState("AVAILABLE") : item).toList());
    writer.saveAndFlush(tasks, task);
    events.taskChanged(task, version, TaskBoardEventTypes.BOARD_TASK_CHANGED);
    touchEntries(route);
  }

  /** Applies a report's exact catalog resource identities to active tasks in one locked warehouse. */
  public List<UUID> applyToAll(UUID warehouseId, UUID sourceTaskId, List<UUID> reportedItemIds) {
    positions.lockQueueMutation(warehouseId);
    BoardTask source = requireTask(warehouseId, sourceTaskId);
    List<TaskRequirement> sourceItems = resolve(source);
    Set<UUID> nodes = sourceItems.stream().filter(item -> reportedItemIds.contains(item.itemId()))
        .map(TaskRequirement::catalogNodeId).filter(Objects::nonNull).collect(Collectors.toSet());
    if (nodes.isEmpty()) throw new ConflictException("У выбранных позиций нет связи с каталогом");
    List<UUID> affected = new ArrayList<>();
    for (BoardTask task : tasks.findAllByWarehouseIdAndStatusOrderById(warehouseId, TaskStatus.ACTIVE)) {
      List<TaskRequirement> items = resolve(task);
      Set<UUID> matching = items.stream().filter(item -> nodes.contains(item.catalogNodeId())
          && !"COMPLETED".equals(item.state())).map(TaskRequirement::itemId).collect(Collectors.toSet());
      if (matching.isEmpty()) continue;
      Set<UUID> missing = closure(items, matching);
      long version = events.lock(TaskBoardAggregateType.BOARD_TASK, task.getId());
      saveItems(task, items.stream().map(item -> missing.contains(item.itemId())
          && !"COMPLETED".equals(item.state()) ? item.withState("MISSING") : item).toList());
      task.reportProblem();
      writer.saveAndFlush(tasks, task);
      events.taskChanged(task, version, TaskBoardEventTypes.BOARD_TASK_CHANGED);
      touchEntries(entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()));
      affected.add(task.getId());
    }
    return List.copyOf(affected);
  }

  /** Restores only explicitly confirmed complete linked groups; missing groups remain blocked. */
  public void restore(BoardTask task, List<UUID> availableIds) {
    List<TaskRequirement> items = resolve(task);
    if (items.isEmpty()) {
      if (!availableIds.isEmpty()) throw new IllegalArgumentException("В задании нет этих позиций");
      task.restoreRequirements(false);
      return;
    }
    Set<UUID> selected = new LinkedHashSet<>(availableIds);
    for (UUID id : selected) {
      if (!"MISSING".equals(item(items, id).state())) {
        throw new ConflictException("Восстановить можно только отсутствующие позиции");
      }
    }
    Set<UUID> needed = closure(items, selected).stream().filter(id ->
        "MISSING".equals(item(items, id).state())).collect(Collectors.toSet());
    if (!selected.containsAll(needed)) {
      throw new ConflictException("Подтвердите доступность всех позиций связанной группы");
    }
    List<TaskRequirement> restored = items.stream().map(item -> selected.contains(item.itemId())
        ? item.withState("RESTORED") : item).toList();
    if (restored.stream().noneMatch(item -> "RESTORED".equals(item.state())
        || ("WORK".equals(item.kind()) && "AVAILABLE".equals(item.state())))) {
      throw new ConflictException("Нет доступных работ для восстановления задания");
    }
    saveItems(task, restored);
    task.restoreRequirements(restored.stream().anyMatch(item -> "MISSING".equals(item.state())));
  }

  public boolean hasMissing(BoardTask task) {
    return stored(task).stream().anyMatch(item -> "MISSING".equals(item.state()));
  }

  public boolean hasMissing(BoardTask task, List<QueueEntry> packageEntries) {
    Set<UUID> entryIds = packageEntries.stream().map(QueueEntry::getId).collect(Collectors.toSet());
    return stored(task).stream().anyMatch(item -> "MISSING".equals(item.state())
        && item.entryIds().stream().anyMatch(entryIds::contains));
  }

  /** Remaining entry budget after its available rows are completed, with no cumulative recredit. */
  public void returnBudget(QueueEntry entry, long remainingSeconds) {
    List<TaskRequirement> items = stored(entry.getTask()).stream()
        .filter(item -> "WORK".equals(item.kind()) && item.entryIds().contains(entry.getId())).toList();
    if (items.isEmpty()) {
      entry.resetResponsibilitySegment(remainingSeconds > 0 ? remainingSeconds : entry.getOriginalBudgetSeconds());
      return;
    }
    long fullSeconds = items.stream().mapToLong(TaskRequirement::plannedWorkSeconds).sum();
    long pendingSeconds = items.stream().filter(item -> !"COMPLETED".equals(item.state()))
        .mapToLong(TaskRequirement::plannedWorkSeconds).sum();
    long maximum = new Completion(true, fullSeconds, pendingSeconds)
        .creditedBudget(entry.getOriginalBudgetSeconds());
    entry.retainRequirementBudget(remainingSeconds > 0 ? Math.min(remainingSeconds, maximum) : maximum);
  }

  /** Remaining entry budget after its available rows are completed, with no cumulative recredit. */
  public Map<UUID, Long> remainingBudgets(BoardTask task, List<QueueEntry> packageEntries) {
    List<TaskRequirement> items = stored(task);
    Map<UUID, Long> result = new LinkedHashMap<>();
    for (QueueEntry entry : packageEntries) {
      if (entry.getCurrentBudgetSeconds() == null) continue;
      List<TaskRequirement> pending = items.stream().filter(item -> "WORK".equals(item.kind())
          && !"COMPLETED".equals(item.state()) && item.entryIds().contains(entry.getId())).toList();
      long total = pending.stream().mapToLong(TaskRequirement::plannedWorkSeconds).sum();
      long available = pending.stream().filter(item -> !"MISSING".equals(item.state()))
          .mapToLong(TaskRequirement::plannedWorkSeconds).sum();
      long credited = new Completion(true, total, available).creditedBudget(entry.getCurrentBudgetSeconds());
      result.put(entry.getId(), entry.getCurrentBudgetSeconds() - credited);
    }
    return result;
  }

  /** Credits only work newly completed in this execution package and returns its budget share. */
  public Completion completeAvailable(BoardTask task, List<QueueEntry> packageEntries) {
    List<TaskRequirement> items = stored(task);
    if (items.isEmpty()) return new Completion(false, 0, 0);
    Set<UUID> entryIds = packageEntries.stream().map(QueueEntry::getId).collect(Collectors.toSet());
    List<TaskRequirement> pending = items.stream().filter(item -> "WORK".equals(item.kind())
        && !"COMPLETED".equals(item.state())
        && item.entryIds().stream().anyMatch(entryIds::contains)).toList();
    long pendingSeconds = pending.stream().mapToLong(TaskRequirement::plannedWorkSeconds).sum();
    long availableSeconds = pending.stream().filter(item -> !"MISSING".equals(item.state()))
        .mapToLong(TaskRequirement::plannedWorkSeconds).sum();
    Set<UUID> completeIds = items.stream().filter(item -> !"MISSING".equals(item.state())
        && !"COMPLETED".equals(item.state()) && item.entryIds().stream().anyMatch(entryIds::contains))
        .map(TaskRequirement::itemId).collect(Collectors.toSet());
    saveItems(task, items.stream().map(item -> completeIds.contains(item.itemId())
        ? item.withState("COMPLETED") : item).toList());
    return new Completion(true, pendingSeconds, availableSeconds);
  }

  public record Completion(boolean tracked, long pendingSeconds, long completedSeconds) {
    public long creditedBudget(long budget) {
      if (pendingSeconds == 0 || completedSeconds == 0) return 0;
      return BigDecimal.valueOf(budget).multiply(BigDecimal.valueOf(completedSeconds))
          .divide(BigDecimal.valueOf(pendingSeconds), 0, RoundingMode.FLOOR).longValueExact();
    }
  }

  private void touchEntries(List<QueueEntry> route) {
    for (QueueEntry entry : route) {
      long version = events.lock(TaskBoardAggregateType.QUEUE_ENTRY, entry.getId());
      entry.requirementsChanged();
      writer.saveAndFlush(entries, entry);
      events.entryChanged(entry, version, TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
    }
  }

  public BoardTask requireTask(UUID warehouseId, UUID taskId) {
    return tasks.findById(taskId).filter(task -> warehouseId.equals(task.getWarehouseId()))
        .orElseThrow(() -> new NotFoundException("Задание не найдено"));
  }

  public List<TaskRequirement> stored(BoardTask task) {
    return read(task.getRequirements(), new TypeReference<>() {});
  }

  private List<TaskRequirement> resolve(BoardTask task) {
    List<TaskRequirement> persisted = stored(task);
    var source = sources.findById(task.getId()).orElse(null);
    if (source != null && source.getSourceType() == TaskSourceType.MAINTENANCE_REPAIR) {
      Set<UUID> doneEntries = entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
          .filter(entry -> entry.getStatus() == EntryStatus.DONE).map(QueueEntry::getId).collect(Collectors.toSet());
      var sourceItems = maintenance.read(task.getWarehouseId(), source.getSourceId()).items();
      if (!persisted.isEmpty()) {
        // Recompute only dependency links in the frozen source version; retain execution history.
        Map<UUID, List<UUID>> links = sourceItems.stream().collect(Collectors.toMap(
            MaintenanceTaskRequirementsGateway.Requirement::itemId,
            MaintenanceTaskRequirementsGateway.Requirement::linkedItemIds));
        return persisted.stream().map(item -> new TaskRequirement(item.itemId(), item.kind(),
            item.name(), item.catalogVersionId(), item.catalogNodeId(), item.plannedWorkSeconds(),
            links.getOrDefault(item.itemId(), item.linkedItemIds()), item.entryIds(), item.state())).toList();
      }
      return sourceItems.stream()
          .map(item -> new TaskRequirement(item.itemId(), item.kind(), item.name(),
              item.catalogVersionId(), item.catalogNodeId(), item.plannedWorkSeconds(),
              item.linkedItemIds(), item.entryIds(), !item.entryIds().isEmpty()
                  && doneEntries.containsAll(item.entryIds()) ? "COMPLETED" : "AVAILABLE")).toList();
    }
    if (!persisted.isEmpty()) return persisted;
    Map<UUID, TaskRequirement> items = new LinkedHashMap<>();
    for (QueueEntry entry : entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId())) {
      List<TaskWorkSnapshotRequest> works = read(entry.getWorkerWorks(), new TypeReference<>() {});
      for (var work : works) items.putIfAbsent(work.id(), new TaskRequirement(work.id(), "WORK",
          work.name(), null, null, work.durationMinutes() == null ? 0 :
              BigDecimal.valueOf(work.quantity()).multiply(BigDecimal.valueOf(work.durationMinutes() * 60L))
                  .setScale(0, RoundingMode.CEILING).longValueExact(),
          List.of(), List.of(entry.getId()), "AVAILABLE"));
      List<TaskMaterialSnapshotRequest> materials = read(entry.getWorkerMaterials(), new TypeReference<>() {});
      for (var material : materials) items.putIfAbsent(material.id(), new TaskRequirement(material.id(),
          "MATERIAL", material.name(), null, null, 0, List.of(), List.of(entry.getId()), "AVAILABLE"));
    }
    return List.copyOf(items.values());
  }

  private Set<UUID> visiblePackageEntryIds(QueueEntry current, List<QueueEntry> route) {
    Set<UUID> result = new HashSet<>();
    int index = route.indexOf(current);
    for (int i = index; i >= 0 && route.get(i).getQueue().equals(current.getQueue()); i--) result.add(route.get(i).getId());
    for (int i = index + 1; i < route.size() && route.get(i).getQueue().equals(current.getQueue()); i++) result.add(route.get(i).getId());
    return result;
  }

  private static TaskRequirement item(List<TaskRequirement> items, UUID id) {
    return items.stream().filter(item -> item.itemId().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Позиция не найдена в задании"));
  }

  static Set<UUID> closure(List<TaskRequirement> items, Set<UUID> initial) {
    Set<UUID> result = new LinkedHashSet<>(initial);
    boolean changed;
    do {
      changed = false;
      for (TaskRequirement item : items) {
        if (result.contains(item.itemId()) || item.linkedItemIds().stream().anyMatch(result::contains)) {
          changed |= result.add(item.itemId());
          changed |= result.addAll(item.linkedItemIds());
        }
      }
    } while (changed);
    return result;
  }

  private void saveItems(BoardTask task, List<TaskRequirement> items) {
    long total = items.stream().filter(item -> "WORK".equals(item.kind()))
        .mapToLong(TaskRequirement::plannedWorkSeconds).sum();
    long done = items.stream().filter(item -> "WORK".equals(item.kind()) && "COMPLETED".equals(item.state()))
        .mapToLong(TaskRequirement::plannedWorkSeconds).sum();
    double percent = total == 0 ? 0 : BigDecimal.valueOf(done).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).doubleValue();
    task.recordRequirements(write(items), percent);
  }

  public String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException("Cannot encode task requirements", exception); }
  }

  public <T> T read(String value, TypeReference<T> type) {
    try { return json.readValue(value, type); }
    catch (Exception exception) { throw new IllegalStateException("Cannot decode task requirements", exception); }
  }
}
