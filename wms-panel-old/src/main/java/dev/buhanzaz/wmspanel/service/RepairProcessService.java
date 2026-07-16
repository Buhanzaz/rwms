package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoLink;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoType;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RentalItemStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairProcessKind;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.TaskAssignment;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RepairProcessService {

    private final DataManager dataManager;
    private final WarehouseAccessService warehouseAccessService;
    private final RentalItemEventService rentalItemEventService;
    private final LocalMediaStorageService localMediaStorageService;
    private final PhotoProcessingQueueService photoProcessingQueueService;
    private final RepairMediaStorageProperties mediaStorageProperties;

    public RepairProcessService(DataManager dataManager,
                                WarehouseAccessService warehouseAccessService,
                                RentalItemEventService rentalItemEventService,
                                LocalMediaStorageService localMediaStorageService,
                                PhotoProcessingQueueService photoProcessingQueueService,
                                RepairMediaStorageProperties mediaStorageProperties) {
        this.dataManager = dataManager;
        this.warehouseAccessService = warehouseAccessService;
        this.rentalItemEventService = rentalItemEventService;
        this.localMediaStorageService = localMediaStorageService;
        this.photoProcessingQueueService = photoProcessingQueueService;
        this.mediaStorageProperties = mediaStorageProperties;
    }

    @Transactional
    public RepairProcess ensureProcess(RepairEstimate estimate, boolean movementRequired) {
        RepairProcess process = findByEstimateId(estimate.getId());
        if (process == null) {
            process = dataManager.create(RepairProcess.class);
            process.setEstimate(estimate);
            process.setRentalItem(dataManager.getReference(RentalItem.class, estimate.getRentalItem().getId()));
            process.setWarehouse(dataManager.getReference(Warehouse.class, estimate.getWarehouse().getId()));
            process.setStatus(RepairProcessStatus.ACTIVE);
            process.setProcessKind(RepairProcessKind.ESTIMATE_REPAIR);
        }
        process.setComment(estimate.getComment());
        process.setMoveToRepairRequired(movementRequired);
        process.setMoveFromRepairRequired(movementRequired);
        if (!movementRequired) {
            process.setMoveToRepairCancelled(false);
            process.setMoveFromRepairCancelled(false);
            process.setMoveToRepairDone(false);
            process.setMoveFromRepairDone(false);
        }
        return dataManager.save(process);
    }

    public RepairProcess findByEstimateId(UUID estimateId) {
        if (estimateId == null) {
            return null;
        }
        return dataManager.load(RepairProcess.class)
                .query("select e from RepairProcess e where e.estimate.id = :estimateId")
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base"))
                .optional()
                .orElse(null);
    }

    public List<RepairProcess> loadAfterRepairProcesses(List<Warehouse> warehouses) {
        List<UUID> warehouseIds = warehouses.stream().map(Warehouse::getId).filter(Objects::nonNull).toList();
        if (warehouseIds.isEmpty()) {
            return List.of();
        }
        promoteCompletedActiveProcessesToAfterRepair(warehouseIds);
        return dataManager.load(RepairProcess.class)
                .query("""
                        select e from RepairProcess e
                        where e.warehouse.id in :warehouseIds
                          and e.processKind <> dev.buhanzaz.wmspanel.entity.RepairProcessKind.REWORK
                          and (
                              e.status = dev.buhanzaz.wmspanel.entity.RepairProcessStatus.AFTER_REPAIR
                              or e.status = dev.buhanzaz.wmspanel.entity.RepairProcessStatus.REWORK
                          )
                        order by e.lastModifiedDate desc, e.createdDate desc
                        """)
                .parameter("warehouseIds", warehouseIds)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", builder1 -> builder1.addFetchPlan("_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base"))
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")
                                .add("type", "_base")
                                .add("condition", "_base"))
                        .add("warehouse", "_base"))
                .list();
    }

    public List<RepairProcess> loadProcessReworks(UUID sourceProcessId) {
        if (sourceProcessId == null) {
            return List.of();
        }
        return dataManager.load(RepairProcess.class)
                .query("""
                        select e from RepairProcess e
                        where e.sourceProcess.id = :sourceProcessId
                          and e.processKind = dev.buhanzaz.wmspanel.entity.RepairProcessKind.REWORK
                          and e.status <> dev.buhanzaz.wmspanel.entity.RepairProcessStatus.ACCEPTED
                          and e.status <> dev.buhanzaz.wmspanel.entity.RepairProcessStatus.CANCELLED
                        order by e.createdDate, e.id
                        """)
                .parameter("sourceProcessId", sourceProcessId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("sourceProcess", "_base")
                        .add("requestedWorkerGroup", builder1 -> builder1.addFetchPlan("_base")
                                .add("workerClass", "_base")
                                .add("warehouse", "_base"))
                        .add("requestedWorker", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")))
                .list();
    }

    private void promoteCompletedActiveProcessesToAfterRepair(List<UUID> warehouseIds) {
        List<RepairProcess> activeProcesses = dataManager.load(RepairProcess.class)
                .query("""
                        select e from RepairProcess e
                        where e.warehouse.id in :warehouseIds
                          and e.status = dev.buhanzaz.wmspanel.entity.RepairProcessStatus.ACTIVE
                        """)
                .parameter("warehouseIds", warehouseIds)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base"))
                .list();
        for (RepairProcess process : activeProcesses) {
            if (syncProcessStatus(process)) {
                rentalItemEventService.recordRepairReadyForCheck(process);
            }
        }
    }

    public List<BoardTask> loadProcessTasks(UUID processId) {
        if (processId == null) {
            return List.of();
        }
        return dataManager.load(BoardTask.class)
                .query("""
                        select e from BoardTask e
                        where e.repairProcess.id = :processId
                        order by e.createdDate, e.id
                        """)
                .parameter("processId", processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")
                                .add("type", "_base")
                                .add("condition", "_base"))
                        .add("warehouse", "_base")
                        .add("repairProcess", builder1 -> builder1.addFetchPlan("_base")
                                .add("estimate", builder2 -> builder2.addFetchPlan("_base")
                                        .add("rentalItem", "_base")
                                        .add("warehouse", "_base"))
                                .add("rentalItem", builder2 -> builder2.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("category", "_base")
                                        .add("subcategory", "_base")
                                        .add("type", "_base")
                                        .add("condition", "_base"))
                                .add("warehouse", "_base")))
                .list();
    }

    @Transactional
    public void markRentalItemStatus(RepairProcess process, RentalItemStatus status) {
        if (process == null || process.getId() == null || status == null) {
            return;
        }
        markRentalItemStatus(process.getId(), status);
    }

    @Transactional
    public void markRentalItemStatus(UUID processId, RentalItemStatus status) {
        if (processId == null || status == null) {
            return;
        }
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base"))
                .one();
        RentalItem rentalItem = process.getRentalItem();
        if (rentalItem == null) {
            return;
        }
        rentalItem.setStatus(status.getId());
        dataManager.save(rentalItem);
    }

    public void markRentalItemReady(RepairProcess process) {
        markRentalItemStatus(process, RentalItemStatus.READY);
    }

    public void markRentalItemWaitingForRepair(RepairProcess process) {
        markRentalItemStatus(process, RentalItemStatus.WAITING_REPAIR);
    }

    public void markRentalItemInRepair(RepairProcess process) {
        markRentalItemStatus(process, RentalItemStatus.IN_REPAIR);
    }

    public void markRentalItemWaitingForRepairCheck(RepairProcess process) {
        markRentalItemStatus(process, RentalItemStatus.WAITING_REPAIR_CHECK);
    }

    public List<QueueEntry> loadTaskEntries(UUID boardTaskId) {
        if (boardTaskId == null) {
            return List.of();
        }
        return dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.task.id = :taskId order by e.routeIndex, e.position")
                .parameter("taskId", boardTaskId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("queue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base"))
                        .add("task", "_base"))
                .list();
    }

    public List<RentalItemEventPhoto> loadLinkedPhotos(UUID boardTaskId) {
        if (boardTaskId == null) {
            return List.of();
        }
        return dataManager.load(BoardTaskPhotoLink.class)
                .query("""
                        select e from BoardTaskPhotoLink e
                        where e.boardTask.id = :taskId
                        order by e.sortOrder, e.id
                        """)
                .parameter("taskId", boardTaskId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("photo", "_base"))
                .list()
                .stream()
                .map(BoardTaskPhotoLink::getPhoto)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<BoardTaskPhotoLink> loadTaskPhotoLinks(UUID boardTaskId) {
        if (boardTaskId == null) {
            return List.of();
        }
        return dataManager.load(BoardTaskPhotoLink.class)
                .query("""
                        select e from BoardTaskPhotoLink e
                        where e.boardTask.id = :taskId
                        order by e.photoType, e.sortOrder, e.id
                        """)
                .parameter("taskId", boardTaskId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("boardTask", builder1 -> builder1.addFetchPlan("_base")
                                .add("repairProcess", "_base")
                                .add("rentalItem", builder2 -> builder2.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("category", "_base")
                                        .add("subcategory", "_base")
                                        .add("type", "_base")
                                        .add("condition", "_base"))
                                .add("warehouse", "_base"))
                        .add("photo", builder1 -> builder1.addFetchPlan("_base")
                                .add("event", builder2 -> builder2.addFetchPlan("_base")
                                        .add("rentalItem", "_base")
                                        .add("warehouse", "_base")
                                        .add("estimate", "_base"))))
                .list();
    }

    public List<BoardTaskPhotoLink> loadProcessPhotoLinks(UUID processId) {
        if (processId == null) {
            return List.of();
        }
        return dataManager.load(BoardTaskPhotoLink.class)
                .query("""
                        select e from BoardTaskPhotoLink e
                        where e.boardTask.repairProcess.id = :processId
                        order by e.boardTask.createdDate, e.photoType, e.sortOrder, e.id
                        """)
                .parameter("processId", processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("boardTask", builder1 -> builder1.addFetchPlan("_base")
                                .add("repairProcess", builder2 -> builder2.addFetchPlan("_base")
                                        .add("estimate", "_base")
                                        .add("rentalItem", builder3 -> builder3.addFetchPlan("_base")
                                                .add("warehouse", "_base")
                                                .add("category", "_base")
                                                .add("subcategory", "_base")
                                                .add("type", "_base")
                                                .add("condition", "_base"))
                                        .add("warehouse", "_base"))
                                .add("rentalItem", builder2 -> builder2.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("category", "_base")
                                        .add("subcategory", "_base")
                                        .add("type", "_base")
                                        .add("condition", "_base"))
                                .add("warehouse", "_base"))
                        .add("photo", builder1 -> builder1.addFetchPlan("_base")
                                .add("event", builder2 -> builder2.addFetchPlan("_base")
                                        .add("rentalItem", "_base")
                                        .add("warehouse", "_base")
                                        .add("estimate", "_base"))))
                .list();
    }

    public List<TaskAssignment> loadProcessAssignments(UUID processId) {
        if (processId == null) {
            return List.of();
        }
        return dataManager.load(TaskAssignment.class)
                .query("""
                        select e from TaskAssignment e
                        where e.queueEntry.task.repairProcess.id = :processId
                        order by e.queueEntry.routeIndex, e.assignedAt, e.startedAt, e.id
                        """)
                .parameter("processId", processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("queueEntry", builder1 -> builder1.addFetchPlan("_base")
                                .add("queue", builder2 -> builder2.addFetchPlan("_base").add("warehouse", "_base"))
                                .add("task", "_base"))
                        .add("workerGroup", builder1 -> builder1.addFetchPlan("_base")
                                .add("workerClass", "_base")
                                .add("warehouse", "_base"))
                        .add("worker", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")))
                .list();
    }

    @Transactional
    public int attachTaskPhotos(UUID boardTaskId,
                                UUID queueEntryId,
                                BoardTaskPhotoType photoType,
                                String comment,
                                List<RepairTaskUploadedPhoto> uploads) {
        if (boardTaskId == null || uploads == null || uploads.isEmpty()) {
            return 0;
        }
        BoardTask boardTask = dataManager.load(BoardTask.class)
                .id(boardTaskId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("repairProcess", builder1 -> builder1.addFetchPlan("_base")
                                .add("estimate", "_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base"))
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base"))
                        .add("warehouse", "_base"))
                .one();
        if (boardTask.getRepairProcess() == null || boardTask.getRentalItem() == null || boardTask.getWarehouse() == null) {
            throw new IllegalArgumentException("У подзадачи нет полного ремонтного контекста для сохранения фото");
        }

        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = dataManager.load(QueueEntry.class)
                    .id(queueEntryId)
                    .fetchPlan(builder -> builder.addFetchPlan("_base")
                            .add("task", "_base")
                            .add("queue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base")))
                    .optional()
                    .orElse(null);
            if (queueEntry != null && (queueEntry.getTask() == null || !Objects.equals(queueEntry.getTask().getId(), boardTask.getId()))) {
                throw new IllegalArgumentException("Очередь не принадлежит выбранной подзадаче");
            }
        }

        TaskAssignment assignment = queueEntry == null ? null : resolveLatestAssignment(queueEntry.getId());
        RentalItem rentalItem = boardTask.getRentalItem();
        RentalItemStatus currentStatus = RentalItemStatus.fromId(rentalItem.getStatus());

        RentalItemEvent event = dataManager.create(RentalItemEvent.class);
        event.setRentalItem(dataManager.getReference(RentalItem.class, rentalItem.getId()));
        event.setWarehouse(dataManager.getReference(Warehouse.class, boardTask.getWarehouse().getId()));
        event.setEventType(RentalItemEventType.MANUAL);
        event.setTitle("Фото выполнения подзадачи: " + boardTask.getTitle());
        event.setComment(trimToNull(comment));
        event.setEventDate(OffsetDateTime.now());
        event.setStatusBefore(currentStatus == null ? null : currentStatus.getId());
        event.setStatusAfter(currentStatus == null ? null : currentStatus.getId());
        event.setActorUsername(warehouseAccessService.username());
        event.setActorDisplayName(warehouseAccessService.username());
        event.setSource("QUEUE_WORK_BOARD");
        event.setSourceComment(photoType == null ? BoardTaskPhotoType.WORK.name() : photoType.name());
        event.setRepairProcess(dataManager.getReference(RepairProcess.class, boardTask.getRepairProcess().getId()));
        event.setBoardTask(dataManager.getReference(BoardTask.class, boardTask.getId()));
        if (boardTask.getRepairProcess().getEstimate() != null && boardTask.getRepairProcess().getEstimate().getId() != null) {
            event.setEstimate(dataManager.getReference(RepairEstimate.class, boardTask.getRepairProcess().getEstimate().getId()));
        }
        if (queueEntry != null && queueEntry.getId() != null) {
            event.setQueueEntry(dataManager.getReference(QueueEntry.class, queueEntry.getId()));
        }
        if (assignment != null && assignment.getWorkerGroup() != null && assignment.getWorkerGroup().getId() != null) {
            event.setWorkerGroup(dataManager.getReference(dev.buhanzaz.wmspanel.entity.WorkerGroup.class, assignment.getWorkerGroup().getId()));
        }
        if (assignment != null && assignment.getWorker() != null && assignment.getWorker().getId() != null) {
            event.setWorker(dataManager.getReference(dev.buhanzaz.wmspanel.entity.Worker.class, assignment.getWorker().getId()));
        }
        event = dataManager.save(event);

        int photoSortOrder = 0;
        int linkSortOrder = dataManager.loadValue("""
                        select coalesce(max(e.sortOrder), -1)
                        from BoardTaskPhotoLink e
                        where e.boardTask.id = :taskId
                        """, Integer.class)
                .parameter("taskId", boardTask.getId())
                .one() + 1;
        BoardTaskPhotoType effectivePhotoType = photoType == null ? BoardTaskPhotoType.WORK : photoType;
        SaveContext saveContext = new SaveContext();
        int savedCount = 0;
        for (RepairTaskUploadedPhoto upload : uploads) {
            if (upload == null || upload.inputStream() == null) {
                continue;
            }
            RentalItemEventPhoto entity = dataManager.create(RentalItemEventPhoto.class);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            LocalMediaStorageService.StoredMedia storedMedia = localMediaStorageService.storeIncoming(
                    upload.inputStream(),
                    upload.fileName(),
                    upload.contentType(),
                    storagePathContext(event),
                    entity.getId()
            );
            entity.setEvent(event);
            entity.setStoragePath(storedMedia.relativePath());
            entity.setFamilyRootKey(storedMedia.familyRootKey());
            entity.setOriginalFileName(upload.fileName());
            entity.setContentType(upload.contentType());
            entity.setSizeBytes(storedMedia.sizeBytes());
            entity.setSortOrder(photoSortOrder++);
            entity.setProcessingStatus(photoProcessingQueueService.queueEnabled() ? dev.buhanzaz.wmspanel.entity.PhotoProcessingStatus.PENDING : dev.buhanzaz.wmspanel.entity.PhotoProcessingStatus.READY);

            BoardTaskPhotoLink link = dataManager.create(BoardTaskPhotoLink.class);
            link.setBoardTask(dataManager.getReference(BoardTask.class, boardTask.getId()));
            link.setPhoto(entity);
            link.setPhotoType(effectivePhotoType);
            link.setSortOrder(linkSortOrder++);

            saveContext.saving(entity);
            saveContext.saving(link);
            savedCount++;
        }
        if (savedCount == 0) {
            dataManager.remove(event);
            return 0;
        }
        dataManager.save(saveContext);
        RentalItemEvent savedEvent = event;
        loadPhotos(savedEvent.getId()).forEach(photo -> enqueuePhotoProcessing(savedEvent, photo));
        return savedCount;
    }

    @Transactional
    public void linkExistingPhotos(BoardTask task, List<RentalItemEventPhoto> photos, BoardTaskPhotoType photoType) {
        if (task == null || task.getId() == null || photos == null || photos.isEmpty()) {
            return;
        }
        int sortOrder = dataManager.loadValue("""
                        select coalesce(max(e.sortOrder), -1)
                        from BoardTaskPhotoLink e
                        where e.boardTask = :task
                        """, Integer.class)
                .parameter("task", task)
                .one() + 1;
        SaveContext saveContext = new SaveContext();
        for (RentalItemEventPhoto photo : photos) {
            if (photo == null || photo.getId() == null) {
                continue;
            }
            Long count = dataManager.loadValue("""
                            select count(e) from BoardTaskPhotoLink e
                            where e.boardTask = :task and e.photo = :photo
                            """, Long.class)
                    .parameter("task", task)
                    .parameter("photo", photo)
                    .one();
            if (count != null && count > 0) {
                continue;
            }
            BoardTaskPhotoLink link = dataManager.create(BoardTaskPhotoLink.class);
            link.setBoardTask(task);
            link.setPhoto(photo);
            link.setPhotoType(photoType == null ? BoardTaskPhotoType.WORK : photoType);
            link.setSortOrder(sortOrder++);
            saveContext.saving(link);
        }
        dataManager.save(saveContext);
    }

    public boolean processReadyForAfterRepair(RepairProcess process) {
        if (process == null || process.getId() == null) {
            return false;
        }
        List<BoardTask> tasks = loadProcessTasks(process.getId());
        boolean allTasksClosed = tasks.stream()
                .filter(task -> task.getTaskKind() != RepairProcessTaskKind.MOVE_FROM_REPAIR || Boolean.TRUE.equals(process.getMoveFromRepairRequired()))
                .allMatch(task -> task.getStatus() == BoardTaskStatus.DONE || task.getStatus() == BoardTaskStatus.CANCELLED);
        if (!allTasksClosed) {
            return false;
        }
        boolean hasMoveFromRepairTask = tasks.stream()
                .anyMatch(task -> task.getTaskKind() == RepairProcessTaskKind.MOVE_FROM_REPAIR);
        boolean moveFromRepairTaskClosed = tasks.stream()
                .filter(task -> task.getTaskKind() == RepairProcessTaskKind.MOVE_FROM_REPAIR)
                .allMatch(task -> task.getStatus() == BoardTaskStatus.DONE || task.getStatus() == BoardTaskStatus.CANCELLED);
        if (Boolean.TRUE.equals(process.getMoveFromRepairRequired())
                && !hasMoveFromRepairTask
                && !Boolean.TRUE.equals(process.getMoveFromRepairDone())
                && !Boolean.TRUE.equals(process.getMoveFromRepairCancelled())) {
            return false;
        }
        if (Boolean.TRUE.equals(process.getMoveFromRepairRequired())
                && hasMoveFromRepairTask
                && !moveFromRepairTaskClosed) {
            return false;
        }
        return true;
    }

    @Transactional
    public boolean syncProcessStatus(RepairProcess process) {
        if (process == null || process.getId() == null) {
            return false;
        }
        RepairProcess reloaded = dataManager.load(RepairProcess.class).id(process.getId()).one();
        if (reloaded.getStatus() == RepairProcessStatus.ACCEPTED || reloaded.getStatus() == RepairProcessStatus.CANCELLED) {
            return false;
        }
        List<BoardTask> tasks = loadProcessTasks(reloaded.getId());
        if (tasks.isEmpty()) {
            reloaded.setStatus(RepairProcessStatus.ACTIVE);
            dataManager.save(reloaded);
            return false;
        }
        if (processReadyForAfterRepair(reloaded)) {
            reloaded.setStatus(RepairProcessStatus.AFTER_REPAIR);
            dataManager.save(reloaded);
            promoteSourceAfterCompletedRework(reloaded);
            return true;
        } else {
            if (reloaded.getStatus() != RepairProcessStatus.REWORK) {
                reloaded.setStatus(RepairProcessStatus.ACTIVE);
            }
        }
        dataManager.save(reloaded);
        return false;
    }

    private void promoteSourceAfterCompletedRework(RepairProcess reworkProcess) {
        if (reworkProcess == null
                || reworkProcess.getProcessKind() != RepairProcessKind.REWORK
                || reworkProcess.getSourceProcess() == null
                || reworkProcess.getSourceProcess().getId() == null) {
            return;
        }
        RepairProcess source = dataManager.load(RepairProcess.class)
                .id(reworkProcess.getSourceProcess().getId())
                .optional()
                .orElse(null);
        if (source != null && source.getStatus() == RepairProcessStatus.REWORK) {
            source.setStatus(RepairProcessStatus.AFTER_REPAIR);
            dataManager.save(source);
        }
    }

    @Transactional
    public void markMovement(BoardTask boardTask, boolean cancelled) {
        if (boardTask == null || boardTask.getRepairProcess() == null) {
            return;
        }
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(boardTask.getRepairProcess().getId())
                .one();
        if (boardTask.getTaskKind() == RepairProcessTaskKind.MOVE_TO_REPAIR) {
            process.setMoveToRepairDone(!cancelled);
            process.setMoveToRepairCancelled(cancelled);
        } else if (boardTask.getTaskKind() == RepairProcessTaskKind.MOVE_FROM_REPAIR) {
            process.setMoveFromRepairDone(!cancelled);
            process.setMoveFromRepairCancelled(cancelled);
        }
        dataManager.save(process);
        syncProcessStatus(process);
    }

    @Transactional
    public void acceptProcess(UUID processId, String comment) {
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("sourceProcess", "_base"))
                .one();
        if (process.getStatus() == RepairProcessStatus.REWORK && hasOpenReworks(process.getId())) {
            throw new IllegalArgumentException("Нельзя принять ремонт, пока есть активная доработка");
        }
        if (!processReadyForAfterRepair(process)) {
            throw new IllegalArgumentException("Процесс еще не готов к приемке");
        }
        process.setStatus(RepairProcessStatus.ACCEPTED);
        process.setAcceptedAt(OffsetDateTime.now());
        process.setAcceptedBy(warehouseAccessService.username());
        process.setAcceptanceComment(comment);
        dataManager.save(process);
        rentalItemEventService.recordRepairAccepted(process, comment);
        if (process.getProcessKind() == RepairProcessKind.REWORK
                && process.getSourceProcess() != null
                && process.getSourceProcess().getId() != null) {
            RepairProcess sourceProcess = dataManager.load(RepairProcess.class)
                    .id(process.getSourceProcess().getId())
                    .fetchPlan(builder -> builder.addFetchPlan("_base")
                            .add("rentalItem", "_base")
                            .add("warehouse", "_base")
                            .add("estimate", "_base"))
                    .one();
            sourceProcess.setStatus(RepairProcessStatus.ACCEPTED);
            sourceProcess.setAcceptedAt(process.getAcceptedAt());
            sourceProcess.setAcceptedBy(process.getAcceptedBy());
            sourceProcess.setAcceptanceComment(comment);
            dataManager.save(sourceProcess);
            rentalItemEventService.recordRepairAccepted(sourceProcess, comment);
        }
    }

    private boolean hasOpenReworks(UUID sourceProcessId) {
        if (sourceProcessId == null) {
            return false;
        }
        Long count = dataManager.loadValue("""
                        select count(e) from RepairProcess e
                        where e.sourceProcess.id = :sourceProcessId
                          and e.processKind = dev.buhanzaz.wmspanel.entity.RepairProcessKind.REWORK
                          and e.status = dev.buhanzaz.wmspanel.entity.RepairProcessStatus.ACTIVE
                        """, Long.class)
                .parameter("sourceProcessId", sourceProcessId)
                .one();
        return count != null && count > 0;
    }

    public String buildTaskSummary(BoardTask boardTask, List<RepairEstimateTaskPlan> taskPlans) {
        List<String> parts = new ArrayList<>();
        if (boardTask.getRentalItem() != null) {
            parts.add(boardTask.getRentalItem().getNumber());
        }
        if (boardTask.getTaskKind() != null) {
            parts.add(boardTask.getTaskKind().name());
        }
        if (boardTask.getStatus() != null) {
            parts.add(boardTask.getStatus().name());
        }
        if (boardTask.getDescription() != null && !boardTask.getDescription().isBlank()) {
            parts.add(boardTask.getDescription());
        }
        if (taskPlans != null && !taskPlans.isEmpty()) {
            parts.add("Подзаданий: " + taskPlans.size());
        }
        return String.join(" | ", parts);
    }

    public String buildProcessSummary(RepairProcess process) {
        if (process == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (process.getRentalItem() != null) {
            parts.add(process.getRentalItem().getNumber());
            if (process.getRentalItem().getCategory() != null) {
                parts.add(process.getRentalItem().getCategory().getName());
            }
            if (process.getRentalItem().getSubcategory() != null) {
                parts.add(process.getRentalItem().getSubcategory().getName());
            }
            if (process.getRentalItem().getType() != null) {
                parts.add(process.getRentalItem().getType().getName());
            }
        }
        if (process.getWarehouse() != null) {
            parts.add(process.getWarehouse().getName());
        }
        if (process.getProcessKind() != null) {
            parts.add(process.getProcessKind().name());
        }
        if (process.getEstimate() != null) {
            parts.add(process.getEstimate().getCabinNumber());
        }
        if (process.getStatus() != null) {
            parts.add(process.getStatus().name());
        }
        return String.join(" | ", parts);
    }

    public List<RepairEstimateTaskPlan> loadProcessPlans(UUID processId) {
        if (processId == null) {
            return List.of();
        }
        return dataManager.load(RepairEstimateTaskPlan.class)
                .query("""
                        select e from RepairEstimateTaskPlan e
                        where e.repairProcess.id = :processId
                        order by e.sortOrder, e.id
                        """)
                .parameter("processId", processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("estimateLine", "_base")
                        .add("repairProcess", "_base")
                        .add("queue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base"))
                        .add("generatedBoardTask", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("rentalItem", builder2 -> builder2.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("category", "_base")
                                        .add("subcategory", "_base")
                                        .add("type", "_base")
                                        .add("condition", "_base"))
                                .add("repairProcess", "_base"))
                        .add("taskLines", builder1 -> builder1.addFetchPlan("_base").add("estimateLine", "_base")))
                .list();
    }

    public List<RentalItemEventPhoto> loadProcessEventPhotos(UUID processId) {
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")
                                .add("type", "_base")
                                .add("condition", "_base"))
                        .add("warehouse", "_base"))
                .optional()
                .orElse(null);
        if (process == null || process.getRentalItem() == null) {
            return List.of();
        }
        return dataManager.load(RentalItemEventPhoto.class)
                .query("""
                        select p from RentalItemEventPhoto p
                        where p.event.rentalItem = :rentalItem
                        order by p.event.eventDate, p.sortOrder, p.id
                        """)
                .parameter("rentalItem", process.getRentalItem())
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("event", builder1 -> builder1.addFetchPlan("_base")
                                .add("estimate", "_base")
                                .add("rentalItem", builder2 -> builder2.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("category", "_base")
                                        .add("subcategory", "_base")
                                        .add("type", "_base")
                                        .add("condition", "_base"))
                                .add("warehouse", "_base")))
                .list();
    }

    public RepairProcessDossierData loadProcessDossier(UUID processId) {
        if (processId == null) {
            return null;
        }
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", builder1 -> builder1.addFetchPlan("_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base"))
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")
                                .add("type", "_base")
                                .add("condition", "_base"))
                        .add("warehouse", "_base"))
                .optional()
                .orElse(null);
        if (process == null) {
            return null;
        }
        return new RepairProcessDossierData(process,
                loadProcessTasks(processId),
                loadProcessPlans(processId),
                loadProcessPhotoLinks(processId),
                loadProcessEventPhotos(processId),
                loadProcessAssignments(processId),
                loadProcessReworks(processId));
    }

    public List<BoardTask> orderedActiveProcessTasks(UUID processId) {
        return loadProcessTasks(processId).stream()
                .sorted(Comparator.comparing(BoardTask::getCreatedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BoardTask::getId))
                .toList();
    }

    public boolean taskHasRequiredPhotos(BoardTask boardTask) {
        if (boardTask == null || boardTask.getId() == null) {
            return false;
        }
        Long count = dataManager.loadValue("""
                        select count(e) from BoardTaskPhotoLink e
                        where e.boardTask = :boardTask
                        """, Long.class)
                .parameter("boardTask", boardTask)
                .one();
        return count != null && count > 0;
    }

    public boolean taskClosed(BoardTask boardTask) {
        return boardTask != null
                && (boardTask.getStatus() == BoardTaskStatus.DONE || boardTask.getStatus() == BoardTaskStatus.CANCELLED);
    }

    public boolean queueEntryClosed(QueueEntry entry) {
        return entry != null
                && (entry.getStatus() == QueueEntryStatus.DONE || entry.getStatus() == QueueEntryStatus.CANCELLED);
    }

    private List<RentalItemEventPhoto> loadPhotos(UUID eventId) {
        if (eventId == null) {
            return List.of();
        }
        return dataManager.load(RentalItemEventPhoto.class)
                .query("select e from RentalItemEventPhoto e where e.event.id = :eventId order by e.sortOrder, e.id")
                .parameter("eventId", eventId)
                .list();
    }

    private TaskAssignment resolveLatestAssignment(UUID queueEntryId) {
        if (queueEntryId == null) {
            return null;
        }
        return dataManager.load(TaskAssignment.class)
                .query("""
                        select e from TaskAssignment e
                        where e.queueEntry.id = :queueEntryId
                        order by coalesce(e.finishedAt, e.startedAt, e.assignedAt, e.createdDate) desc, e.id desc
                        """)
                .parameter("queueEntryId", queueEntryId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional()
                .orElse(null);
    }

    private LocalMediaStorageService.StoragePathContext storagePathContext(RentalItemEvent event) {
        return new LocalMediaStorageService.StoragePathContext(
                event == null || event.getWarehouse() == null ? null : event.getWarehouse().getCode(),
                event == null || event.getWarehouse() == null ? null : event.getWarehouse().getCity(),
                event == null || event.getRentalItem() == null ? null : event.getRentalItem().getNumber(),
                event == null || event.getEventType() == null ? null : event.getEventType().getId(),
                event == null ? null : event.getEventDate(),
                event == null ? null : event.getId(),
                null
        );
    }

    private void enqueuePhotoProcessing(RentalItemEvent event, RentalItemEventPhoto photo) {
        if (!photoProcessingQueueService.queueEnabled() || event == null || photo == null) {
            return;
        }
        try {
            photoProcessingQueueService.publish(new PhotoProcessingTaskMessage(
                    photo.getId(),
                    mediaStorageProperties.getMinioBucket(),
                    photo.getStoragePath(),
                    photo.getFamilyRootKey(),
                    photo.getContentType(),
                    event.getRentalItem() == null ? null : event.getRentalItem().getId(),
                    event.getId(),
                    event.getWarehouse() == null ? null : event.getWarehouse().getCode(),
                    event.getRentalItem() == null ? null : event.getRentalItem().getNumber(),
                    event.getEventType() == null ? null : event.getEventType().getId(),
                    mediaStorageProperties.getPreviewLongEdge(),
                    mediaStorageProperties.getThumbLongEdge(),
                    mediaStorageProperties.getTinyLongEdge()
            ));
        } catch (RuntimeException ex) {
            photoProcessingQueueService.markFailed(photo.getId(), ex.getMessage());
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record RepairProcessDossierData(RepairProcess process,
                                           List<BoardTask> tasks,
                                           List<RepairEstimateTaskPlan> plans,
                                           List<BoardTaskPhotoLink> photoLinks,
                                           List<RentalItemEventPhoto> eventPhotos,
                                           List<TaskAssignment> assignments,
                                           List<RepairProcess> reworks) {
    }

    public record RepairTaskUploadedPhoto(String fileName, String contentType, InputStream inputStream) {
    }
}
