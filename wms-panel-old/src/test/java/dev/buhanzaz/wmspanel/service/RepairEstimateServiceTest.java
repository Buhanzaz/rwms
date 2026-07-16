package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessoryStockBalance;
import dev.buhanzaz.wmspanel.entity.AccessorySubcategory;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLinkType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlanGenerationStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemStatus;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.entity.EntityValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class RepairEstimateServiceTest {

    @Autowired
    RepairEstimateService repairEstimateService;

    @Autowired
    RepairEstimateTaskPlanService repairEstimateTaskPlanService;

    @Autowired
    RepairEstimateTaskPlanGenerationService repairEstimateTaskPlanGenerationService;

    @Autowired
    RepairProcessService repairProcessService;

    @Autowired
    RepairCatalogService repairCatalogService;

    @Autowired
    QueueBoardService queueBoardService;

    @Autowired
    RentalItemEventService rentalItemEventService;

    @Autowired
    DataManager dataManager;

    @Autowired
    EntityStates entityStates;

    private final List<Object> entitiesToRemove = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = entitiesToRemove.size() - 1; i >= 0; i--) {
            removeIfPresent(entitiesToRemove.get(i));
        }
        entitiesToRemove.clear();
        repairCatalogService.clearCache();
    }

    @Test
    void saveEstimateKeepsStableLineIdentityAndStructuredComments() {
        RentalItem rentalItem = firstRentalItem();
        String keyOne = UUID.randomUUID().toString();
        String keyTwo = UUID.randomUUID().toString();

        RepairEstimate firstSave = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "От кого",
                "Кому",
                "Комментарий сметы",
                null,
                RepairEstimateStatus.DRAFT,
                List.of(
                        new RepairEstimateService.EstimateLineCommand(
                                keyOne,
                                dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                                "Установка раковины",
                                "Коммент 1",
                                "ед.",
                                1,
                                new BigDecimal("2500"),
                                null),
                        new RepairEstimateService.EstimateLineCommand(
                                keyTwo,
                                dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.MATERIAL,
                                "Раковина",
                                "",
                                "шт.",
                                2,
                                new BigDecimal("6000"),
                                null)),
                List.of()));

        List<RepairEstimateLine> firstLines = repairEstimateService.loadLines(firstSave.getId());
        assertThat(firstLines).hasSize(2);
        assertThat(firstLines)
                .extracting(RepairEstimateLine::getSourceLineKey)
                .containsExactlyInAnyOrder(keyOne, keyTwo);
        assertThat(firstLines)
                .extracting(RepairEstimateLine::getDescription)
                .allMatch(description -> !description.contains("[Комментарий:"));
        assertThat(firstLines)
                .filteredOn(line -> keyOne.equals(line.getSourceLineKey()))
                .first()
                .satisfies(line -> assertThat(line.getLineComment()).isEqualTo("Коммент 1"));

        RepairEstimate secondSave = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                firstSave.getId(),
                rentalItem.getId(),
                "От кого",
                "Кому",
                "Комментарий сметы",
                null,
                RepairEstimateStatus.DRAFT,
                List.of(
                        new RepairEstimateService.EstimateLineCommand(
                                keyOne,
                                dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                                "Установка раковины",
                                "Коммент 1 обновлён",
                                "ед.",
                                3,
                                new BigDecimal("2500"),
                                null),
                        new RepairEstimateService.EstimateLineCommand(
                                keyTwo,
                                dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.MATERIAL,
                                "Раковина",
                                "",
                                "шт.",
                                2,
                                new BigDecimal("6000"),
                                null)),
                List.of()));

        List<RepairEstimateLine> secondLines = repairEstimateService.loadLines(secondSave.getId());
        assertThat(secondLines).hasSize(2);
        assertThat(secondLines)
                .extracting(RepairEstimateLine::getId)
                .containsExactlyInAnyOrderElementsOf(firstLines.stream().map(RepairEstimateLine::getId).toList());
        assertThat(secondLines)
                .filteredOn(line -> keyOne.equals(line.getSourceLineKey()))
                .first()
                .satisfies(line -> {
                    assertThat(line.getLineComment()).isEqualTo("Коммент 1 обновлён");
                    assertThat(line.getDescription()).isEqualTo("Установка раковины");
                });

        entitiesToRemove.add(secondSave);
        entitiesToRemove.addAll(secondLines);
    }

    @Test
    void saveTaskPlansUnchangedInputPreservesGeneratedLinkAndStatus() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> firstPlans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        assertThat(firstPlans).hasSize(1);
        RepairEstimateTaskPlan firstPlan = firstPlans.get(0);
        assertThat(firstPlan.getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
        assertThat(firstPlan.getQueue()).isNotNull();
        assertThat(firstPlan.getQueue().getWarehouse().getId()).isEqualTo(warehouse.getId());
        assertThat(firstPlan.getGroupComment()).isEqualTo("Комментарий строки");
        assertThat(firstPlan.getFollowUpNode()).isNotNull();
        assertThat(firstPlan.getFollowUpNode().getCode()).isEqualTo(fixture.followUpNode().getCode());

        WorkQueue queue = createQueue(warehouse, "Очередь " + UUID.randomUUID());

        firstPlan.setQueue(queue);
        firstPlan.setGroupComment("Группа 1");
        List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), firstPlans);
        assertThat(savedPlans).hasSize(1);
        assertThat(savedPlans.get(0).getQueue().getId()).isEqualTo(queue.getId());
        assertThat(savedPlans.get(0).getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);

        repairEstimateTaskPlanGenerationService.generateTasks(savedPlans);
        List<RepairEstimateTaskPlan> generatedPlans = repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId());
        assertThat(generatedPlans).hasSize(1);
        RepairEstimateTaskPlan generatedPlan = generatedPlans.get(0);
        assertThat(generatedPlan.getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        assertThat(generatedPlan.getGeneratedBoardTask()).isNotNull();
        BoardTask generatedTask = generatedPlan.getGeneratedBoardTask();

        List<RepairEstimateTaskPlan> resavedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), generatedPlans);
        assertThat(resavedPlans).hasSize(1);
        assertThat(resavedPlans.get(0).getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        assertThat(resavedPlans.get(0).getGeneratedBoardTask()).isNotNull();
        assertThat(resavedPlans.get(0).getGeneratedBoardTask().getId()).isEqualTo(generatedTask.getId());

        cleanupGeneratedTask(resavedPlans.get(0));
        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void generateTasksCreatesBothMovementStagesWhenMovementRequired() {
        RentalItem rentalItem = createRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        createQueueWithCode(warehouse, "MOVE_TO_REPAIR", "Перемещение на ремонт", 10);
        createQueueWithCode(warehouse, "MOVE_TO_STORAGE", "Перемещение с ремонта", 90);
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId(), true);
        repairEstimateTaskPlanGenerationService.generateTasks(plans);

        RepairProcess process = dataManager.load(RepairProcess.class)
                .query("select e from RepairProcess e where e.estimate = :estimate")
                .parameter("estimate", savedEstimate)
                .one();
        List<BoardTask> tasks = dataManager.load(BoardTask.class)
                .query("select e from BoardTask e where e.repairProcess = :process")
                .parameter("process", process)
                .fetchPlan("_base")
                .list();

        assertThat(tasks)
                .extracting(BoardTask::getTaskKind)
                .contains(RepairProcessTaskKind.MOVE_TO_REPAIR, RepairProcessTaskKind.MOVE_FROM_REPAIR);

        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.add(process);
        entitiesToRemove.addAll(tasks);
        entitiesToRemove.addAll(dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.task.repairProcess = :process")
                .parameter("process", process)
                .list());
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void completedMovementTasksMoveProcessToAfterRepairEvenWhenLegacyFlagsAreStale() {
        RentalItem rentalItem = createRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        createQueueWithCode(warehouse, "MOVE_TO_REPAIR", "Перемещение на ремонт", 10);
        createQueueWithCode(warehouse, "MOVE_TO_STORAGE", "Перемещение с ремонта", 90);
        CatalogFixture fixture = createCatalogFixture(warehouse);

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        UUID.randomUUID().toString(),
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId(), true);
        repairEstimateTaskPlanGenerationService.generateTasks(plans);

        RepairProcess process = dataManager.load(RepairProcess.class)
                .query("select e from RepairProcess e where e.estimate = :estimate")
                .parameter("estimate", savedEstimate)
                .one();
        List<BoardTask> tasks = dataManager.load(BoardTask.class)
                .query("select e from BoardTask e where e.repairProcess = :process")
                .parameter("process", process)
                .fetchPlan("_base")
                .list();
        assertThat(tasks).isNotEmpty();
        tasks.forEach(task -> {
            task.setStatus(BoardTaskStatus.DONE);
            dataManager.save(task);
        });

        process.setMoveToRepairDone(false);
        process.setMoveFromRepairDone(false);
        process.setMoveFromRepairCancelled(false);
        dataManager.save(process);

        assertThat(repairProcessService.syncProcessStatus(process)).isTrue();
        RepairProcess reloadedProcess = dataManager.load(RepairProcess.class).id(process.getId()).one();
        assertThat(reloadedProcess.getStatus()).isEqualTo(RepairProcessStatus.AFTER_REPAIR);
        assertThat(repairProcessService.loadAfterRepairProcesses(List.of(warehouse)))
                .extracting(RepairProcess::getId)
                .contains(process.getId());

        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.add(process);
        entitiesToRemove.addAll(dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.task.repairProcess = :process")
                .parameter("process", process)
                .list());
        entitiesToRemove.addAll(tasks);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void saveTaskPlansAllowsMultiplePlansForSameEstimateLine() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        assertThat(plans).hasSize(1);
        RepairEstimateTaskPlan firstPlan = plans.get(0);
        firstPlan.setQueue(createQueue(warehouse, "Очередь 1 " + UUID.randomUUID()));

        RepairEstimateTaskPlan secondPlan = dataManager.create(RepairEstimateTaskPlan.class);
        secondPlan.setEstimate(savedEstimate);
        secondPlan.setEstimateLine(firstPlan.getEstimateLine());
        secondPlan.setQueue(createQueue(warehouse, "Очередь 2 " + UUID.randomUUID()));
        secondPlan.setGroupComment("Дополнительный план");
        secondPlan.setFollowUpNode(firstPlan.getFollowUpNode());
        secondPlan.setActive(true);
        plans.add(secondPlan);

        List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), plans);
        assertThat(savedPlans).hasSize(2);
        assertThat(savedPlans)
                .extracting(plan -> plan.getEstimateLine().getId())
                .containsOnly(firstPlan.getEstimateLine().getId());

        List<RepairEstimateLine> reloadedLines = repairEstimateService.loadLines(savedEstimate.getId());
        assertThat(reloadedLines).hasSize(1);
        assertThat(reloadedLines.get(0).getTaskPlans()).hasSize(2);

        repairEstimateTaskPlanGenerationService.generateTasks(savedPlans);
        List<RepairEstimateTaskPlan> generatedPlans = repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId());
        assertThat(generatedPlans).hasSize(2);
        assertThat(generatedPlans)
                .extracting(RepairEstimateTaskPlan::getGenerationStatus)
                .containsOnly(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        assertThat(generatedPlans)
                .allSatisfy(plan -> assertThat(plan.getGeneratedBoardTask()).isNotNull());

        for (RepairEstimateTaskPlan generatedPlan : generatedPlans) {
            cleanupGeneratedTask(generatedPlan);
        }
        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void createTaskFromPlanCreatesSingleBoardTask() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        plans.get(0).setQueue(createQueue(warehouse, "Очередь генерации " + UUID.randomUUID()));
        plans.get(0).setGroupComment("Комментарий группы");
        List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), plans);
        repairEstimateTaskPlanGenerationService.generateTasks(savedPlans);

        List<RepairEstimateTaskPlan> generatedPlans = repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId());
        assertThat(generatedPlans).hasSize(1);
        RepairEstimateTaskPlan generatedPlan = generatedPlans.get(0);
        assertThat(generatedPlan.getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        assertThat(generatedPlan.getGeneratedBoardTask()).isNotNull();
        assertThat(generatedPlan.getGeneratedBoardTask().getDescription())
                .contains("Работа")
                .contains("Комментарий строки: Комментарий строки")
                .contains("Комментарий группы: Комментарий группы")
                .doesNotContain("[Комментарий:");

        List<QueueEntry> entries = generatedQueueEntries(generatedPlan.getGeneratedBoardTask());
        assertThat(entries).hasSize(1);

        cleanupGeneratedTask(generatedPlan);
        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void generatedRepairTaskUsesSummedCatalogWorkDurationAsQueueEntryPlan() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        fixture.workNode().setDurationMinutes(60);
        fixture.followUpNode().setDurationMinutes(60);
        dataManager.save(fixture.workNode(), fixture.followUpNode());

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(
                        new RepairEstimateService.EstimateLineCommand(
                                UUID.randomUUID().toString(),
                                dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                                fixture.workNode().getName(),
                                null,
                                "ед.",
                                1,
                                new BigDecimal("1200"),
                                fixture.workNode().getCode()),
                        new RepairEstimateService.EstimateLineCommand(
                                UUID.randomUUID().toString(),
                                dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                                fixture.followUpNode().getName(),
                                null,
                                "ед.",
                                1,
                                new BigDecimal("800"),
                                fixture.followUpNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        assertThat(plans).hasSize(1);
        List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), plans);
        repairEstimateTaskPlanGenerationService.generateTasks(savedPlans);

        RepairEstimateTaskPlan generatedPlan = repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()).get(0);
        assertThat(generatedPlan.getGeneratedBoardTask()).isNotNull();
        assertThat(generatedPlan.getGeneratedBoardTask().getPlannedDurationMinutes()).isEqualTo(120);

        List<QueueEntry> entries = generatedQueueEntries(generatedPlan.getGeneratedBoardTask());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getPlannedDurationMinutes()).isEqualTo(120);
        assertThat(entries.get(0).getActiveWorkSeconds()).isZero();
        assertThat(entries.get(0).getActiveStartedAt()).isNull();

        cleanupGeneratedTask(generatedPlan);
        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void saveEstimateAssignsFurnitureToItemAndReducesAvailableAccessoryStock() {
        RentalItem rentalItem = createRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        RepairEstimateCatalogNode furnitureCategory = createCatalogCategory("FURNITURE", "Мебель");
        RepairEstimateCatalogNode bunkBedMaterial = createCatalogMaterial(
                furnitureCategory,
                "METAL_BUNK_BED",
                "Кровать двухъярусная",
                "шт.",
                new BigDecimal("9000"));
        AccessoryItem accessoryItem = createAccessoryItemLinkedToMaterial(warehouse, bunkBedMaterial, "Кровать двухъярусная");
        AccessoryStockBalance balance = createAccessoryBalance(warehouse, accessoryItem, 10, 10);

        RepairEstimate estimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Мебель для бытовки",
                null,
                RepairEstimateStatus.DRAFT,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        UUID.randomUUID().toString(),
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.MATERIAL,
                        bunkBedMaterial.getName(),
                        "",
                        "шт.",
                        2,
                        new BigDecimal("9000"),
                        bunkBedMaterial.getCode())),
                List.of()));

        List<RentalItemAccessory> assignments = dataManager.load(RentalItemAccessory.class)
                .query("""
                        select e from RentalItemAccessory e
                        where e.rentalItem.id = :rentalItemId
                        """)
                .parameter("rentalItemId", rentalItem.getId())
                .list();
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).getAccessoryItem().getId()).isEqualTo(accessoryItem.getId());
        assertThat(assignments.get(0).getQuantity()).isEqualTo(2);

        AccessoryStockBalance reloadedBalance = dataManager.load(AccessoryStockBalance.class)
                .id(balance.getId())
                .one();
        assertThat(reloadedBalance.getQuantityTotal()).isEqualTo(10);
        assertThat(reloadedBalance.getQuantityAvailable()).isEqualTo(8);

        entitiesToRemove.add(estimate);
        entitiesToRemove.addAll(repairEstimateService.loadLines(estimate.getId()));
        assignments.forEach(entitiesToRemove::add);
        entitiesToRemove.add(reloadedBalance);
        entitiesToRemove.add(accessoryItem);
        entitiesToRemove.add(accessoryItem.getSubcategory());
        entitiesToRemove.add(accessoryItem.getCategory());
        entitiesToRemove.add(bunkBedMaterial);
        entitiesToRemove.add(furnitureCategory);
    }

    @Test
    void generateTasksSetsRentalItemStatusAcrossRepairLifecycle() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        plans.get(0).setQueue(createQueue(warehouse, "Очередь статусов " + UUID.randomUUID()));
        List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), plans);

        repairEstimateTaskPlanGenerationService.generateTasks(savedEstimate.getId(), savedPlans);
        assertThat(reloadRentalItem(rentalItem.getId()).getStatus()).isEqualTo(RentalItemStatus.WAITING_REPAIR.getId());

        RepairEstimateTaskPlan generatedPlan = repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()).get(0);
        WorkerGroup workerGroup = firstWorkerGroup();
        queueBoardService.takeNextTask(generatedPlan.getQueue().getId(), workerGroup, null);
        assertThat(reloadRentalItem(rentalItem.getId()).getStatus()).isEqualTo(RentalItemStatus.IN_REPAIR.getId());

        List<RentalItemEvent> timelineAfterStart = rentalItemEventService.loadTimeline(rentalItem.getId());
        RentalItemEvent processRootEvent = timelineAfterStart.stream()
                .filter(event -> event.getEventType() == RentalItemEventType.ESTIMATE_COMPLETED)
                .findFirst()
                .orElseThrow();
        RentalItemEvent startedEvent = timelineAfterStart.stream()
                .filter(event -> event.getEventType() == RentalItemEventType.REPAIR_TASK_STARTED)
                .findFirst()
                .orElseThrow();
        assertThat(startedEvent.getParentEvent()).isNotNull();
        assertThat(startedEvent.getParentEvent().getId()).isEqualTo(processRootEvent.getId());

        queueBoardService.completeCurrentTask(generatedPlan.getQueue().getId());
        assertThat(reloadRentalItem(rentalItem.getId()).getStatus()).isEqualTo(RentalItemStatus.WAITING_REPAIR_CHECK.getId());

        List<RentalItemEvent> timelineAfterComplete = rentalItemEventService.loadTimeline(rentalItem.getId());
        RentalItemEvent completedEvent = timelineAfterComplete.stream()
                .filter(event -> event.getEventType() == RentalItemEventType.REPAIR_TASK_COMPLETED)
                .findFirst()
                .orElseThrow();
        assertThat(completedEvent.getParentEvent()).isNotNull();
        assertThat(completedEvent.getParentEvent().getId()).isEqualTo(processRootEvent.getId());

        RentalItemEvent readyEvent = timelineAfterComplete.stream()
                .filter(event -> event.getEventType() == RentalItemEventType.REPAIR_READY_FOR_CHECK)
                .findFirst()
                .orElseThrow();
        assertThat(readyEvent.getParentEvent()).isNotNull();
        assertThat(readyEvent.getParentEvent().getId()).isEqualTo(processRootEvent.getId());

        cleanupGeneratedTask(generatedPlan);
        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void emptyEstimateCompletesAsReady() {
        RentalItem rentalItem = firstRentalItem();
        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Пустая смета",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(),
                List.of()));

        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        repairEstimateTaskPlanGenerationService.generateTasks(savedEstimate.getId(), plans);

        assertThat(reloadRentalItem(rentalItem.getId()).getStatus()).isEqualTo(RentalItemStatus.READY.getId());

        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void emptyEstimateWithMovementStaysWaitingForRepair() {
        RentalItem rentalItem = firstRentalItem();
        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Пустая смета",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(),
                List.of()));

        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId(), true);
        repairEstimateTaskPlanGenerationService.generateTasks(savedEstimate.getId(), plans);

        assertThat(reloadRentalItem(rentalItem.getId()).getStatus()).isEqualTo(RentalItemStatus.WAITING_REPAIR.getId());

        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void repeatedCompleteDoesNotDuplicateTask() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());
        plans.get(0).setQueue(createQueue(warehouse, "Очередь повтора " + UUID.randomUUID()));
        List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(savedEstimate.getId(), plans);
        repairEstimateTaskPlanGenerationService.generateTasks(savedPlans);
        repairEstimateTaskPlanGenerationService.generateTasks(savedPlans);

        List<RepairEstimateTaskPlan> generatedPlans = repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId());
        assertThat(generatedPlans).hasSize(1);
        RepairEstimateTaskPlan generatedPlan = generatedPlans.get(0);
        assertThat(generatedPlan.getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        assertThat(generatedPlan.getGeneratedBoardTask()).isNotNull();
        assertThat(generatedQueueEntries(generatedPlan.getGeneratedBoardTask())).hasSize(1);

        cleanupGeneratedTask(generatedPlan);
        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void prepareTaskPlansUsesQueueInheritedFromParentCategory() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        InheritedQueueCatalogFixture fixture = createInheritedQueueCatalogFixture(warehouse);

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Проверка очереди",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        UUID.randomUUID().toString(),
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getQueue()).isNotNull();
        assertThat(plans.get(0).getQueue().getId()).isEqualTo(fixture.queue().getId());
        assertThat(repairEstimateTaskPlanService.resolveDefaultQueue(warehouse, fixture.workNode())).isNotNull();
        assertThat(repairEstimateTaskPlanService.resolveDefaultQueue(warehouse, fixture.workNode()).getId())
                .isEqualTo(fixture.queue().getId());

        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void prepareTaskPlansUsesQueueInheritedFromCanvasPathCategory() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CanvasQueueCatalogFixture fixture = createCanvasQueueCatalogFixture(warehouse);

        RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Проверка очереди графа",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        UUID.randomUUID().toString(),
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId());

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getQueue()).isNotNull();
        assertThat(plans.get(0).getQueue().getId()).isEqualTo(fixture.queue().getId());
        assertThat(repairEstimateTaskPlanService.resolveDefaultQueue(warehouse, fixture.workNode())).isNotNull();
        assertThat(repairEstimateTaskPlanService.resolveDefaultQueue(warehouse, fixture.workNode()).getId())
                .isEqualTo(fixture.queue().getId());

        entitiesToRemove.add(savedEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(savedEstimate.getId()));
    }

    @Test
    void invalidQueueOrWarehouseMismatchDoesNotCreateTaskAndLeavesExplicitState() {
        RentalItem rentalItem = firstRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        CatalogFixture fixture = createCatalogFixture(warehouse);
        String sourceLineKey = UUID.randomUUID().toString();

        RepairEstimate pendingEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        sourceLineKey,
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> pendingPlans = repairEstimateTaskPlanService.prepareTaskPlans(pendingEstimate.getId());
        pendingPlans.get(0).setQueue(null);
        List<RepairEstimateTaskPlan> savedPendingPlans = repairEstimateTaskPlanService.saveTaskPlans(pendingEstimate.getId(), pendingPlans);
        long boardTaskCountBefore = boardTaskCountForRentalItem(rentalItem.getId());
        repairEstimateTaskPlanGenerationService.generateTasks(savedPendingPlans);

        List<RepairEstimateTaskPlan> reloadedPendingPlans = repairEstimateTaskPlanService.loadTaskPlans(pendingEstimate.getId());
        assertThat(reloadedPendingPlans).hasSize(1);
        assertThat(reloadedPendingPlans.get(0).getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
        assertThat(reloadedPendingPlans.get(0).getGeneratedBoardTask()).isNull();
        assertThat(boardTaskCountForRentalItem(rentalItem.getId())).isEqualTo(boardTaskCountBefore);

        Warehouse mismatchWarehouse = dataManager.create(Warehouse.class);
        mismatchWarehouse.setName("Склад mismatch " + UUID.randomUUID());
        mismatchWarehouse.setCode("MISMATCH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        mismatchWarehouse.setCity("Город");
        mismatchWarehouse.setTimeZone("Europe/Moscow");
        mismatchWarehouse.setActive(true);
        Warehouse savedMismatchWarehouse = dataManager.save(mismatchWarehouse);
        entitiesToRemove.add(savedMismatchWarehouse);

        WorkQueue mismatchQueue = dataManager.create(WorkQueue.class);
        mismatchQueue.setWarehouse(savedMismatchWarehouse);
        mismatchQueue.setCode("MISMATCH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        mismatchQueue.setName("Очередь mismatch " + UUID.randomUUID());
        mismatchQueue.setActive(true);
        WorkQueue savedMismatchQueue = dataManager.save(mismatchQueue);
        entitiesToRemove.add(savedMismatchQueue);

        RepairEstimate mismatchEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                null,
                rentalItem.getId(),
                "Источник",
                "Получатель",
                "Общий комментарий",
                null,
                RepairEstimateStatus.COMPLETED,
                List.of(new RepairEstimateService.EstimateLineCommand(
                        UUID.randomUUID().toString(),
                        dev.buhanzaz.wmspanel.entity.RepairEstimateLineType.WORK,
                        fixture.workNode().getName(),
                        "Комментарий строки",
                        "ед.",
                        1,
                        new BigDecimal("1200"),
                        fixture.workNode().getCode())),
                List.of()));

        repairCatalogService.clearCache();
        List<RepairEstimateTaskPlan> mismatchPlans = repairEstimateTaskPlanService.prepareTaskPlans(mismatchEstimate.getId());
        RepairEstimateTaskPlan mismatchPlan = mismatchPlans.get(0);
        mismatchPlan.setQueue(savedMismatchQueue);
        RepairEstimateTaskPlan savedMismatchPlan = dataManager.save(mismatchPlan);

        repairEstimateTaskPlanGenerationService.generateTasks(List.of(savedMismatchPlan));

        List<RepairEstimateTaskPlan> reloadedMismatchPlans = repairEstimateTaskPlanService.loadTaskPlans(mismatchEstimate.getId());
        assertThat(reloadedMismatchPlans).hasSize(1);
        assertThat(reloadedMismatchPlans.get(0).getGenerationStatus()).isEqualTo(RepairEstimateTaskPlanGenerationStatus.FAILED);
        assertThat(reloadedMismatchPlans.get(0).getGeneratedBoardTask()).isNull();
        assertThat(boardTaskCountForRentalItem(rentalItem.getId())).isEqualTo(boardTaskCountBefore);

        entitiesToRemove.add(pendingEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(pendingEstimate.getId()));
        entitiesToRemove.add(mismatchEstimate);
        entitiesToRemove.addAll(repairEstimateTaskPlanService.loadTaskPlans(mismatchEstimate.getId()));
    }

    private RentalItem firstRentalItem() {
        return createRentalItem();
    }

    private Warehouse firstWarehouse() {
        return dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .fetchPlan("_base")
                .list()
                .stream()
                .findFirst()
                .orElseThrow();
    }

    private WorkerGroup firstWorkerGroup() {
        return createWorkerGroup();
    }

    private RentalItem createRentalItem() {
        Warehouse warehouse = createWarehouse();
        RentalCategory category = createCategory();
        RentalItem rentalItem = dataManager.create(RentalItem.class);
        rentalItem.setWarehouse(warehouse);
        rentalItem.setCategory(category);
        rentalItem.setNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rentalItem.setStatus("READY");
        RentalItem saved = dataManager.save(rentalItem);
        entitiesToRemove.add(saved);
        return saved;
    }

    private Warehouse createWarehouse() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Test warehouse " + UUID.randomUUID());
        warehouse.setCode("WH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        warehouse.setCity("Test City");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        Warehouse saved = dataManager.save(warehouse);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalCategory createCategory() {
        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Test category " + UUID.randomUUID());
        category.setCode("CAT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        category.setActive(true);
        RentalCategory saved = dataManager.save(category);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairEstimateCatalogNode createCatalogCategory(String code, String name) {
        RepairEstimateCatalogNode category = dataManager.create(RepairEstimateCatalogNode.class);
        category.setCode(code);
        category.setName(name);
        category.setNodeType(RepairEstimateCatalogNodeType.CATEGORY);
        category.setActive(true);
        category.setShowInMainMenu(false);
        category.setDefaultQuantity(1);
        category.setAdditionalOption(false);
        category.setSortOrder(10);
        RepairEstimateCatalogNode saved = dataManager.save(category);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairEstimateCatalogNode createCatalogMaterial(RepairEstimateCatalogNode parent,
                                                            String code,
                                                            String name,
                                                            String unit,
                                                            BigDecimal price) {
        RepairEstimateCatalogNode material = dataManager.create(RepairEstimateCatalogNode.class);
        material.setCode(code);
        material.setName(name);
        material.setNodeType(RepairEstimateCatalogNodeType.MATERIAL);
        material.setParent(parent);
        material.setActive(true);
        material.setShowInMainMenu(false);
        material.setDefaultQuantity(1);
        material.setAdditionalOption(false);
        material.setSortOrder(20);
        material.setUnit(unit);
        material.setUnitPrice(price);
        RepairEstimateCatalogNode saved = dataManager.save(material);
        entitiesToRemove.add(saved);
        return saved;
    }

    private AccessoryItem createAccessoryItemLinkedToMaterial(Warehouse warehouse,
                                                              RepairEstimateCatalogNode furnitureMaterial,
                                                              String name) {
        AccessoryCategory category = dataManager.create(AccessoryCategory.class);
        category.setName("Мебель " + UUID.randomUUID());
        category.setCode("FURN_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        category.setActive(true);
        AccessoryCategory savedCategory = dataManager.save(category);
        entitiesToRemove.add(savedCategory);

        AccessorySubcategory subcategory = dataManager.create(AccessorySubcategory.class);
        subcategory.setCategory(savedCategory);
        subcategory.setName("Кровати " + UUID.randomUUID());
        subcategory.setCode("BEDS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        subcategory.setActive(true);
        AccessorySubcategory savedSubcategory = dataManager.save(subcategory);
        entitiesToRemove.add(savedSubcategory);

        AccessoryItem accessoryItem = dataManager.create(AccessoryItem.class);
        accessoryItem.setCategory(savedCategory);
        accessoryItem.setSubcategory(savedSubcategory);
        accessoryItem.setName(name);
        accessoryItem.setCode("ITEM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        accessoryItem.setActive(true);
        accessoryItem.setFurnitureMaterial(furnitureMaterial);
        AccessoryItem savedItem = dataManager.save(accessoryItem);
        entitiesToRemove.add(savedItem);
        return savedItem;
    }

    private AccessoryStockBalance createAccessoryBalance(Warehouse warehouse,
                                                         AccessoryItem accessoryItem,
                                                         int total,
                                                         int available) {
        AccessoryStockBalance balance = dataManager.create(AccessoryStockBalance.class);
        balance.setWarehouse(warehouse);
        balance.setAccessoryItem(accessoryItem);
        balance.setQuantityTotal(total);
        balance.setQuantityAvailable(available);
        balance.setQuantityReserved(0);
        balance.setQuantityInRent(0);
        balance.setQuantityBroken(0);
        balance.setQuantityWrittenOff(0);
        AccessoryStockBalance saved = dataManager.save(balance);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerGroup createWorkerGroup() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = dataManager.create(WorkerClass.class);
        workerClass.setCode("TEST_CLASS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        workerClass.setName("Test class " + UUID.randomUUID());
        workerClass.setActive(true);
        WorkerClass savedWorkerClass = dataManager.save(workerClass);
        entitiesToRemove.add(savedWorkerClass);

        WorkerGroup group = dataManager.create(WorkerGroup.class);
        group.setWarehouse(warehouse);
        group.setWorkerClass(savedWorkerClass);
        group.setName("Test group " + UUID.randomUUID());
        group.setActive(true);
        WorkerGroup savedGroup = dataManager.save(group);
        entitiesToRemove.add(savedGroup);
        return savedGroup;
    }

    private CatalogFixture createCatalogFixture(Warehouse warehouse) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkQueue queue = createQueue(warehouse, "Очередь каталога " + suffix);

        RepairEstimateCatalogNode category = dataManager.create(RepairEstimateCatalogNode.class);
        category.setCode("CAT_" + suffix);
        category.setName("Категория " + suffix);
        category.setNodeType(RepairEstimateCatalogNodeType.CATEGORY);
        category.setActive(true);
        category.setShowInMainMenu(false);
        category.setDefaultQuantity(1);
        category.setAdditionalOption(false);
        category.setSortOrder(10);
        RepairEstimateCatalogNode savedCategory = dataManager.save(category);
        entitiesToRemove.add(savedCategory);

        RepairEstimateCatalogNode workNode = dataManager.create(RepairEstimateCatalogNode.class);
        workNode.setCode("WORK_" + suffix);
        workNode.setName("Работа " + suffix);
        workNode.setNodeType(RepairEstimateCatalogNodeType.WORK);
        workNode.setParent(savedCategory);
        workNode.setWorkQueue(queue);
        workNode.setActive(true);
        workNode.setShowInMainMenu(false);
        workNode.setDefaultQuantity(1);
        workNode.setAdditionalOption(false);
        workNode.setSortOrder(20);
        workNode.setUnit("ед.");
        workNode.setUnitPrice(new BigDecimal("1200"));
        RepairEstimateCatalogNode savedWorkNode = dataManager.save(workNode);
        entitiesToRemove.add(savedWorkNode);

        RepairEstimateCatalogNode followUpNode = dataManager.create(RepairEstimateCatalogNode.class);
        followUpNode.setCode("FOLLOW_" + suffix);
        followUpNode.setName("Следующий узел " + suffix);
        followUpNode.setNodeType(RepairEstimateCatalogNodeType.WORK);
        followUpNode.setParent(savedCategory);
        followUpNode.setActive(true);
        followUpNode.setShowInMainMenu(false);
        followUpNode.setDefaultQuantity(1);
        followUpNode.setAdditionalOption(false);
        followUpNode.setWorkQueue(queue);
        followUpNode.setSortOrder(30);
        followUpNode.setUnit("ед.");
        followUpNode.setUnitPrice(new BigDecimal("800"));
        RepairEstimateCatalogNode savedFollowUpNode = dataManager.save(followUpNode);
        entitiesToRemove.add(savedFollowUpNode);

        RepairEstimateCatalogLink link = dataManager.create(RepairEstimateCatalogLink.class);
        link.setSourceNode(savedWorkNode);
        link.setTargetNode(savedFollowUpNode);
        link.setLinkType(RepairEstimateCatalogLinkType.FOLLOW_UP);
        link.setActive(true);
        link.setSortOrder(1);
        RepairEstimateCatalogLink savedLink = dataManager.save(link);
        entitiesToRemove.add(savedLink);

        return new CatalogFixture(savedWorkNode, savedFollowUpNode);
    }

    private WorkQueue createQueue(Warehouse warehouse, String name) {
        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(warehouse);
        queue.setCode("QUEUE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        queue.setName(name);
        queue.setActive(true);
        WorkQueue savedQueue = dataManager.save(queue);
        entitiesToRemove.add(savedQueue);
        return savedQueue;
    }

    private WorkQueue createQueueWithCode(Warehouse warehouse, String code, String name, int sortOrder) {
        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(warehouse);
        queue.setCode(code);
        queue.setName(name);
        queue.setQueueKind(WorkQueueKind.REPAIR);
        queue.setSortOrder(sortOrder);
        queue.setActive(true);
        queue.setCollapsed(false);
        queue.setHidden(false);
        queue.setSinkQueue(false);
        queue.setNotifyWhenThresholdReached(false);
        WorkQueue savedQueue = dataManager.save(queue);
        entitiesToRemove.add(savedQueue);
        return savedQueue;
    }

    private InheritedQueueCatalogFixture createInheritedQueueCatalogFixture(Warehouse warehouse) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkQueue queue = createQueue(warehouse, "Очередь наследования " + suffix);
        queue.setQueueKind(WorkQueueKind.REPAIR);
        WorkQueue savedQueue = dataManager.save(queue);

        RepairEstimateCatalogNode category = dataManager.create(RepairEstimateCatalogNode.class);
        category.setCode("CAT_INHERIT_" + suffix);
        category.setName("Категория наследования " + suffix);
        category.setNodeType(RepairEstimateCatalogNodeType.CATEGORY);
        category.setWorkQueue(savedQueue);
        category.setRouteQueueKind(WorkQueueKind.REPAIR);
        category.setActive(true);
        category.setShowInMainMenu(false);
        category.setDefaultQuantity(1);
        category.setAdditionalOption(false);
        category.setSortOrder(10);
        RepairEstimateCatalogNode savedCategory = dataManager.save(category);
        entitiesToRemove.add(savedCategory);

        RepairEstimateCatalogNode subcategory = dataManager.create(RepairEstimateCatalogNode.class);
        subcategory.setCode("SUB_INHERIT_" + suffix);
        subcategory.setName("Подкатегория наследования " + suffix);
        subcategory.setNodeType(RepairEstimateCatalogNodeType.SUBCATEGORY);
        subcategory.setParent(savedCategory);
        subcategory.setActive(true);
        subcategory.setShowInMainMenu(false);
        subcategory.setDefaultQuantity(1);
        subcategory.setAdditionalOption(false);
        subcategory.setSortOrder(20);
        RepairEstimateCatalogNode savedSubcategory = dataManager.save(subcategory);
        entitiesToRemove.add(savedSubcategory);

        RepairEstimateCatalogNode workNode = dataManager.create(RepairEstimateCatalogNode.class);
        workNode.setCode("WORK_INHERIT_" + suffix);
        workNode.setName("Работа наследования " + suffix);
        workNode.setNodeType(RepairEstimateCatalogNodeType.WORK);
        workNode.setParent(savedSubcategory);
        workNode.setActive(true);
        workNode.setShowInMainMenu(false);
        workNode.setDefaultQuantity(1);
        workNode.setAdditionalOption(false);
        workNode.setSortOrder(30);
        workNode.setUnit("ед.");
        workNode.setUnitPrice(new BigDecimal("1200"));
        RepairEstimateCatalogNode savedWorkNode = dataManager.save(workNode);
        entitiesToRemove.add(savedWorkNode);

        return new InheritedQueueCatalogFixture(savedWorkNode, savedQueue);
    }

    private CanvasQueueCatalogFixture createCanvasQueueCatalogFixture(Warehouse warehouse) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkQueue queue = createQueue(warehouse, "Очередь графа " + suffix);
        queue.setQueueKind(WorkQueueKind.REPAIR);
        WorkQueue savedQueue = dataManager.save(queue);

        RepairEstimateCatalogNode category = dataManager.create(RepairEstimateCatalogNode.class);
        category.setCode("CAT_GRAPH_" + suffix);
        category.setName("Категория графа " + suffix);
        category.setNodeType(RepairEstimateCatalogNodeType.CATEGORY);
        category.setWorkQueue(savedQueue);
        category.setActive(true);
        category.setShowInMainMenu(false);
        category.setDefaultQuantity(1);
        category.setAdditionalOption(false);
        category.setSortOrder(10);
        RepairEstimateCatalogNode savedCategory = dataManager.save(category);
        entitiesToRemove.add(savedCategory);

        RepairEstimateCatalogNode subcategory = dataManager.create(RepairEstimateCatalogNode.class);
        subcategory.setCode("SUB_GRAPH_" + suffix);
        subcategory.setName("Подкатегория графа " + suffix);
        subcategory.setNodeType(RepairEstimateCatalogNodeType.SUBCATEGORY);
        subcategory.setActive(true);
        subcategory.setShowInMainMenu(false);
        subcategory.setDefaultQuantity(1);
        subcategory.setAdditionalOption(false);
        subcategory.setSortOrder(20);
        RepairEstimateCatalogNode savedSubcategory = dataManager.save(subcategory);
        entitiesToRemove.add(savedSubcategory);

        RepairEstimateCatalogNode workNode = dataManager.create(RepairEstimateCatalogNode.class);
        workNode.setCode("WORK_GRAPH_" + suffix);
        workNode.setName("Работа графа " + suffix);
        workNode.setNodeType(RepairEstimateCatalogNodeType.WORK);
        workNode.setActive(true);
        workNode.setShowInMainMenu(false);
        workNode.setDefaultQuantity(1);
        workNode.setAdditionalOption(false);
        workNode.setSortOrder(30);
        workNode.setUnit("ед.");
        workNode.setUnitPrice(new BigDecimal("1200"));
        RepairEstimateCatalogNode savedWorkNode = dataManager.save(workNode);
        entitiesToRemove.add(savedWorkNode);

        RepairEstimateCatalogLink categoryToSubcategory = dataManager.create(RepairEstimateCatalogLink.class);
        categoryToSubcategory.setSourceNode(savedCategory);
        categoryToSubcategory.setTargetNode(savedSubcategory);
        categoryToSubcategory.setLinkType(RepairEstimateCatalogLinkType.FOLLOW_UP);
        categoryToSubcategory.setActive(true);
        categoryToSubcategory.setSortOrder(1);
        RepairEstimateCatalogLink savedCategoryToSubcategory = dataManager.save(categoryToSubcategory);
        entitiesToRemove.add(savedCategoryToSubcategory);

        RepairEstimateCatalogLink subcategoryToWork = dataManager.create(RepairEstimateCatalogLink.class);
        subcategoryToWork.setSourceNode(savedSubcategory);
        subcategoryToWork.setTargetNode(savedWorkNode);
        subcategoryToWork.setLinkType(RepairEstimateCatalogLinkType.FOLLOW_UP);
        subcategoryToWork.setActive(true);
        subcategoryToWork.setSortOrder(2);
        RepairEstimateCatalogLink savedSubcategoryToWork = dataManager.save(subcategoryToWork);
        entitiesToRemove.add(savedSubcategoryToWork);

        return new CanvasQueueCatalogFixture(savedWorkNode, savedQueue);
    }

    private List<QueueEntry> generatedQueueEntries(BoardTask task) {
        return dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.task = :task order by e.routeIndex")
                .parameter("task", task)
                .fetchPlan("_base")
                .list();
    }

    private RentalItem reloadRentalItem(UUID rentalItemId) {
        return dataManager.load(RentalItem.class)
                .id(rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .one();
    }

    private long boardTaskCountForRentalItem(UUID rentalItemId) {
        return dataManager.loadValue("""
                        select count(e) from BoardTask e
                        where e.rentalItem.id = :rentalItemId
                        """, Long.class)
                .parameter("rentalItemId", rentalItemId)
                .one();
    }

    private void cleanupGeneratedTask(RepairEstimateTaskPlan plan) {
        RepairEstimateTaskPlan reloadedPlan = dataManager.load(RepairEstimateTaskPlan.class)
                .id(plan.getId())
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("generatedBoardTask", "_base"))
                .one();
        BoardTask generatedTask = reloadedPlan.getGeneratedBoardTask();
        if (generatedTask == null || generatedTask.getId() == null) {
            return;
        }
        List<QueueEntry> entries = generatedQueueEntries(generatedTask);
        if (!entries.isEmpty()) {
            dataManager.remove(entries.toArray());
        }
        reloadedPlan.setGeneratedBoardTask(null);
        reloadedPlan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        dataManager.save(reloadedPlan);
        dataManager.remove(generatedTask);
    }

    private record CatalogFixture(RepairEstimateCatalogNode workNode, RepairEstimateCatalogNode followUpNode) {
    }

    private record InheritedQueueCatalogFixture(RepairEstimateCatalogNode workNode, WorkQueue queue) {
    }

    private record CanvasQueueCatalogFixture(RepairEstimateCatalogNode workNode, WorkQueue queue) {
    }

    private void removeIfPresent(Object entity) {
        Object entityId = entity == null ? null : EntityValues.getId(entity);
        if (entity == null || entityStates.isDetached(entity) && entityId == null) {
            return;
        }
        try {
            Object reloaded = dataManager.load(entity.getClass())
                    .id(entityId)
                    .optional()
                    .orElse(null);
            if (reloaded != null) {
                dataManager.remove(reloaded);
            }
        } catch (RuntimeException ignored) {
            // Test cleanup should be best-effort: some entities are already removed by the scenario itself.
        }
    }
}
