import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

const serviceOwnerPhotos = vi.hoisted(() => vi.fn())

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: (props: unknown) => {
    serviceOwnerPhotos(props)
    return null
  },
}))

import type { ServiceMediaOwner } from "@/features/media/media-service"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairTaskDetailWorkspace } from "@/features/repair-tasks/repair-task-detail-workspace"
import {
  repairTaskGeneralMediaOwner,
  repairTaskSourceMediaOwner,
} from "@/features/repair-tasks/repair-task-media-owner"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const repairId = "00000000-0000-4000-8000-000000000002"
const rentalItemId = "00000000-0000-4000-8000-000000000003"
const stageId = "00000000-0000-4000-8000-000000000004"
const entryId = "00000000-0000-4000-8000-000000000005"
const workLineId = "00000000-0000-4000-8000-000000000006"
const materialLineId = "00000000-0000-4000-8000-000000000007"
const generalMediaId = "00000000-0000-4000-8000-000000000008"
const workMediaId = "00000000-0000-4000-8000-000000000009"
const resultMediaId = "00000000-0000-4000-8000-000000000010"
const findingId = "00000000-0000-4000-8000-000000000011"
const estimateId = "00000000-0000-4000-8000-000000000016"
const sourceRepairId = "00000000-0000-4000-8000-000000000017"

const task: RepairTaskDto = {
  id: repairId,
  version: 3,
  status: "COMPLETED",
  kind: "REPAIR",
  origin: "INVENTORY",
  acceptanceStatus: "PENDING",
  startedAt: "2026-08-19T08:00:00Z",
  completedAt: "2026-08-19T09:00:00Z",
  warehouseId,
  rentalItemId,
  cabinNumber: "БТ-42",
  actorId: "operator-1",
  sourceParty: "Инвентаризация",
  dispatchDate: "2026-08-19",
  maintenanceMediaReferences: [{ mediaId: generalMediaId, generation: 2 }],
  coverMediaId: generalMediaId,
  subtasks: [
    {
      id: stageId,
      taskBoardEntryId: entryId,
      kind: "REPAIR_WORK",
      status: "DONE",
      workLines: [
        {
          id: workLineId,
          sourceLineKey: workLineId,
          lineType: "WORK",
          description: "Заменить окно",
          lineComment: "Проверить герметичность",
          unit: "шт.",
          quantity: 1,
          unitPrice: "100.00",
          lineTotal: "100.00",
          catalogSnapshot: null,
          maintenanceMediaReferences: [{ mediaId: workMediaId, generation: 4 }],
        },
      ],
      materialLines: [
        {
          id: materialLineId,
          sourceLineKey: materialLineId,
          lineType: "MATERIAL",
          description: "Стеклопакет",
          lineComment: "",
          unit: "шт.",
          quantity: 1,
          unitPrice: "50.00",
          lineTotal: "50.00",
          catalogSnapshot: null,
        },
      ],
      primaryLineId: workLineId,
      groupComment: "",
      evidence: [
        {
          evidenceId: "00000000-0000-4000-8000-000000000012",
          entryId,
          workerId: "00000000-0000-4000-8000-000000000013",
          workerDisplayName: "Иван Петров",
          workerGroupId: null,
          workerGroupName: null,
          mediaId: resultMediaId,
          mediaGeneration: 5,
          capturedAt: "2026-08-19T08:55:00Z",
          recordedAt: "2026-08-19T09:00:00Z",
          state: "READY",
        },
      ],
      queueName: "Ремонт",
      queueId: "00000000-0000-4000-8000-000000000014",
      routeQueueKind: "REPAIR",
      sortOrder: 0,
      queuePosition: 0,
      plannedDurationMinutes: 30,
      startedAt: "2026-08-19T08:00:00Z",
      completedAt: "2026-08-19T09:00:00Z",
      activeStartedAt: null,
      activeWorkSeconds: 3_600,
      workerGroup: null,
      assignments: [],
    },
  ],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: "00000000-0000-4000-8000-000000000015",
  sourceInventoryFindingId: findingId,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  movementToRepair: false,
  forceCapitalRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-08-19T07:00:00Z",
  updatedAt: "2026-08-19T09:00:00Z",
}

