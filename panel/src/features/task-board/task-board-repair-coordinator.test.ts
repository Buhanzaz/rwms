import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import {
  SPB_WAREHOUSE_ID,
  taskBoardMockClient,
} from "@/features/task-board/mock"

const repairMocks = vi.hoisted(() => ({
  complete: vi.fn(),
  list: vi.fn(),
  move: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
  take: vi.fn(),
}))

vi.mock("@/features/auth/auth-config", () => ({
  DEV_AUTH_BYPASS_ENABLED: true,
}))

vi.mock("@/features/repair-tasks/api/repair-tasks-api", () => ({
  completeRepairTaskEntry: repairMocks.complete,
  listRepairTasks: repairMocks.list,
  moveRepairTaskEntry: repairMocks.move,
  pauseRepairTaskEntry: repairMocks.pause,
  resumeRepairTaskEntry: repairMocks.resume,
  takeRepairTaskEntry: repairMocks.take,
}))

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  () => ({ getOperationalRepairEstimateCatalog: vi.fn() })
)

import {
  completeTaskBoardEntry,
  confirmTaskBoardGroupReturned,
  getTaskBoard,
} from "@/features/task-board/api/task-board-api"
import { createMockTaskBoardProjection } from "@/features/task-board/domain/task-board-domain"

function subtask(
  id: string,
  queueCode: string,
  sortOrder: number,
  status: "WAITING" | "IN_PROGRESS" | "PAUSED" | "DONE" = "WAITING"
): RepairTaskDto["subtasks"][number] {
  return {
    id,
    kind: "REPAIR_WORK",
    status,
    workLines: [],
    materialLines: [],
    groupComment: "",
    queueCode,
    routeQueueKind: "REPAIR",
    sortOrder,
    queuePosition: sortOrder * 10,
    plannedDurationMinutes: null,
    photoRequired: false,
    startedAt: null,
    completedAt: status === "DONE" ? "2026-07-12T09:00:00.000Z" : null,
    activeStartedAt: null,
    activeWorkSeconds: 0,
    workerGroup: null,
    assignments: [],
    resultMedia: [],
    assigneeName: null,
  }
}

function repairTask(): RepairTaskDto {
  return {
    id: "repair-source-1",
    version: 0,
    status: "QUEUED",
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    acceptanceStatus: "NOT_READY",
    startedAt: null,
    completedAt: null,
    acceptanceDecidedAt: null,
    acceptanceDecidedBy: null,
    acceptanceComment: null,
    warehouseId: "spb",
    rentalItemId: "rental-1",
    cabinNumber: "БЫТ-001",
    authorName: "Администратор",
    reason: "Ремонт бытовки",
    dispatchDate: null,
    comment: "Проверить результат",
    media: [],
    subtasks: [
      subtask("step-done", "INTERNAL_WORKS", 0, "DONE"),
      subtask("step-active", "ELECTRICS", 1),
    ],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    createdAt: "2026-07-12T08:00:00.000Z",
    updatedAt: "2026-07-12T09:00:00.000Z",
  }
}

