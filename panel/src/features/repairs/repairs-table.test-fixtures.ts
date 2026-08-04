import type {
  RepairTaskDto,
  RepairTaskOrigin,
  RepairTaskSubtaskStatus,
} from "@/features/repair-tasks/model/repair-task"

export const REPAIRS_TABLE_TEST_DATES = [
  "2026-07-24",
  "2026-07-25",
  "2026-07-26",
] as const

const priorities = [1, 2, 3, 4, 5] as const
const subtaskStatuses: RepairTaskSubtaskStatus[] = [
  "WAITING",
  "IN_PROGRESS",
  "PAUSED",
]
const taskTitles = [
  "Ремонт кровли",
  "Замена линолеума",
  "Проверка электрики",
] as const

function createRepair(
  date: (typeof REPAIRS_TABLE_TEST_DATES)[number],
  dateIndex: number,
  itemIndex: number
): RepairTaskDto {
  const sequence = dateIndex * 6 + itemIndex + 1
  const status = subtaskStatuses[sequence % subtaskStatuses.length]
  const kind = sequence % 4 === 0 ? "REWORK" : "REPAIR"
  const origin: RepairTaskOrigin =
    kind === "REWORK"
      ? "DIRECT_REPAIR"
      : sequence % 3 === 0
        ? "INVENTORY"
        : sequence % 2 === 0
          ? "ESTIMATE"
          : "DIRECT_REPAIR"
  const repairId = `repair-table-fixture-${String(sequence).padStart(2, "0")}`
  const cabinNumber = `ТЕСТ-${String(sequence).padStart(3, "0")}`
  const timestamp = `${date}T08:00:00Z`

  return {
    id: repairId,
    version: 1,
    status: status === "IN_PROGRESS" ? "IN_PROGRESS" : "QUEUED",
    kind,
    origin,
    acceptanceStatus: "NOT_READY",
    startedAt: status === "IN_PROGRESS" ? timestamp : null,
    completedAt: null,
    warehouseId: "00000000-0000-4000-8000-000000000001",
    rentalItemId: `rental-item-fixture-${String(sequence).padStart(2, "0")}`,
    cabinNumber,
    actorId: "repairs-table-test",
    sourceParty: "Автотест",
    dispatchDate: date,
    priority: priorities[(sequence - 1) % priorities.length],
    taskBoardAvailable: true,
    subtasks: [
      {
        id: `${repairId}-subtask`,
        externalTaskId: `${repairId}-external`,
        taskTitle: taskTitles[(sequence - 1) % taskTitles.length],
        taskText: null,
        kind: "REPAIR_WORK",
        status,
        workLines: [],
        materialLines: [],
        groupComment: "",
        queueName: "Ремонт",
        queueId: "repair-queue",
        routeQueueKind: "REPAIR",
        sortOrder: 0,
        entryType: "REAL",
        queuePosition: itemIndex,
        scheduledDate: date,
        priority: priorities[(sequence - 1) % priorities.length],
        pinned: sequence % 2 === 0,
        plannedDurationMinutes: 60,
        startedAt: status === "IN_PROGRESS" ? timestamp : null,
        completedAt: null,
        activeStartedAt: status === "IN_PROGRESS" ? timestamp : null,
        activeWorkSeconds: status === "IN_PROGRESS" ? 900 : 0,
        workerGroup: null,
        assignments: [],
      },
    ],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    movementToRepair: false,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    createdAt: timestamp,
    updatedAt: timestamp,
  }
}

export const REPAIRS_TABLE_TEST_FIXTURES: RepairTaskDto[] =
  REPAIRS_TABLE_TEST_DATES.flatMap((date, dateIndex) =>
    Array.from({ length: 6 }, (_, itemIndex) =>
      createRepair(date, dateIndex, itemIndex)
    )
  )
