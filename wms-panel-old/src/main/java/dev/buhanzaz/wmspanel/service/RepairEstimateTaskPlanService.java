package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLineType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlanGenerationStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskLine;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RepairEstimateTaskPlanService {

    private final DataManager dataManager;
    private final RepairCatalogService repairCatalogService;
    private final RepairProcessService repairProcessService;

    public RepairEstimateTaskPlanService(DataManager dataManager,
                                         RepairCatalogService repairCatalogService,
                                         RepairProcessService repairProcessService) {
        this.dataManager = dataManager;
        this.repairCatalogService = repairCatalogService;
        this.repairProcessService = repairProcessService;
    }

    public List<RepairEstimateTaskPlan> loadTaskPlans(UUID estimateId) {
        return dataManager.load(RepairEstimateTaskPlan.class)
                .query("""
                        select e from RepairEstimateTaskPlan e
                        where e.estimate.id = :estimateId
                        order by e.sortOrder, e.id
                        """)
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("estimateLine", builder1 -> builder1.addFetchPlan("_base").add("estimate", "_base"))
                        .add("repairProcess", "_base")
                        .add("queue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base"))
                        .add("followUpNode", "_base")
                        .add("generatedBoardTask", "_base")
                        .add("taskLines", builder1 -> builder1.addFetchPlan("_base").add("estimateLine", "_base")))
                .list();
    }

    @Transactional
    public List<RepairEstimateTaskPlan> prepareTaskPlans(UUID estimateId) {
        return prepareTaskPlans(estimateId, false);
    }

    @Transactional
    public List<RepairEstimateTaskPlan> prepareTaskPlans(UUID estimateId, boolean movementRequired) {
        RepairEstimate estimate = dataManager.load(RepairEstimate.class)
                .id(estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("rentalItem", "_base"))
                .one();

        List<RepairEstimateLine> lines = dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate.id = :estimateId order by e.rowOrder, e.id")
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("estimate", "_base"))
                .list();

        List<GroupPlanSeed> seeds = buildGroupedSeeds(lines);
        RepairProcess process = null;
        if (movementRequired || !seeds.isEmpty()) {
            process = repairProcessService.ensureProcess(estimate, movementRequired);
        }
        Map<UUID, RepairEstimateTaskPlan> existingByPrimaryLineId = loadTaskPlans(estimateId).stream()
                .filter(plan -> plan.getEstimateLine() != null && plan.getEstimateLine().getId() != null)
                .collect(Collectors.toMap(plan -> plan.getEstimateLine().getId(), plan -> plan, (left, right) -> left, LinkedHashMap::new));

        Map<UUID, List<RepairProcessTaskLine>> existingTaskLinesByPlanId = loadTaskLinesForEstimate(estimateId).stream()
                .filter(taskLine -> taskLine.getTaskPlan() != null && taskLine.getTaskPlan().getId() != null)
                .collect(Collectors.groupingBy(taskLine -> taskLine.getTaskPlan().getId(), LinkedHashMap::new, Collectors.toList()));

        List<RepairEstimateTaskPlan> plansToSave = new ArrayList<>();
        List<RepairProcessTaskLine> taskLinesToSave = new ArrayList<>();
        List<RepairProcessTaskLine> taskLinesToRemove = new ArrayList<>();

        for (GroupPlanSeed seed : seeds) {
            RepairEstimateTaskPlan plan = existingByPrimaryLineId.remove(seed.primaryLine().getId());
            if (plan == null) {
                plan = createTaskPlanForLine(estimate, seed.primaryLine());
            }
            applySeedDefaults(plan, estimate, process, seed);
            plansToSave.add(plan);
        }
        if (!plansToSave.isEmpty()) {
            dataManager.save(plansToSave.toArray());
        }

        for (RepairEstimateTaskPlan plan : plansToSave) {
            GroupPlanSeed seed = seeds.stream()
                    .filter(candidate -> Objects.equals(candidate.primaryLine().getId(), plan.getEstimateLine().getId()))
                    .findFirst()
                    .orElse(null);
            if (seed == null || plan.getId() == null) {
                continue;
            }
            Map<UUID, RepairProcessTaskLine> existingTaskLineByLineId = existingTaskLinesByPlanId
                    .getOrDefault(plan.getId(), List.of())
                    .stream()
                    .filter(taskLine -> taskLine.getEstimateLine() != null && taskLine.getEstimateLine().getId() != null)
                    .collect(Collectors.toMap(taskLine -> taskLine.getEstimateLine().getId(), taskLine -> taskLine, (left, right) -> left, LinkedHashMap::new));
            int sortOrder = 0;
            for (RepairEstimateLine line : seed.lines()) {
                RepairProcessTaskLine taskLine = existingTaskLineByLineId.remove(line.getId());
                if (taskLine == null) {
                    taskLine = dataManager.create(RepairProcessTaskLine.class);
                    taskLine.setTaskPlan(plan);
                    taskLine.setEstimateLine(line);
                }
                taskLine.setLineType(line.getLineType());
                taskLine.setSortOrder(sortOrder++);
                taskLine.setPrimaryWorkLine(Objects.equals(line.getId(), seed.primaryLine().getId()));
                taskLinesToSave.add(taskLine);
            }
            taskLinesToRemove.addAll(existingTaskLineByLineId.values());
        }

        if (!taskLinesToRemove.isEmpty()) {
            dataManager.remove(taskLinesToRemove.toArray());
        }
        if (!taskLinesToSave.isEmpty()) {
            dataManager.save(taskLinesToSave.toArray());
        }
        if (!existingByPrimaryLineId.isEmpty()) {
            removeTaskLinesForPlans(existingByPrimaryLineId.values());
            dataManager.remove(existingByPrimaryLineId.values().toArray());
        }

        return loadTaskPlans(estimateId);
    }

    @Transactional
    public List<RepairEstimateTaskPlan> saveTaskPlans(UUID estimateId, List<RepairEstimateTaskPlan> taskPlans) {
        RepairEstimate estimate = dataManager.load(RepairEstimate.class)
                .id(estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .one();
        RepairProcess process = repairProcessService.findByEstimateId(estimateId);
        if (process == null) {
            process = repairProcessService.ensureProcess(estimate, false);
        }

        Map<UUID, RepairEstimateTaskPlan> existingById = loadTaskPlans(estimateId).stream()
                .filter(plan -> plan.getId() != null)
                .collect(Collectors.toMap(RepairEstimateTaskPlan::getId, plan -> plan, (left, right) -> left, LinkedHashMap::new));
        List<RepairEstimateTaskPlan> toSave = new ArrayList<>();

        for (RepairEstimateTaskPlan incoming : taskPlans) {
            if (incoming == null || incoming.getEstimateLine() == null || incoming.getEstimateLine().getId() == null) {
                continue;
            }
            RepairEstimateLine estimateLine = dataManager.load(RepairEstimateLine.class)
                    .id(incoming.getEstimateLine().getId())
                    .fetchPlan(builder -> builder.addFetchPlan("_base").add("estimate", "_base"))
                    .one();
            if (!Objects.equals(estimateLine.getEstimate().getId(), estimate.getId())) {
                throw new IllegalArgumentException("Task plan line does not belong to the selected estimate");
            }
            if (!isWorkLine(estimateLine)) {
                continue;
            }
            RepairEstimateTaskPlan target = incoming.getId() == null ? null : existingById.remove(incoming.getId());
            boolean newPlan = target == null;
            boolean generationInputsChanged = !newPlan && generationInputsChanged(target, incoming);
            if (newPlan) {
                target = dataManager.create(RepairEstimateTaskPlan.class);
            }

            target.setEstimate(estimate);
            target.setEstimateLine(estimateLine);
            target.setRepairProcess(incoming.getRepairProcess() == null ? process : incoming.getRepairProcess());
            target.setSortOrder(incoming.getSortOrder() == null ? estimateLine.getRowOrder() : incoming.getSortOrder());
            target.setQueue(incoming.getQueue());
            target.setGroupComment(incoming.getGroupComment());
            target.setFollowUpNode(incoming.getFollowUpNode());
            target.setComment(incoming.getComment());
            target.setActive(incoming.getActive() == null ? true : incoming.getActive());

            if (target.getQueue() != null && target.getQueue().getWarehouse() != null
                    && estimate.getWarehouse() != null
                    && !Objects.equals(target.getQueue().getWarehouse().getId(), estimate.getWarehouse().getId())) {
                throw new IllegalArgumentException("Queue warehouse must match estimate warehouse");
            }
            if (newPlan) {
                target.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
                target.setGeneratedBoardTask(null);
            } else if (generationInputsChanged) {
                target.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
                target.setGeneratedBoardTask(null);
            }
            toSave.add(target);
        }

        if (!existingById.isEmpty()) {
            removeTaskLinesForPlans(existingById.values());
            dataManager.remove(existingById.values().toArray());
        }
        if (!toSave.isEmpty()) {
            dataManager.save(toSave.toArray());
        }
        return loadTaskPlans(estimateId);
    }

    @Transactional
    public void deleteTaskPlans(UUID estimateId) {
        List<RepairProcessTaskLine> taskLines = loadTaskLinesForEstimate(estimateId);
        if (!taskLines.isEmpty()) {
            dataManager.remove(taskLines.toArray());
        }
        List<RepairEstimateTaskPlan> plans = loadTaskPlans(estimateId);
        if (!plans.isEmpty()) {
            dataManager.remove(plans.toArray());
        }
    }

    public List<RepairProcessTaskLine> loadTaskLinesForEstimate(UUID estimateId) {
        return dataManager.load(RepairProcessTaskLine.class)
                .query("""
                        select e from RepairProcessTaskLine e
                        where e.taskPlan.estimate.id = :estimateId
                        order by e.taskPlan.sortOrder, e.sortOrder, e.id
                        """)
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("taskPlan", "_base")
                        .add("estimateLine", "_base"))
                .list();
    }

    private void removeTaskLinesForPlans(Iterable<RepairEstimateTaskPlan> plans) {
        List<UUID> planIds = new ArrayList<>();
        for (RepairEstimateTaskPlan plan : plans) {
            if (plan != null && plan.getId() != null) {
                planIds.add(plan.getId());
            }
        }
        if (planIds.isEmpty()) {
            return;
        }
        List<RepairProcessTaskLine> taskLines = dataManager.load(RepairProcessTaskLine.class)
                .query("""
                        select e from RepairProcessTaskLine e
                        where e.taskPlan.id in :planIds
                        """)
                .parameter("planIds", planIds)
                .list();
        if (!taskLines.isEmpty()) {
            dataManager.remove(taskLines.toArray());
        }
    }

    private RepairEstimateTaskPlan createTaskPlanForLine(RepairEstimate estimate, RepairEstimateLine line) {
        RepairEstimateTaskPlan plan = dataManager.create(RepairEstimateTaskPlan.class);
        applyLineDefaults(plan, estimate, line);
        plan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
        plan.setGeneratedBoardTask(null);
        plan.setActive(true);
        return plan;
    }

    private void applyLineDefaults(RepairEstimateTaskPlan plan, RepairEstimate estimate, RepairEstimateLine line) {
        plan.setEstimate(estimate);
        plan.setEstimateLine(line);
        plan.setSortOrder(line.getRowOrder());
        RepairEstimateCatalogNode node = resolveCatalogNode(line.getCatalogCode());
        if (node != null && plan.getQueue() == null) {
            plan.setQueue(resolveDefaultQueue(estimate.getWarehouse(), node));
        }
        if (plan.getFollowUpNode() == null) {
            plan.setFollowUpNode(defaultFollowUpNode(line.getCatalogCode()));
        }
        if (plan.getGroupComment() == null || plan.getGroupComment().isBlank()) {
            plan.setGroupComment(line.getLineComment());
        }
        if (plan.getGenerationStatus() == null) {
            plan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
        }
        if (plan.getActive() == null) {
            plan.setActive(true);
        }
    }

    private void applySeedDefaults(RepairEstimateTaskPlan plan, RepairEstimate estimate, RepairProcess process, GroupPlanSeed seed) {
        applyLineDefaults(plan, estimate, seed.primaryLine());
        plan.setRepairProcess(process);
        plan.setComment(seed.summary());
        plan.setGroupComment(seed.groupComment());
        if (seed.catalogNode() != null && plan.getQueue() == null) {
            plan.setQueue(resolveDefaultQueue(estimate.getWarehouse(), seed.catalogNode()));
        }
    }

    public RepairEstimateCatalogNode resolveCatalogNode(String catalogCode) {
        String normalizedCode = normalizeCode(catalogCode);
        if (normalizedCode == null) {
            return null;
        }
        return dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e from RepairEstimateCatalogNode e
                        left join fetch e.workQueue
                        where upper(e.code) = :code
                        """)
                .parameter("code", normalizedCode)
                .optional()
                .orElse(null);
    }

    public WorkQueue resolveDefaultQueue(Warehouse warehouse, RepairEstimateCatalogNode catalogNode) {
        if (warehouse == null || warehouse.getId() == null || catalogNode == null) {
            return null;
        }
        RepairEstimateCatalogNode bindingNode = resolveQueueBindingNode(catalogNode);
        if (bindingNode == null) {
            return null;
        }
        WorkQueue explicitQueue = bindingNode.getWorkQueue();
        if (explicitQueue != null && explicitQueue.getCode() != null && !explicitQueue.getCode().isBlank()) {
            WorkQueue queue = dataManager.load(WorkQueue.class)
                    .query("""
                            select e from WorkQueue e
                            where e.active = true
                              and e.warehouse = :warehouse
                              and upper(e.code) = :code
                            order by e.sortOrder, e.name
                            """)
                    .parameter("warehouse", warehouse)
                    .parameter("code", explicitQueue.getCode().trim().toUpperCase(Locale.ROOT))
                    .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                    .list()
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (queue != null) {
                return queue;
            }
        }
        WorkQueueKind queueKind = bindingNode.getRouteQueueKind();
        if (queueKind == null) {
            return null;
        }
        List<String> queueKinds = queueKind == WorkQueueKind.HOLDING
                ? List.of(WorkQueueKind.HOLDING.getId(), "CAPITAL_REPAIR")
                : List.of(queueKind.getId());
        return dataManager.load(WorkQueue.class)
                .query("""
                        select e from WorkQueue e
                        where e.active = true
                          and e.warehouse = :warehouse
                          and e.queueKind in :queueKinds
                        order by e.sortOrder, e.name
                        """)
                .parameter("warehouse", warehouse)
                .parameter("queueKinds", queueKinds)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .list()
                .stream()
                .findFirst()
                .orElse(null);
    }

    public boolean hasEffectiveQueueBinding(RepairEstimateCatalogNode catalogNode) {
        return resolveQueueBindingNode(catalogNode) != null;
    }

    private RepairEstimateCatalogNode defaultFollowUpNode(String catalogCode) {
        if (catalogCode == null || catalogCode.isBlank()) {
            return null;
        }
        List<RepairCatalogService.CatalogNode> followUpNodes = repairCatalogService.followUpNodes(catalogCode);
        if (followUpNodes.isEmpty()) {
            return null;
        }
        return resolveCatalogNode(followUpNodes.get(0).code());
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private boolean generationInputsChanged(RepairEstimateTaskPlan current, RepairEstimateTaskPlan incoming) {
        return !sameId(current.getQueue(), incoming.getQueue())
                || !sameId(current.getFollowUpNode(), incoming.getFollowUpNode())
                || !Objects.equals(normalizeText(current.getGroupComment()), normalizeText(incoming.getGroupComment()))
                || !Objects.equals(effectiveActive(current.getActive()), effectiveActive(incoming.getActive()));
    }

    private boolean sameId(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (left instanceof dev.buhanzaz.wmspanel.entity.UuidEntity entityLeft
                && right instanceof dev.buhanzaz.wmspanel.entity.UuidEntity entityRight) {
            return Objects.equals(entityLeft.getId(), entityRight.getId());
        }
        return Objects.equals(left, right);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean effectiveActive(Boolean value) {
        return value == null || value;
    }

    private boolean isWorkLine(RepairEstimateLine line) {
        return line != null
                && line.getLineType() == RepairEstimateLineType.WORK
                && !repairCatalogService.isFurnitureCode(line.getCatalogCode());
    }

    private List<GroupPlanSeed> buildGroupedSeeds(List<RepairEstimateLine> lines) {
        List<GroupPlanSeed> result = new ArrayList<>();
        GroupPlanAccumulator current = null;
        for (RepairEstimateLine line : lines) {
            if (line == null) {
                continue;
            }
            if (line.getLineType() == RepairEstimateLineType.WORK) {
                if (repairCatalogService.isFurnitureCode(line.getCatalogCode())) {
                    if (current != null) {
                        result.add(current.toSeed());
                        current = null;
                    }
                    continue;
                }
                RepairEstimateCatalogNode catalogNode = resolveCatalogNode(line.getCatalogCode());
                String key = groupKey(catalogNode);
                if (current == null || !Objects.equals(current.key, key)) {
                    if (current != null) {
                        result.add(current.toSeed());
                    }
                    current = new GroupPlanAccumulator(key, catalogNode, line);
                } else {
                    current.add(line);
                }
                continue;
            }
            if (line.getLineType() == RepairEstimateLineType.MATERIAL && current != null) {
                current.add(line);
            }
        }
        if (current != null) {
            result.add(current.toSeed());
        }
        return result;
    }

    private String groupKey(RepairEstimateCatalogNode catalogNode) {
        RepairEstimateCatalogNode bindingNode = resolveQueueBindingNode(catalogNode);
        if (bindingNode == null) {
            return "UNCATEGORIZED";
        }
        String queueKey = bindingNode.getWorkQueue() != null
                && bindingNode.getWorkQueue().getCode() != null
                && !bindingNode.getWorkQueue().getCode().isBlank()
                ? bindingNode.getWorkQueue().getCode().trim().toUpperCase(Locale.ROOT)
                : bindingNode.getRouteQueueKind() == null ? "NO_QUEUE" : bindingNode.getRouteQueueKind().getId();
        return queueKey;
    }

    private RepairEstimateCatalogNode resolveQueueBindingNode(RepairEstimateCatalogNode catalogNode) {
        return resolveQueueBindingNode(catalogNode, new LinkedHashSet<>());
    }

    private RepairEstimateCatalogNode resolveQueueBindingNode(RepairEstimateCatalogNode catalogNode, Set<UUID> visitedNodeIds) {
        RepairEstimateCatalogNode current = catalogNode;
        while (current != null) {
            if (current.getId() != null && !visitedNodeIds.add(current.getId())) {
                return null;
            }
            if (hasQueueBinding(current)) {
                return current;
            }
            UUID parentId = current.getParent() == null ? null : current.getParent().getId();
            if (parentId != null) {
                current = dataManager.load(RepairEstimateCatalogNode.class)
                        .id(parentId)
                        .fetchPlan(builder -> builder.addFetchPlan("_base")
                                .add("parent", "_base")
                                .add("workQueue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base")))
                        .optional()
                        .orElse(null);
                continue;
            }
            current = loadGraphParent(current, visitedNodeIds);
        }
        return null;
    }

    private RepairEstimateCatalogNode loadGraphParent(RepairEstimateCatalogNode child, Set<UUID> visitedNodeIds) {
        if (child == null || child.getId() == null) {
            return null;
        }
        return dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e.sourceNode from RepairEstimateCatalogLink e
                        where e.active = true
                          and e.targetNode = :child
                        order by coalesce(e.sortOrder, 2147483647),
                                 e.sourceNode.sortOrder,
                                 e.sourceNode.name
                        """)
                .parameter("child", child)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("parent", "_base")
                        .add("workQueue", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base")))
                .list()
                .stream()
                .filter(node -> node.getId() == null || !visitedNodeIds.contains(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasQueueBinding(RepairEstimateCatalogNode catalogNode) {
        return catalogNode != null
                && ((catalogNode.getWorkQueue() != null
                && catalogNode.getWorkQueue().getCode() != null
                && !catalogNode.getWorkQueue().getCode().isBlank())
                || catalogNode.getRouteQueueKind() != null);
    }

    private record GroupPlanSeed(RepairEstimateLine primaryLine, List<RepairEstimateLine> lines,
                                 RepairEstimateCatalogNode catalogNode, String groupComment, String summary) {
    }

    private static final class GroupPlanAccumulator {
        private final String key;
        private final RepairEstimateCatalogNode catalogNode;
        private final RepairEstimateLine primaryLine;
        private final List<RepairEstimateLine> lines = new ArrayList<>();

        private GroupPlanAccumulator(String key, RepairEstimateCatalogNode catalogNode, RepairEstimateLine primaryLine) {
            this.key = key;
            this.catalogNode = catalogNode;
            this.primaryLine = primaryLine;
            add(primaryLine);
        }

        private void add(RepairEstimateLine line) {
            lines.add(line);
        }

        private GroupPlanSeed toSeed() {
            String groupComment = lines.stream()
                    .map(RepairEstimateLine::getLineComment)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .collect(Collectors.joining("; "));
            String summary = lines.stream()
                    .map(RepairEstimateLine::getDescription)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.joining("; "));
            return new GroupPlanSeed(primaryLine, List.copyOf(lines), catalogNode, groupComment, summary);
        }
    }
}