describe("repair task-board coordinator", () => {
  let source: RepairTaskDto

  beforeEach(() => {
    taskBoardMockClient.resetForTests()
    vi.clearAllMocks()
    source = repairTask()
    repairMocks.list.mockImplementation(async () => [structuredClone(source)])
    repairMocks.complete.mockImplementation(async ({ subtaskId }) => {
      source = {
        ...source,
        version: source.version + 1,
        subtasks: source.subtasks.map((item) =>
          item.id === subtaskId
            ? {
                ...item,
                status: "DONE",
                completedAt: "2026-07-12T10:00:00.000Z",
              }
            : item
        ),
      }
      if (source.subtasks.every((item) => item.status === "DONE")) {
        source = { ...source, status: "COMPLETED" }
      }
      return structuredClone(source)
    })
  })

  async function prepareTwoReturningTasks() {
    const initial = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    const active = initial.tasks.find((task) => task.status === "IN_PROGRESS")!
    const assignment = initial.assignments.find(
      (candidate) => candidate.id === active.assignmentId
    )!
    const group = initial.groups.find(
      (candidate) => candidate.id === assignment.workerGroupId
    )!
    const workerIds = group.members
      .filter((member) => member.active)
      .map((member) => member.workerId)
    const second = initial.tasks.find(
      (task) => task.status === "QUEUED" && task.queueCode === "INTERNAL_WORKS"
    )!
    const movement = initial.tasks.find(
      (task) => task.status === "QUEUED" && task.queueCode === "MOVEMENT"
    )!
    await taskBoardMockClient.takeTask({
      taskId: second.id,
      expectedVersion: second.version,
      workerGroupId: group.id,
      workerIds,
    })
    await taskBoardMockClient.takeTask({
      taskId: movement.id,
      expectedVersion: movement.version,
      workerGroupId: group.id,
      workerIds,
    })
    const takenMovement = await taskBoardMockClient.findByExternalTaskId(
      SPB_WAREHOUSE_ID,
      movement.externalTaskId
    )
    await taskBoardMockClient.completeTask({
      taskId: takenMovement!.id,
      expectedVersion: takenMovement!.version,
    })
    const snapshot = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    const returning = snapshot.interruptions.filter(
      (interruption) =>
        interruption.workerGroupId === group.id &&
        interruption.state === "RETURNING"
    )
    const entry = createMockTaskBoardProjection(snapshot)
      .queues.flatMap((queue) => queue.entries)
      .find(
        (candidate) =>
          candidate.interruption?.id === returning[0]?.id &&
          candidate.runtimeTask?.status === "RETURNING"
      )!
    return { entry, group, returning }
  }

  it("skips a step completed before first import and attaches the exact source step", async () => {
    const board = await getTaskBoard("spb", SPB_WAREHOUSE_ID)
    const real = board.queues
      .flatMap((queue) => queue.entries)
      .find((entry) => entry.entryType === "REAL" && entry.detailsHref)

    expect(real?.subtask.id).toBe("step-active")
    expect(real?.runtimeTask?.route[0]?.externalStepId).toBe("step-done")
    expect(real?.runtimeTask?.route[1]?.externalStepId).toBe("step-active")
  })

  it("persists completion media before runtime advance and recovers a stale runtime write", async () => {
    const board = await getTaskBoard("spb", SPB_WAREHOUSE_ID)
    const entry = board.queues
      .flatMap((queue) => queue.entries)
      .find((candidate) => candidate.detailsHref)!
    const upload = {
      id: "photo-1",
      file: new File(["photo"], "result.jpg", { type: "image/jpeg" }),
      previewUrl: "blob:result",
      rotationDegrees: 0 as const,
    }
    await taskBoardMockClient.pauseTask({
      taskId: entry.runtimeTask!.id,
      expectedVersion: entry.runtimeTask!.version,
    })

    await expect(completeTaskBoardEntry(entry, [upload])).rejects.toMatchObject(
      {
        status: 409,
      }
    )
    expect(repairMocks.complete).toHaveBeenCalledWith(
      expect.objectContaining({
        subtaskId: "step-active",
        pendingUploads: [upload],
      })
    )

    await getTaskBoard("spb", SPB_WAREHOUSE_ID)
    expect(
      await taskBoardMockClient.findByExternalTaskId(
        SPB_WAREHOUSE_ID,
        "repair:repair-source-1"
      )
    ).toMatchObject({ status: "DONE" })
  })

  it("synchronizes an unstarted route amendment and source cancellation", async () => {
    await getTaskBoard("spb", SPB_WAREHOUSE_ID)
    source = {
      ...source,
      version: 1,
      reason: "Уточнённый ремонт",
      subtasks: [
        subtask("step-done", "INTERNAL_WORKS", 0, "DONE"),
        subtask("step-active", "ELECTRICS", 1),
        subtask("step-new", "PLUMBING", 2),
      ],
    }

    await getTaskBoard("spb", SPB_WAREHOUSE_ID)
    let runtime = await taskBoardMockClient.findByExternalTaskId(
      SPB_WAREHOUSE_ID,
      "repair:repair-source-1"
    )
    expect(runtime?.title).toBe("Уточнённый ремонт")
    expect(runtime?.route.map((step) => step.externalStepId)).toEqual([
      "step-done",
      "step-active",
      "step-new",
    ])

    source = { ...source, version: 2, status: "CANCELLED" }
    const board = await getTaskBoard("spb", SPB_WAREHOUSE_ID)
    runtime = await taskBoardMockClient.findByExternalTaskId(
      SPB_WAREHOUSE_ID,
      "repair:repair-source-1"
    )
    expect(runtime?.status).toBe("CANCELLED")
    expect(
      board.queues
        .flatMap((queue) => queue.entries)
        .some(
          (entry) =>
            entry.runtimeTask?.externalTaskId === "repair:repair-source-1"
        )
    ).toBe(false)
  })

  it("confirms every returning task of the clicked crew in one command", async () => {
    const prepared = await prepareTwoReturningTasks()
    expect(prepared.returning).toHaveLength(2)

    await confirmTaskBoardGroupReturned(prepared.entry)

    const snapshot = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.interruptions.filter(
        (interruption) =>
          interruption.workerGroupId === prepared.group.id &&
          interruption.state === "RETURNING"
      )
    ).toHaveLength(0)
    expect(
      snapshot.interruptions.filter(
        (interruption) =>
          interruption.workerGroupId === prepared.group.id &&
          interruption.state === "RESUMED"
      )
    ).toHaveLength(2)
  })

  it("rejects a stale group-return set before changing the remaining task", async () => {
    const prepared = await prepareTwoReturningTasks()
    const command = {
      workerGroupId: prepared.group.id,
      interruptions: prepared.returning.map((interruption) => ({
        id: interruption.id,
        expectedVersion: interruption.version,
      })),
    }
    await taskBoardMockClient.confirmGroupReturned({
      workerGroupId: prepared.group.id,
      interruptions: [command.interruptions[0]!],
    })

    await expect(
      taskBoardMockClient.confirmGroupReturned(command)
    ).rejects.toMatchObject({ status: 409 })
    const snapshot = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.interruptions.find(
        (interruption) => interruption.id === command.interruptions[1]!.id
      )?.state
    ).toBe("RETURNING")
  })
})
