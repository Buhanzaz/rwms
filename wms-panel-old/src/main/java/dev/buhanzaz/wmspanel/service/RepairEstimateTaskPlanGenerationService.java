package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLineType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlanGenerationStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskLine;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RepairEstimateTaskPlanGenerationService {

    private final DataManager dataManager;
    private final QueueBoardService queueBoardService;
    private final RepairProcessService repairProcessService;
    private final RentalItemEventService rentalItemEventService;

    public RepairEstimateTaskPlanGenerationService(DataManager dataManager,
                                                   QueueBoardService queueBoardService,
                                                   RepairProcessService repairProcessService,
                                                   RentalItemEventService rentalItemEventService) {
        this.dataManager = dataManager;
        this.queueBoardService = queueBoardService;
        this.repairProcessService = repairProcessService;
        this.rentalItemEventService = rentalItemEventService;
    }

    @Transactional
    public void generateTasks(List<RepairEstimateTaskPlan> taskPlans) {
        if (taskPlans == null || taskPlans.isEmpty()) {
            return;
        }
        RepairEstimateTaskPlan firstPlan = taskPlans.get(0);
        UUID estimateId = firstPlan.getEstimate() == null ? null : firstPlan.getEstimate().getId();
        generateTasks(estimateId, taskPlans);
    }

    @Transactional
    public void generateTasks(UUID estimateId, List<RepairEstimateTaskPlan> taskPlans) {
        if (estimateId == null) {
            return;
        }
        RepairProcess process = repairProcessService.findByEstimateId(estimateId);
        boolean hasPlannedTasks = taskPlans != null && !taskPlans.isEmpty();
        boolean movementRequired = process != null
                && (Boolean.TRUE.equals(process.getMoveToRepairRequired())
                || Boolean.TRUE.equals(process.getMoveFromRepairRequired()));
        if (taskPlans != null) {
            for (RepairEstimateTaskPlan taskPlan : taskPlans) {
                generateTasks(taskPlan);
            }
        }
        if (process != null && process.getId() != null) {
            ensureMovementTasks(process.getId());
            boolean readyAfterRepair = repairProcessService.syncProcessStatus(process);
            if (readyAfterRepair) {
                rentalItemEventService.recordRepairReadyForCheck(process);
            } else {
                List<BoardTask> tasks = repairProcessService.loadProcessTasks(process.getId());
                if (!hasPlannedTasks && !movementRequired && tasks.isEmpty()) {
                    rentalItemEventService.recordEstimateCompleted(process.getEstimate(), false);
                } else {
                    rentalItemEventService.recordEstimateCompleted(process.getEstimate(), movementRequired);
                }
            }
        }
    }

    @Transactional
    public void generateTasks(RepairEstimateTaskPlan taskPlan) {
        if (taskPlan == null || taskPlan.getId() == null) {
            return;
        }
        RepairEstimateTaskPlan plan = loadPlan(taskPlan.getId());
        if (plan.getGeneratedBoardTask() != null) {
            if (plan.getGenerationStatus() != RepairEstimateTaskPlanGenerationStatus.GENERATED) {
                plan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.GENERATED);
                dataManager.save(plan);
            }
            return;
        }
        if (plan.getGenerationStatus() == RepairEstimateTaskPlanGenerationStatus.GENERATED) {
            return;
        }

        RepairEstimate estimate = plan.getEstimate();
        if (estimate == null || estimate.getRentalItem() == null || plan.getRepairProcess() == null) {
            markFailed(plan);
            return;
        }
        WorkQueue queue = plan.getQueue();
        if (queue == null) {
            if (plan.getGenerationStatus() != RepairEstimateTaskPlanGenerationStatus.FAILED) {
                plan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
                dataManager.save(plan);
            }
            return;
        }
        if (queue.getWarehouse() == null || estimate.getWarehouse() == null
                || !Objects.equals(queue.getWarehouse().getId(), estimate.getWarehouse().getId())) {
            markFailed(plan);
            return;
        }

        String taskDescription = buildTaskDescription(plan);
        Integer plannedDurationMinutes = plannedDurationMinutes(plan);
        BoardTask createdTask = queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                estimate.getRentalItem(),
                taskDescription,
                null,
                List.of(new QueueBoardService.QueueTaskStep(queue, taskDescription, plannedDurationMinutes)),
                plan.getRepairProcess(),
                RepairProcessTaskKind.REPAIR_WORK));

        plan.setGeneratedBoardTask(createdTask);
        plan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        dataManager.save(plan);
    }

    @Transactional
    public void ensureMovementTasks(UUID processId) {
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", builder1 -> builder1.addFetchPlan("_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base"))
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base"))
                .one();
        if (!Boolean.TRUE.equals(process.getMoveToRepairRequired()) && !Boolean.TRUE.equals(process.getMoveFromRepairRequired())) {
            return;
        }
        List<BoardTask> tasks = repairProcessService.loadProcessTasks(processId);
        if (Boolean.TRUE.equals(process.getMoveToRepairRequired())
                && tasks.stream().noneMatch(task -> task.getTaskKind() == RepairProcessTaskKind.MOVE_TO_REPAIR)) {
            createMovementTask(process, RepairProcessTaskKind.MOVE_TO_REPAIR, "Перемещение на ремонт",
                    List.of("MOVE_TO_REPAIR", "TRANSFER_TO_REPAIR"));
        }
        if (Boolean.TRUE.equals(process.getMoveFromRepairRequired())
                && tasks.stream().noneMatch(task -> task.getTaskKind() == RepairProcessTaskKind.MOVE_FROM_REPAIR)) {
            createMovementTask(process, RepairProcessTaskKind.MOVE_FROM_REPAIR, "Перемещение с ремонта",
                    List.of("MOVE_FROM_REPAIR", "TRANSFER_FROM_REPAIR", "MOVE_TO_STORAGE"));
        }
    }

    private void createMovementTask(RepairProcess process, RepairProcessTaskKind taskKind, String title, List<String> queueCodes) {
        WorkQueue queue = resolveMovementQueue(process, queueCodes, taskKind);
        if (queue == null) {
            return;
        }
        String description = title + ": " + process.getRentalItem().getNumber();
        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                process.getRentalItem(),
                description,
                null,
                List.of(new QueueBoardService.QueueTaskStep(queue, description, null)),
                process,
                taskKind));
    }

    private WorkQueue resolveMovementQueue(RepairProcess process, List<String> queueCodes, RepairProcessTaskKind taskKind) {
        for (String queueCode : queueCodes) {
            WorkQueue exact = dataManager.load(WorkQueue.class)
                    .query("""
                            select e from WorkQueue e
                            where e.warehouse = :warehouse
                              and e.code = :code
                            """)
                    .parameter("warehouse", process.getWarehouse())
                    .parameter("code", queueCode)
                    .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                    .optional()
                    .orElse(null);
            if (exact != null) {
                return exact;
            }
        }
        String nameMarker = taskKind == RepairProcessTaskKind.MOVE_TO_REPAIR ? "ремонт" : "ремонта";
        return dataManager.load(WorkQueue.class)
                .query("""
                        select e from WorkQueue e
                        where e.warehouse = :warehouse
                          and lower(e.name) like :nameMarker
                        order by e.sortOrder, e.name
                        """)
                .parameter("warehouse", process.getWarehouse())
                .parameter("nameMarker", "%" + nameMarker + "%")
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .optional()
                .orElse(null);
    }

    private RepairEstimateTaskPlan loadPlan(UUID planId) {
        return dataManager.load(RepairEstimateTaskPlan.class)
                .id(planId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", builder1 -> builder1.addFetchPlan("_base")
                                .add("rentalItem", "_base")
                                .add("warehouse", "_base"))
                        .add("estimateLine", builder1 -> builder1.addFetchPlan("_base").add("estimate", "_base"))
                        .add("repairProcess", "_base")
                        .add("queue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base"))
                        .add("generatedBoardTask", "_base")
                        .add("taskLines", builder1 -> builder1.addFetchPlan("_base").add("estimateLine", "_base")))
                .one();
    }

    private void markFailed(RepairEstimateTaskPlan plan) {
        if (plan.getGenerationStatus() != RepairEstimateTaskPlanGenerationStatus.FAILED) {
            plan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.FAILED);
            dataManager.save(plan);
        }
    }

    private String buildTaskDescription(RepairEstimateTaskPlan plan) {
        List<String> parts = new ArrayList<>();
        List<RepairProcessTaskLine> taskLines = plan.getTaskLines() == null ? List.of() : plan.getTaskLines();
        List<RepairEstimateLine> workLines = taskLines.stream()
                .map(RepairProcessTaskLine::getEstimateLine)
                .filter(Objects::nonNull)
                .filter(line -> line.getLineType() == RepairEstimateLineType.WORK)
                .toList();
        List<RepairEstimateLine> materialLines = taskLines.stream()
                .map(RepairProcessTaskLine::getEstimateLine)
                .filter(Objects::nonNull)
                .filter(line -> line.getLineType() == RepairEstimateLineType.MATERIAL)
                .toList();

        if (!workLines.isEmpty()) {
            parts.add("Работы: " + workLines.stream()
                    .map(RepairEstimateLine::getDescription)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("Работы"));
        }
        String lineComments = workLines.stream()
                .map(RepairEstimateLine::getLineComment)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + "; " + right)
                .orElse(null);
        if (lineComments != null) {
            parts.add("Комментарий строки: " + lineComments);
        }
        if (!materialLines.isEmpty()) {
            parts.add("Материалы: " + materialLines.stream()
                    .map(this::formatMaterialLine)
                    .reduce((left, right) -> left + "; " + right)
                    .orElse(""));
        }
        if (plan.getGroupComment() != null && !plan.getGroupComment().isBlank()) {
            parts.add("Комментарий группы: " + plan.getGroupComment().trim());
        }
        return parts.isEmpty() ? "Ремонтное подзадание" : String.join(" | ", parts);
    }

    private String formatMaterialLine(RepairEstimateLine line) {
        String description = line.getDescription() == null ? "Материал" : line.getDescription().trim();
        Integer quantity = line.getQuantity() == null || line.getQuantity() < 1 ? 1 : line.getQuantity();
        String unit = line.getUnit() == null ? "" : line.getUnit().trim();
        return description + " x" + quantity + (unit.isBlank() ? "" : " " + unit);
    }

    private Integer plannedDurationMinutes(RepairEstimateTaskPlan plan) {
        List<RepairProcessTaskLine> taskLines = plan.getTaskLines() == null ? List.of() : plan.getTaskLines();
        int total = 0;
        for (RepairProcessTaskLine taskLine : taskLines) {
            RepairEstimateLine line = taskLine.getEstimateLine();
            if (line == null || line.getLineType() != RepairEstimateLineType.WORK) {
                continue;
            }
            Integer duration = loadCatalogDuration(line.getCatalogCode());
            if (duration == null || duration <= 0) {
                continue;
            }
            int quantity = line.getQuantity() == null || line.getQuantity() < 1 ? 1 : line.getQuantity();
            total += duration * quantity;
        }
        return total > 0 ? total : null;
    }

    private Integer loadCatalogDuration(String catalogCode) {
        String normalizedCode = catalogCode == null ? null : catalogCode.trim().toUpperCase();
        if (normalizedCode == null || normalizedCode.isBlank()) {
            return null;
        }
        return dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e
                        from RepairEstimateCatalogNode e
                        where upper(e.code) = :code
                        """)
                .parameter("code", normalizedCode)
                .list()
                .stream()
                .filter(node -> node.getNodeType() == RepairEstimateCatalogNodeType.WORK)
                .map(RepairEstimateCatalogNode::getDurationMinutes)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
