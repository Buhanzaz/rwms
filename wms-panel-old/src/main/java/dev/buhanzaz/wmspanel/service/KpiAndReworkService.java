package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessKind;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.TaskAssignment;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class KpiAndReworkService {

    private final DataManager dataManager;
    private final WorkerManagementService workerManagementService;

    public KpiAndReworkService(DataManager dataManager,
                               WorkerManagementService workerManagementService) {
        this.dataManager = dataManager;
        this.workerManagementService = workerManagementService;
    }

    public List<WorkerClass> loadWorkerClasses() {
        return workerManagementService.loadActiveWorkerClasses();
    }

    public List<WorkerGroup> loadGroupsForClass(UUID workerClassId) {
        if (workerClassId == null) {
            return List.of();
        }
        return workerManagementService.loadVisibleWorkerGroups().stream()
                .filter(group -> Boolean.TRUE.equals(group.getActive()))
                .filter(group -> group.getWorkerClass() != null && Objects.equals(group.getWorkerClass().getId(), workerClassId))
                .sorted(Comparator
                        .comparing((WorkerGroup group) -> warehouseSortOrder(group.getWarehouse()), Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(group -> warehouseName(group.getWarehouse()), Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(WorkerGroup::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    public GroupHistoryResult loadHistory(UUID workerGroupId) {
        if (workerGroupId == null) {
            return new GroupHistoryResult(List.of(), false);
        }

        List<TaskAssignment> assignments = dataManager.load(TaskAssignment.class)
                .query("""
                        select e from TaskAssignment e
                        where e.workerGroup.id = :workerGroupId
                        order by coalesce(e.finishedAt, e.startedAt, e.assignedAt, e.createdDate) desc, e.id desc
                        """)
                .parameter("workerGroupId", workerGroupId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("workerGroup", nested -> nested.addFetchPlan("_base")
                                .add("workerClass", "_base")
                                .add("warehouse", "_base"))
                        .add("worker", "_base")
                        .add("queueEntry", nested -> nested.addFetchPlan("_base")
                                .add("queue", queue -> queue.addFetchPlan("_base").add("warehouse", "_base"))
                                .add("task", task -> task.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("rentalItem", rentalItem -> rentalItem.addFetchPlan("_base").add("warehouse", "_base"))
                                        .add("repairProcess", process -> process.addFetchPlan("_base")
                                                .add("estimate", "_base")
                                                .add("warehouse", "_base")
                                                .add("rentalItem", "_base")))))
                .list();

        if (assignments.isEmpty()) {
            return new GroupHistoryResult(List.of(), false);
        }

        Map<UUID, List<TaskAssignment>> assignmentsByEntryId = new LinkedHashMap<>();
        for (TaskAssignment assignment : assignments) {
            QueueEntry queueEntry = assignment.getQueueEntry();
            if (queueEntry == null || queueEntry.getId() == null) {
                continue;
            }
            assignmentsByEntryId.computeIfAbsent(queueEntry.getId(), ignored -> new ArrayList<>()).add(assignment);
        }

        Map<UUID, List<RentalItemEventPhoto>> photosByEntryId = loadPhotosByQueueEntryId(assignmentsByEntryId.keySet());
        Map<UUID, String> fallbackPerformersByEntryId = loadFallbackPerformersByEntryId(assignmentsByEntryId.keySet(), workerGroupId);

        List<GroupHistoryEntry> history = assignmentsByEntryId.values().stream()
                .map(entryAssignments -> toHistoryEntry(entryAssignments, photosByEntryId, fallbackPerformersByEntryId))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(GroupHistoryEntry::date, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(GroupHistoryEntry::queueEntryId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        return new GroupHistoryResult(history, true);
    }

    private GroupHistoryEntry toHistoryEntry(List<TaskAssignment> assignments,
                                             Map<UUID, List<RentalItemEventPhoto>> photosByEntryId,
                                             Map<UUID, String> fallbackPerformersByEntryId) {
        if (assignments == null || assignments.isEmpty()) {
            return null;
        }

        assignments = assignments.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::assignmentMoment, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TaskAssignment::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        TaskAssignment latestAssignment = assignments.get(0);
        QueueEntry queueEntry = latestAssignment.getQueueEntry();
        if (queueEntry == null || queueEntry.getId() == null) {
            return null;
        }

        BoardTask boardTask = queueEntry.getTask();
        RepairProcess repairProcess = boardTask == null ? null : boardTask.getRepairProcess();
        Warehouse warehouse = boardTask == null ? null : boardTask.getWarehouse();

        Set<String> performers = new LinkedHashSet<>();
        for (TaskAssignment assignment : assignments) {
            Worker worker = assignment.getWorker();
            if (worker != null && worker.getDisplayName() != null && !worker.getDisplayName().isBlank()) {
                performers.add(worker.getDisplayName().trim());
            }
        }
        if (performers.isEmpty()) {
            String fallbackPerformers = fallbackPerformersByEntryId.get(queueEntry.getId());
            if (fallbackPerformers != null && !fallbackPerformers.isBlank()) {
                performers.add(fallbackPerformers);
            }
        }
        if (performers.isEmpty() && latestAssignment.getWorkerGroup() != null && latestAssignment.getWorkerGroup().getName() != null) {
            performers.add(latestAssignment.getWorkerGroup().getName());
        }

        List<RentalItemEventPhoto> photos = photosByEntryId.getOrDefault(queueEntry.getId(), List.of());
        List<PhotoInfo> photoInfos = photos.stream()
                .filter(photo -> photo.getId() != null)
                .map(photo -> new PhotoInfo(photo.getId(), photo.getOriginalFileName()))
                .toList();

        Integer plannedMinutes = queueEntry.getPlannedDurationMinutes() != null
                ? queueEntry.getPlannedDurationMinutes()
                : boardTask == null ? null : boardTask.getPlannedDurationMinutes();

        return new GroupHistoryEntry(
                queueEntry.getId(),
                resolveHistoryMoment(queueEntry, assignments),
                resolveUnitNumber(boardTask),
                warehouse == null ? null : warehouse.getName(),
                boardTask == null ? null : boardTask.getTitle(),
                normalizeBlank(queueEntry.getTaskText()) != null
                        ? normalizeBlank(queueEntry.getTaskText())
                        : boardTask == null ? null : normalizeBlank(boardTask.getDescription()),
                String.join(", ", performers),
                plannedMinutes,
                activeWorkMinutes(queueEntry.getActiveWorkSeconds()),
                photoInfos,
                queueEntry.getStatus(),
                resolveWorkType(queueEntry, boardTask, repairProcess),
                false);
    }

    private Map<UUID, List<RentalItemEventPhoto>> loadPhotosByQueueEntryId(Collection<UUID> queueEntryIds) {
        if (queueEntryIds == null || queueEntryIds.isEmpty()) {
            return Map.of();
        }

        List<RentalItemEventPhoto> photos = dataManager.load(RentalItemEventPhoto.class)
                .query("""
                        select e from RentalItemEventPhoto e
                        where e.event.queueEntry.id in :queueEntryIds
                        order by e.event.eventDate desc, e.sortOrder, e.id
                        """)
                .parameter("queueEntryIds", queueEntryIds)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("event", nested -> nested.addFetchPlan("_base").add("queueEntry", "_base")))
                .list();

        Map<UUID, List<RentalItemEventPhoto>> result = new LinkedHashMap<>();
        for (RentalItemEventPhoto photo : photos) {
            if (photo.getEvent() == null || photo.getEvent().getQueueEntry() == null || photo.getEvent().getQueueEntry().getId() == null) {
                continue;
            }
            result.computeIfAbsent(photo.getEvent().getQueueEntry().getId(), ignored -> new ArrayList<>()).add(photo);
        }
        return result;
    }

    private Map<UUID, String> loadFallbackPerformersByEntryId(Collection<UUID> queueEntryIds, UUID workerGroupId) {
        if (queueEntryIds == null || queueEntryIds.isEmpty() || workerGroupId == null) {
            return Map.of();
        }

        String memberNames = dataManager.load(WorkerGroupMember.class)
                .query("""
                        select e from WorkerGroupMember e
                        where e.workerGroup.id = :workerGroupId
                          and (e.active = true or e.active is null)
                        order by e.worker.displayName, e.id
                        """)
                .parameter("workerGroupId", workerGroupId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("worker", "_base"))
                .list()
                .stream()
                .map(WorkerGroupMember::getWorker)
                .filter(Objects::nonNull)
                .map(Worker::getDisplayName)
                .map(this::normalizeBlank)
                .filter(Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        if (memberNames.isBlank()) {
            return Map.of();
        }

        Map<UUID, String> result = new LinkedHashMap<>();
        for (UUID queueEntryId : queueEntryIds) {
            result.put(queueEntryId, memberNames);
        }
        return result;
    }

    private OffsetDateTime resolveHistoryMoment(QueueEntry queueEntry, List<TaskAssignment> assignments) {
        OffsetDateTime assignmentMoment = assignments.stream()
                .map(this::assignmentMoment)
                .filter(Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null);
        if (queueEntry == null) {
            return assignmentMoment;
        }
        return maxMoment(
                queueEntry.getDoneAt(),
                queueEntry.getPausedAt(),
                queueEntry.getActiveStartedAt(),
                assignmentMoment,
                queueEntry.getCreatedDate());
    }

    private OffsetDateTime assignmentMoment(TaskAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        return maxMoment(
                assignment.getFinishedAt(),
                assignment.getPausedAt(),
                assignment.getStartedAt(),
                assignment.getAssignedAt(),
                assignment.getCreatedDate());
    }

    private OffsetDateTime maxMoment(OffsetDateTime... values) {
        OffsetDateTime latest = null;
        if (values == null) {
            return null;
        }
        for (OffsetDateTime value : values) {
            if (value == null) {
                continue;
            }
            if (latest == null || value.isAfter(latest)) {
                latest = value;
            }
        }
        return latest;
    }

    private String resolveUnitNumber(BoardTask boardTask) {
        if (boardTask == null) {
            return null;
        }
        if (normalizeBlank(boardTask.getUnitNumber()) != null) {
            return normalizeBlank(boardTask.getUnitNumber());
        }
        if (boardTask.getRentalItem() != null) {
            return normalizeBlank(boardTask.getRentalItem().getNumber());
        }
        return null;
    }

    private Integer activeWorkMinutes(Long seconds) {
        if (seconds == null || seconds <= 0L) {
            return null;
        }
        return (int) Math.max(1L, Math.round(seconds / 60.0d));
    }

    private Optional<WorkType> resolveWorkType(QueueEntry queueEntry, BoardTask boardTask, RepairProcess repairProcess) {
        if (repairProcess != null && repairProcess.getProcessKind() == RepairProcessKind.REWORK) {
            return Optional.of(WorkType.REWORK);
        }
        if (boardTask != null && boardTask.getTaskKind() == RepairProcessTaskKind.REWORK) {
            return Optional.of(WorkType.REWORK);
        }
        return Optional.of(WorkType.REGULAR);
    }

    private Integer warehouseSortOrder(Warehouse warehouse) {
        return warehouse == null ? null : warehouse.getSortOrder();
    }

    private String warehouseName(Warehouse warehouse) {
        return warehouse == null ? null : warehouse.getName();
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record GroupHistoryResult(List<GroupHistoryEntry> entries, boolean reworkMarkerAvailable) {
    }

    public record GroupHistoryEntry(UUID queueEntryId,
                                    OffsetDateTime date,
                                    String unitNumber,
                                    String warehouseName,
                                    String taskTitle,
                                    String subtaskTitle,
                                    String performedBy,
                                    Integer plannedMinutes,
                                    Integer actualMinutes,
                                    List<PhotoInfo> photos,
                                    QueueEntryStatus status,
                                    Optional<WorkType> workType,
                                    boolean highlightedAsRework) {
    }

    public record PhotoInfo(UUID photoId, String fileName) {
    }

    public enum WorkType {
        REGULAR,
        REWORK
    }
}
