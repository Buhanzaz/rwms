package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RentalItemStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RentalItemEventService {

    private static final String GROUP_PREFIX_ESTIMATE = "estimate:";
    private static final String GROUP_PREFIX_PROCESS = "process:";
    private static final String GROUP_PREFIX_INVENTORY = "inventory:";

    private final DataManager dataManager;
    private final WarehouseAccessService warehouseAccessService;
    private final RepairCatalogService repairCatalogService;

    public RentalItemEventService(DataManager dataManager,
                                  WarehouseAccessService warehouseAccessService,
                                  RepairCatalogService repairCatalogService) {
        this.dataManager = dataManager;
        this.warehouseAccessService = warehouseAccessService;
        this.repairCatalogService = repairCatalogService;
    }

    public List<RentalItemEvent> loadTimeline(UUID rentalItemId) {
        if (rentalItemId == null) {
            return List.of();
        }
        List<RentalItemEvent> events = dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.rentalItem.id = :rentalItemId
                        order by e.eventDate desc, e.createdDate desc, e.id desc
                        """)
                .parameter("rentalItemId", rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", builder1 -> builder1.addFetchPlan("_base")
                                .add("queue", "_base")
                                .add("task", "_base"))
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .list();
        return orderTimeline(events);
    }

    public List<RentalItemEventPhoto> loadPhotos(UUID eventId) {
        if (eventId == null) {
            return List.of();
        }
        return dataManager.load(RentalItemEventPhoto.class)
                .query("select e from RentalItemEventPhoto e where e.event.id = :eventId order by e.sortOrder, e.id")
                .parameter("eventId", eventId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("event", builder1 -> builder1.addFetchPlan("_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base")
                                .add("estimate", "_base")))
                .list();
    }

    public List<RentalItemEventAccessory> loadEventAccessories(UUID eventId) {
        if (eventId == null) {
            return List.of();
        }
        return dataManager.load(RentalItemEventAccessory.class)
                .query("""
                        select e from RentalItemEventAccessory e
                        where e.event.id = :eventId
                        order by coalesce(e.accessoryItem.sortOrder, 999999), e.accessoryItem.name
                        """)
                .parameter("eventId", eventId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("event", "_base")
                        .add("accessoryItem", nested -> nested.addFetchPlan("_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")))
                .list();
    }

    public List<RentalItemEventPhoto> loadLatestPhotos(UUID rentalItemId) {
        if (rentalItemId == null) {
            return List.of();
        }
        List<RentalItemEventPhoto> photos = dataManager.load(RentalItemEventPhoto.class)
                .query("""
                        select p from RentalItemEventPhoto p
                        join p.event e
                        where e.rentalItem.id = :rentalItemId
                        order by e.eventDate desc, e.createdDate desc, p.sortOrder, p.id
                        """)
                .parameter("rentalItemId", rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("event", builder1 -> builder1.addFetchPlan("_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base")
                                .add("estimate", "_base")))
                .list();
        if (photos.isEmpty()) {
            return List.of();
        }
        UUID latestEventId = photos.get(0).getEvent() == null ? null : photos.get(0).getEvent().getId();
        if (latestEventId == null) {
            return List.of();
        }
        return photos.stream()
                .filter(photo -> photo.getEvent() != null && latestEventId.equals(photo.getEvent().getId()))
                .toList();
    }

    public RentalItemEvent latestEvent(UUID rentalItemId) {
        if (rentalItemId == null) {
            return null;
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.rentalItem.id = :rentalItemId
                        order by e.eventDate desc, e.createdDate desc, e.id desc
                        """)
                .parameter("rentalItemId", rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", builder1 -> builder1.addFetchPlan("_base")
                                .add("queue", "_base")
                                .add("task", "_base"))
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional()
                .orElse(null);
    }

    @Transactional
    public RentalItemEvent recordInventoryNewItem(RentalItem rentalItem,
                                                  Warehouse warehouse,
                                                  String mobileTaskKey,
                                                  String mobileCreationMode,
                                                  OffsetDateTime eventDate,
                                                  String comment,
                                                  String sourceComment) {
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = RentalItemStatus.READY;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent event = findByMobileTaskKey(mobileTaskKey).orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, warehouse, eventDate, comment, before, after, RentalItemEventType.INVENTORY_NEW_ITEM, "MOBILE_INVENTORY", sourceComment);
        event.setMobileTaskKey(trimToNull(mobileTaskKey));
        event.setMobileCreationMode(trimToNull(mobileCreationMode));
        applyRootHierarchy(event, inventoryGroupKey(rentalItem));
        clearEstimateAndProcessLinks(event);
        event.setTitle("Создан объект " + safeNumber(rentalItem));
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordInventoryExistingItem(RentalItem rentalItem,
                                                       Warehouse warehouse,
                                                       String mobileTaskKey,
                                                       String mobileCreationMode,
                                                       OffsetDateTime eventDate,
                                                       String comment,
                                                       String sourceComment) {
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent event = findByMobileTaskKey(mobileTaskKey).orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, warehouse, eventDate, comment, before, after, RentalItemEventType.INVENTORY_EXISTING_ITEM, "MOBILE_INVENTORY", sourceComment);
        event.setMobileTaskKey(trimToNull(mobileTaskKey));
        event.setMobileCreationMode(trimToNull(mobileCreationMode));
        applyRootHierarchy(event, inventoryGroupKey(rentalItem));
        clearEstimateAndProcessLinks(event);
        event.setTitle("Инвентаризация существующего объекта " + safeNumber(rentalItem));
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordEstimateDraft(RepairEstimate estimate,
                                               String comment,
                                               OffsetDateTime eventDate) {
        return recordEstimateDraft(estimate, comment, eventDate, null);
    }

    @Transactional
    public RentalItemEvent recordEstimateDraft(RepairEstimate estimate,
                                               String comment,
                                               OffsetDateTime eventDate,
                                               String sourceComment) {
        RentalItem rentalItem = estimate == null ? null : estimate.getRentalItem();
        if (estimate == null || rentalItem == null) {
            return null;
        }
        Warehouse warehouse = estimate.getWarehouse() == null
                ? rentalItem.getWarehouse()
                : estimate.getWarehouse();
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent event = findEstimateRootEvent(estimate.getId()).orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, warehouse, eventDate, comment, before, after, RentalItemEventType.ESTIMATE_DRAFT, "ESTIMATE", sourceComment);
        event.setEstimate(dataManager.getReference(RepairEstimate.class, estimate.getId()));
        clearInventoryAndRepairLinks(event);
        RepairProcess process = findProcessByEstimateId(estimate.getId());
        if (process != null && process.getId() != null) {
            event.setRepairProcess(dataManager.getReference(RepairProcess.class, process.getId()));
            applyRootHierarchy(event, processGroupKey(process));
        } else {
            applyRootHierarchy(event, estimateGroupKey(estimate));
        }
        event.setMobileTaskKey(null);
        event.setMobileCreationMode(null);
        event.setTitle("Черновик сметы для " + safeNumber(rentalItem));
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordEstimateCompleted(RepairEstimate estimate,
                                                   boolean movementRequired) {
        return recordEstimateCompleted(estimate, movementRequired, null, null);
    }

    @Transactional
    public RentalItemEvent recordEstimateCompleted(RepairEstimate estimate,
                                                   boolean movementRequired,
                                                   String comment,
                                                   OffsetDateTime eventDate) {
        RentalItem rentalItem = estimate == null ? null : estimate.getRentalItem();
        if (estimate == null || rentalItem == null) {
            return null;
        }
        Warehouse warehouse = estimate.getWarehouse() == null
                ? rentalItem.getWarehouse()
                : estimate.getWarehouse();
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = estimateRequiresRepair(estimate, movementRequired)
                ? RentalItemStatus.WAITING_REPAIR
                : RentalItemStatus.READY;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent event = findEstimateRootEvent(estimate.getId()).orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, warehouse, eventDate, comment, before, after, RentalItemEventType.ESTIMATE_COMPLETED, "ESTIMATE", null);
        event.setEstimate(dataManager.getReference(RepairEstimate.class, estimate.getId()));
        clearInventoryAndRepairLinks(event);
        RepairProcess process = findProcessByEstimateId(estimate.getId());
        if (process != null && process.getId() != null) {
            event.setRepairProcess(dataManager.getReference(RepairProcess.class, process.getId()));
            applyRootHierarchy(event, processGroupKey(process));
        } else {
            applyRootHierarchy(event, estimateGroupKey(estimate));
        }
        event.setMobileTaskKey(null);
        event.setMobileCreationMode(null);
        event.setTitle("Смета завершена для " + safeNumber(rentalItem));
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordStatusChange(RentalItem rentalItem,
                                              RentalItemStatus after,
                                              String title,
                                              String comment,
                                              String source,
                                              String sourceComment,
                                              OffsetDateTime eventDate) {
        if (rentalItem == null || after == null) {
            return null;
        }
        RentalItemStatus before = statusOf(rentalItem);
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent event = dataManager.create(RentalItemEvent.class);
        populateBase(event, rentalItem, rentalItem.getWarehouse(), eventDate, comment, before, after, RentalItemEventType.STATUS_CHANGED, source, sourceComment);
        applyRootHierarchy(event, inventoryGroupKey(rentalItem));
        clearEstimateAndProcessLinks(event);
        event.setTitle(trimToNull(title) == null ? "Статус объекта изменён " + safeNumber(rentalItem) : title);
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordAccessorySnapshot(RentalItem rentalItem,
                                                   Warehouse warehouse,
                                                   RentalItemEvent parentEvent,
                                                   OffsetDateTime eventDate,
                                                   String comment,
                                                   String source,
                                                   String sourceComment,
                                                   List<RentalItemAccessory> accessories) {
        if (rentalItem == null || rentalItem.getId() == null) {
            return null;
        }
        RentalItemStatus currentStatus = statusOf(rentalItem);
        RentalItemEvent event = dataManager.create(RentalItemEvent.class);
        populateBase(event,
                rentalItem,
                warehouse == null ? rentalItem.getWarehouse() : warehouse,
                eventDate,
                buildAccessorySummary(accessories, comment),
                currentStatus,
                currentStatus,
                RentalItemEventType.ACCESSORY_UPDATED,
                source,
                sourceComment);
        applyChildHierarchy(event, parentEvent,
                parentEvent == null ? inventoryGroupKey(rentalItem) : parentEvent.getEventGroupKey(),
                false);
        clearEstimateAndProcessLinks(event);
        if (parentEvent != null && parentEvent.getEstimate() != null && parentEvent.getEstimate().getId() != null) {
            event.setEstimate(dataManager.getReference(RepairEstimate.class, parentEvent.getEstimate().getId()));
        }
        if (parentEvent != null && parentEvent.getRepairProcess() != null && parentEvent.getRepairProcess().getId() != null) {
            event.setRepairProcess(dataManager.getReference(RepairProcess.class, parentEvent.getRepairProcess().getId()));
        }
        event.setTitle("Обновлено доп. оборудование " + safeNumber(rentalItem));
        RentalItemEvent savedEvent = dataManager.save(event);
        syncEventAccessories(savedEvent, accessories);
        return savedEvent;
    }

    @Transactional
    public RentalItemEvent recordRepairTaskStarted(RepairProcess process,
                                                   BoardTask boardTask,
                                                   QueueEntry queueEntry,
                                                   WorkerGroup workerGroup,
                                                   Worker worker) {
        RentalItem rentalItem = process == null ? null : process.getRentalItem();
        if (rentalItem == null) {
            return null;
        }
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = RentalItemStatus.IN_REPAIR;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent rootEvent = resolveProcessRootEvent(process);
        RentalItemEvent event = findLatestRepairTaskStarted(boardTask == null ? null : boardTask.getId())
                .orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, process == null ? rentalItem.getWarehouse() : process.getWarehouse(), OffsetDateTime.now(), null, before, after, RentalItemEventType.REPAIR_TASK_STARTED, "REPAIR_PROCESS", null);
        applyProcessLinks(event, process, boardTask, queueEntry, workerGroup, worker);
        applyChildHierarchy(event, rootEvent, rootEvent == null ? processGroupKey(process) : rootEvent.getEventGroupKey(), true);
        event.setMobileTaskKey(null);
        event.setMobileCreationMode(null);
        event.setTitle(boardTask == null || boardTask.getTitle() == null ? "Задача ремонта взята в работу" : "Задача ремонта взята в работу: " + boardTask.getTitle());
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordRepairTaskCompleted(RepairProcess process,
                                                     BoardTask boardTask,
                                                     QueueEntry queueEntry,
                                                     WorkerGroup workerGroup,
                                                     Worker worker) {
        RentalItem rentalItem = process == null ? null : process.getRentalItem();
        if (rentalItem == null) {
            return null;
        }
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = before == null ? RentalItemStatus.IN_REPAIR : before;
        RentalItemEvent rootEvent = resolveProcessRootEvent(process);
        RentalItemEvent event = findLatestRepairTaskCompleted(boardTask == null ? null : boardTask.getId())
                .orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, process == null ? rentalItem.getWarehouse() : process.getWarehouse(), OffsetDateTime.now(), null, before, after, RentalItemEventType.REPAIR_TASK_COMPLETED, "REPAIR_PROCESS", null);
        applyProcessLinks(event, process, boardTask, queueEntry, workerGroup, worker);
        applyChildHierarchy(event, rootEvent, rootEvent == null ? processGroupKey(process) : rootEvent.getEventGroupKey(), true);
        event.setMobileTaskKey(null);
        event.setMobileCreationMode(null);
        event.setTitle(boardTask == null || boardTask.getTitle() == null ? "Задача ремонта завершена" : "Задача ремонта завершена: " + boardTask.getTitle());
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordRepairReadyForCheck(RepairProcess process) {
        RentalItem rentalItem = process == null ? null : process.getRentalItem();
        if (rentalItem == null) {
            return null;
        }
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = RentalItemStatus.WAITING_REPAIR_CHECK;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent rootEvent = resolveProcessRootEvent(process);
        RentalItemEvent event = findLatestRepairReadyForCheck(process == null ? null : process.getId())
                .orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, process == null ? rentalItem.getWarehouse() : process.getWarehouse(), OffsetDateTime.now(), process == null ? null : process.getComment(), before, after, RentalItemEventType.REPAIR_READY_FOR_CHECK, "REPAIR_PROCESS", null);
        if (process != null && process.getEstimate() != null && process.getEstimate().getId() != null) {
            event.setEstimate(dataManager.getReference(RepairEstimate.class, process.getEstimate().getId()));
        }
        event.setRepairProcess(process == null ? null : dataManager.getReference(RepairProcess.class, process.getId()));
        applyChildHierarchy(event, rootEvent, rootEvent == null ? processGroupKey(process) : rootEvent.getEventGroupKey(), true);
        clearInventoryAndBoardLinks(event);
        event.setTitle("Ремонт готов к проверке " + safeNumber(rentalItem));
        return dataManager.save(event);
    }

    @Transactional
    public RentalItemEvent recordRepairAccepted(RepairProcess process, String comment) {
        RentalItem rentalItem = process == null ? null : process.getRentalItem();
        if (rentalItem == null) {
            return null;
        }
        RentalItemStatus before = statusOf(rentalItem);
        RentalItemStatus after = RentalItemStatus.READY;
        rentalItem.setStatus(after.getId());
        dataManager.save(rentalItem);

        RentalItemEvent rootEvent = resolveProcessRootEvent(process);
        RentalItemEvent event = findLatestRepairAccepted(process == null ? null : process.getId())
                .orElseGet(() -> dataManager.create(RentalItemEvent.class));
        populateBase(event, rentalItem, process == null ? rentalItem.getWarehouse() : process.getWarehouse(), OffsetDateTime.now(), comment, before, after, RentalItemEventType.REPAIR_ACCEPTED, "REPAIR_PROCESS", null);
        if (process != null && process.getEstimate() != null && process.getEstimate().getId() != null) {
            event.setEstimate(dataManager.getReference(RepairEstimate.class, process.getEstimate().getId()));
        }
        event.setRepairProcess(process == null ? null : dataManager.getReference(RepairProcess.class, process.getId()));
        applyChildHierarchy(event, rootEvent, rootEvent == null ? processGroupKey(process) : rootEvent.getEventGroupKey(), true);
        clearInventoryAndBoardLinks(event);
        event.setTitle("Ремонт принят " + safeNumber(rentalItem));
        return dataManager.save(event);
    }

    private RentalItemEvent populateBase(RentalItemEvent event,
                                         RentalItem rentalItem,
                                         Warehouse warehouse,
                                         OffsetDateTime eventDate,
                                         String comment,
                                         RentalItemStatus before,
                                         RentalItemStatus after,
                                         RentalItemEventType type,
                                         String source,
                                         String sourceComment) {
        event.setRentalItem(rentalItem == null ? null : dataManager.getReference(RentalItem.class, rentalItem.getId()));
        event.setWarehouse(warehouse == null ? null : dataManager.getReference(Warehouse.class, warehouse.getId()));
        event.setEventDate(eventDate == null ? OffsetDateTime.now() : eventDate);
        event.setComment(trimToNull(comment));
        event.setStatusBefore(before == null ? null : before.getId());
        event.setStatusAfter(after == null ? null : after.getId());
        event.setEventType(type == null ? RentalItemEventType.MANUAL : type);
        event.setSource(trimToNull(source));
        event.setSourceComment(trimToNull(sourceComment));
        ActorContext actorContext = currentActor();
        event.setActorUsername(actorContext.username());
        event.setActorDisplayName(actorContext.displayName());
        return event;
    }

    private void applyRootHierarchy(RentalItemEvent event, String groupKey) {
        if (event == null) {
            return;
        }
        event.setParentEvent(null);
        event.setEventGroupKey(trimToNull(groupKey));
        event.setEventOrder(0);
    }

    private void applyChildHierarchy(RentalItemEvent event, RentalItemEvent parent, String groupKey, boolean reuseOrder) {
        if (event == null) {
            return;
        }
        if (parent == null || parent.getId() == null) {
            applyRootHierarchy(event, groupKey);
            return;
        }
        event.setParentEvent(dataManager.getReference(RentalItemEvent.class, parent.getId()));
        event.setEventGroupKey(trimToNull(groupKey));
        if (!reuseOrder || event.getEventOrder() == null) {
            event.setEventOrder(nextChildOrder(parent.getId(), event.getId()));
        }
    }

    private RentalItemEvent resolveProcessRootEvent(RepairProcess process) {
        if (process == null || process.getId() == null) {
            return null;
        }
        RentalItemEvent root = dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.repairProcess.id = :processId
                          and e.parentEvent is null
                          and e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.ESTIMATE_COMPLETED
                        order by e.createdDate desc
                        """)
                .parameter("processId", process.getId())
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base"))
                .optional()
                .orElse(null);
        if (root != null) {
            return root;
        }
        RepairEstimate estimate = process.getEstimate();
        return estimate == null || estimate.getId() == null ? null : findEstimateRootEvent(estimate.getId()).orElse(null);
    }

    private RepairProcess findProcessByEstimateId(UUID estimateId) {
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

    private Integer nextChildOrder(UUID parentEventId, UUID currentEventId) {
        if (parentEventId == null) {
            return 0;
        }
        List<RentalItemEvent> siblings = dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.parentEvent.id = :parentEventId
                          and (:currentEventId is null or e.id <> :currentEventId)
                        """)
                .parameter("parentEventId", parentEventId)
                .parameter("currentEventId", currentEventId)
                .list();
        int max = 0;
        for (RentalItemEvent sibling : siblings) {
            Integer order = sibling == null ? null : sibling.getEventOrder();
            if (order != null && order > max) {
                max = order;
            }
        }
        return max + 1;
    }

    private String estimateGroupKey(RepairEstimate estimate) {
        return estimate == null || estimate.getId() == null ? null : GROUP_PREFIX_ESTIMATE + estimate.getId();
    }

    private String processGroupKey(RepairProcess process) {
        return process == null || process.getId() == null ? null : GROUP_PREFIX_PROCESS + process.getId();
    }

    private String inventoryGroupKey(RentalItem rentalItem) {
        return rentalItem == null || rentalItem.getId() == null ? null : GROUP_PREFIX_INVENTORY + rentalItem.getId();
    }

    private List<RentalItemEvent> orderTimeline(List<RentalItemEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Map<UUID, RentalItemEvent> byId = events.stream()
                .filter(Objects::nonNull)
                .filter(event -> event.getId() != null)
                .collect(Collectors.toMap(RentalItemEvent::getId, event -> event, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, List<RentalItemEvent>> childrenByParent = new LinkedHashMap<>();
        List<RentalItemEvent> roots = new ArrayList<>();
        for (RentalItemEvent event : events) {
            if (event == null || event.getId() == null) {
                continue;
            }
            RentalItemEvent parent = event.getParentEvent();
            if (parent != null && parent.getId() != null && byId.containsKey(parent.getId())) {
                childrenByParent.computeIfAbsent(parent.getId(), key -> new ArrayList<>()).add(event);
            } else {
                roots.add(event);
            }
        }
        roots.sort(rootComparator());
        List<RentalItemEvent> ordered = new ArrayList<>(events.size());
        for (RentalItemEvent root : roots) {
            appendTimelineBranch(root, childrenByParent, ordered, new java.util.HashSet<>());
        }
        return ordered;
    }

    private void appendTimelineBranch(RentalItemEvent event,
                                      Map<UUID, List<RentalItemEvent>> childrenByParent,
                                      List<RentalItemEvent> ordered,
                                      java.util.Set<UUID> visited) {
        if (event == null || event.getId() == null || !visited.add(event.getId())) {
            return;
        }
        ordered.add(event);
            List<RentalItemEvent> children = childrenByParent.getOrDefault(event.getId(), List.of());
        if (!children.isEmpty()) {
            children.stream()
                    .sorted(childComparator())
                    .forEach(child -> appendTimelineBranch(child, childrenByParent, ordered, visited));
        }
    }

    private Comparator<RentalItemEvent> rootComparator() {
        return Comparator
                .comparing((RentalItemEvent event) -> event.getEventDate() == null ? OffsetDateTime.MIN : event.getEventDate(), Comparator.reverseOrder())
                .thenComparing(event -> event.getCreatedDate() == null ? OffsetDateTime.MIN : event.getCreatedDate(), Comparator.reverseOrder())
                .thenComparing(event -> event.getId() == null ? new UUID(0L, 0L) : event.getId(), Comparator.reverseOrder());
    }

    private Comparator<RentalItemEvent> childComparator() {
        return Comparator
                .comparing((RentalItemEvent event) -> event.getEventOrder() == null ? Integer.MAX_VALUE : event.getEventOrder())
                .thenComparing(event -> event.getEventDate() == null ? OffsetDateTime.MIN : event.getEventDate())
                .thenComparing(event -> event.getCreatedDate() == null ? OffsetDateTime.MIN : event.getCreatedDate())
                .thenComparing(event -> event.getId() == null ? new UUID(0L, 0L) : event.getId());
    }

    private void applyProcessLinks(RentalItemEvent event,
                                   RepairProcess process,
                                   BoardTask boardTask,
                                   QueueEntry queueEntry,
                                   WorkerGroup workerGroup,
                                   Worker worker) {
        if (process != null && process.getEstimate() != null && process.getEstimate().getId() != null) {
            event.setEstimate(dataManager.getReference(RepairEstimate.class, process.getEstimate().getId()));
        }
        if (process != null && process.getId() != null) {
            event.setRepairProcess(dataManager.getReference(RepairProcess.class, process.getId()));
        }
        if (boardTask != null && boardTask.getId() != null) {
            event.setBoardTask(dataManager.getReference(BoardTask.class, boardTask.getId()));
        }
        if (queueEntry != null && queueEntry.getId() != null) {
            event.setQueueEntry(dataManager.getReference(QueueEntry.class, queueEntry.getId()));
        }
        if (workerGroup != null && workerGroup.getId() != null) {
            event.setWorkerGroup(dataManager.getReference(WorkerGroup.class, workerGroup.getId()));
        }
        if (worker != null && worker.getId() != null) {
            event.setWorker(dataManager.getReference(Worker.class, worker.getId()));
        }
    }

    private void clearEstimateAndProcessLinks(RentalItemEvent event) {
        event.setEstimate(null);
        event.setRepairProcess(null);
        event.setBoardTask(null);
        event.setQueueEntry(null);
        event.setWorkerGroup(null);
        event.setWorker(null);
    }

    private void clearInventoryAndRepairLinks(RentalItemEvent event) {
        event.setMobileTaskKey(null);
        event.setMobileCreationMode(null);
        event.setRepairProcess(null);
        event.setBoardTask(null);
        event.setQueueEntry(null);
        event.setWorkerGroup(null);
        event.setWorker(null);
    }

    private void clearInventoryAndBoardLinks(RentalItemEvent event) {
        event.setMobileTaskKey(null);
        event.setMobileCreationMode(null);
        event.setBoardTask(null);
        event.setQueueEntry(null);
        event.setWorkerGroup(null);
        event.setWorker(null);
    }

    private boolean estimateRequiresRepair(RepairEstimate estimate, boolean movementRequired) {
        if (movementRequired) {
            return true;
        }
        List<RepairEstimateLine> lines = dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate.id = :estimateId order by e.rowOrder, e.id")
                .parameter("estimateId", estimate.getId())
                .list();
        if (lines.isEmpty()) {
            return false;
        }
        return lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> line.getCatalogCode() == null || !repairCatalogService.isFurnitureCode(line.getCatalogCode()))
                .findFirst()
                .isPresent();
    }

    private void syncEventAccessories(RentalItemEvent event, List<RentalItemAccessory> accessories) {
        if (event == null || event.getId() == null) {
            return;
        }
        List<RentalItemEventAccessory> existing = loadEventAccessories(event.getId());
        if (!existing.isEmpty()) {
            dataManager.remove(existing);
        }
        if (accessories == null || accessories.isEmpty()) {
            return;
        }
        for (RentalItemAccessory assignment : accessories) {
            if (assignment == null
                    || assignment.getAccessoryItem() == null
                    || assignment.getAccessoryItem().getId() == null
                    || assignment.getQuantity() == null
                    || assignment.getQuantity() <= 0) {
                continue;
            }
            RentalItemEventAccessory snapshot = dataManager.create(RentalItemEventAccessory.class);
            snapshot.setEvent(dataManager.getReference(RentalItemEvent.class, event.getId()));
            snapshot.setAccessoryItem(dataManager.getReference(AccessoryItem.class, assignment.getAccessoryItem().getId()));
            snapshot.setQuantity(assignment.getQuantity());
            dataManager.save(snapshot);
        }
    }

    private java.util.Optional<RentalItemEvent> findEstimateRootEvent(UUID estimateId) {
        if (estimateId == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.estimate.id = :estimateId
                          and e.parentEvent is null
                          and (
                               e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.ESTIMATE_DRAFT
                               or e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.ESTIMATE_COMPLETED
                               or e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.ESTIMATE
                          )
                        order by e.createdDate desc
                        """)
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", "_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional();
    }

    private java.util.Optional<RentalItemEvent> findEstimateChildEvent(UUID estimateId) {
        if (estimateId == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.estimate.id = :estimateId
                          and e.parentEvent is not null
                        order by e.createdDate desc
                        """)
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", "_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional();
    }

    private java.util.Optional<RentalItemEvent> findByMobileTaskKey(String mobileTaskKey) {
        String normalized = trimToNull(mobileTaskKey);
        if (normalized == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.mobileTaskKey = :mobileTaskKey
                        order by e.createdDate desc
                        """)
                .parameter("mobileTaskKey", normalized)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base")))
                .optional();
    }

    private java.util.Optional<RentalItemEvent> findLatestRepairTaskStarted(UUID boardTaskId) {
        if (boardTaskId == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.boardTask.id = :boardTaskId
                          and e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.REPAIR_TASK_STARTED
                        order by e.createdDate desc
                        """)
                .parameter("boardTaskId", boardTaskId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", "_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional();
    }

    private java.util.Optional<RentalItemEvent> findLatestRepairTaskCompleted(UUID boardTaskId) {
        if (boardTaskId == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.boardTask.id = :boardTaskId
                          and e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.REPAIR_TASK_COMPLETED
                        order by e.createdDate desc
                        """)
                .parameter("boardTaskId", boardTaskId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", "_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional();
    }

    private java.util.Optional<RentalItemEvent> findLatestRepairReadyForCheck(UUID repairProcessId) {
        if (repairProcessId == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.repairProcess.id = :repairProcessId
                          and e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.REPAIR_READY_FOR_CHECK
                        order by e.createdDate desc
                        """)
                .parameter("repairProcessId", repairProcessId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("parentEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("parentEvent", "_base"))
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", "_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional();
    }

    private java.util.Optional<RentalItemEvent> findLatestRepairAccepted(UUID repairProcessId) {
        if (repairProcessId == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.repairProcess.id = :repairProcessId
                          and e.eventType = dev.buhanzaz.wmspanel.entity.RentalItemEventType.REPAIR_ACCEPTED
                        order by e.createdDate desc
                        """)
                .parameter("repairProcessId", repairProcessId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("repairProcess", "_base")
                        .add("boardTask", "_base")
                        .add("queueEntry", "_base")
                        .add("workerGroup", "_base")
                        .add("worker", "_base"))
                .optional();
    }

    private RentalItemStatus statusOf(RentalItem rentalItem) {
        if (rentalItem == null) {
            return null;
        }
        return RentalItemStatus.fromId(rentalItem.getStatus());
    }

    private ActorContext currentActor() {
        String username = warehouseAccessService.username();
        if (username == null || username.isBlank()) {
            return new ActorContext("system", "system");
        }
        User user = dataManager.load(User.class)
                .query("select e from wmspanel_User e where e.username = :username")
                .parameter("username", username)
                .optional()
                .orElse(null);
        String displayName = user == null ? username : user.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }
        return new ActorContext(username, displayName);
    }

    private String safeNumber(RentalItem rentalItem) {
        return rentalItem == null || rentalItem.getNumber() == null ? "объект" : rentalItem.getNumber();
    }

    private String buildAccessorySummary(List<RentalItemAccessory> accessories, String comment) {
        List<String> parts = new ArrayList<>();
        if (comment != null && !comment.isBlank()) {
            parts.add(comment.trim());
        }
        if (accessories != null) {
            for (RentalItemAccessory accessory : accessories) {
                if (accessory == null
                        || accessory.getAccessoryItem() == null
                        || accessory.getAccessoryItem().getName() == null
                        || accessory.getAccessoryItem().getName().isBlank()
                        || accessory.getQuantity() == null
                        || accessory.getQuantity() <= 0) {
                    continue;
                }
                parts.add(accessory.getAccessoryItem().getName() + " x" + accessory.getQuantity());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ActorContext(String username, String displayName) {
    }
}