function gallery(title: string) {
  const call = serviceOwnerPhotos.mock.calls.find(
    ([props]) => (props as { title?: string }).title === title
  )
  expect(call, `gallery ${title}`).toBeDefined()
  return call?.[0] as {
    owner: ServiceMediaOwner
    visibleMediaIds: string[]
    authoritativeReadyReferences: Array<{
      mediaId: string
      generation: number
    }>
    presentation?: string
    coverMediaId?: string | null
  }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairTaskDetailWorkspace media", () => {
  it("keeps general, work-bound, and result media in separate exact galleries", () => {
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RepairTaskDetailWorkspace accessToken="token" task={task} readOnly />
        </QueryClientProvider>
      </MemoryRouter>
    )

    expect(gallery("Общие медиа задания")).toMatchObject({
      owner: {
        ownerType: "TASK_BOARD_ENTRY",
        ownerId: entryId,
        warehouseId,
        context: "WORK_RESULT",
      },
      visibleMediaIds: [generalMediaId],
      authoritativeReadyReferences: [
        { mediaId: generalMediaId, generation: 2 },
      ],
      coverMediaId: generalMediaId,
    })
    expect(gallery("Общие медиа задания")).not.toHaveProperty("presentation")
    expect(gallery("Фото работы «Заменить окно»")).toMatchObject({
      owner: {
        ownerType: "TASK_BOARD_ENTRY",
        ownerId: entryId,
        warehouseId,
        context: "WORK_RESULT",
      },
      visibleMediaIds: [workMediaId],
      authoritativeReadyReferences: [{ mediaId: workMediaId, generation: 4 }],
      presentation: "work-carousel",
    })
    expect(gallery("Фото результата задания")).toMatchObject({
      owner: {
        ownerType: "TASK_BOARD_ENTRY",
        ownerId: entryId,
        warehouseId,
        context: "WORK_RESULT",
      },
      visibleMediaIds: [resultMediaId],
      authoritativeReadyReferences: [{ mediaId: resultMediaId, generation: 5 }],
    })
    expect(gallery("Фото результата задания")).not.toHaveProperty(
      "presentation"
    )
    expect(serviceOwnerPhotos).toHaveBeenCalledTimes(3)
  })

  it("selects the original owner for inventory, estimate, and inherited rework media", () => {
    expect(repairTaskGeneralMediaOwner(task)).toEqual({
      ownerType: "TASK_BOARD_ENTRY",
      ownerId: entryId,
      warehouseId,
      context: "WORK_RESULT",
    })
    expect(
      repairTaskSourceMediaOwner(task, task.subtasks[0].workLines[0], entryId)
    ).toEqual({
      ownerType: "TASK_BOARD_ENTRY",
      ownerId: entryId,
      warehouseId,
      context: "WORK_RESULT",
    })
    expect(
      repairTaskSourceMediaOwner(task, task.subtasks[0].workLines[0])
    ).toEqual({
      ownerType: "INVENTORY_FINDING",
      ownerId: findingId,
      warehouseId,
      context: "INSPECTION",
    })
    expect(
      repairTaskSourceMediaOwner({
        ...task,
        origin: "ESTIMATE",
        sourceEstimateId: estimateId,
      })
    ).toEqual({
      ownerType: "INVENTORY_FINDING",
      ownerId: findingId,
      warehouseId,
      context: "INSPECTION",
    })
    expect(
      repairTaskSourceMediaOwner({
        ...task,
        origin: "ESTIMATE",
        sourceEstimateId: estimateId,
        sourceInventoryId: null,
        sourceInventoryFindingId: null,
      })
    ).toEqual({
      ownerType: "MAINTENANCE_ESTIMATE",
      ownerId: estimateId,
      warehouseId,
      context: "ESTIMATE",
    })
    expect(
      repairTaskSourceMediaOwner(
        {
          ...task,
          kind: "REWORK",
          origin: "DIRECT_REPAIR",
          sourceInventoryId: null,
          sourceInventoryFindingId: null,
          sourceRepairTaskId: sourceRepairId,
        },
        {
          ...task.subtasks[0].workLines[0],
          rework: {
            disposition: "REPEAT",
            sourceRepairId,
            sourceLineId: workLineId,
            lineageRootLineId: workLineId,
          },
        }
      )
    ).toEqual({
      ownerType: "MAINTENANCE_REPAIR",
      ownerId: sourceRepairId,
      warehouseId,
      context: "REPAIR",
    })
  })
})
