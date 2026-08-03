import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const useServiceOwnerMedia = vi.hoisted(() => vi.fn())
const serviceOwnerPhotos = vi.hoisted(() => vi.fn())

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({
    photos,
  }: {
    photos: Array<{ id: string; url: string }>
  }) => (
    <div>
      {photos.map((photo, index) => (
        <img
          key={photo.id}
          src={photo.url}
          alt={`Фото ${index + 1} из ${photos.length}`}
        />
      ))}
    </div>
  ),
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: (props: unknown) => {
    serviceOwnerPhotos(props)
    return <div>Фото владельца</div>
  },
}))

vi.mock("@/features/media/use-service-owner-media", () => ({
  useServiceOwnerMedia,
}))

import { formatAcceptanceDateTime } from "@/features/acceptance/acceptance-formatters"
import { RepairAcceptanceDossier } from "@/features/acceptance/repair-acceptance-dossier"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const repairId = "00000000-0000-4000-8000-000000000002"
const rentalItemId = "00000000-0000-4000-8000-000000000003"
const stageId = "00000000-0000-4000-8000-000000000004"
const entryId = "00000000-0000-4000-8000-000000000005"
const evidenceId = "00000000-0000-4000-8000-000000000006"
const mediaId = "00000000-0000-4000-8000-000000000007"
const workerId = "00000000-0000-4000-8000-000000000008"
const workerGroupId = "00000000-0000-4000-8000-000000000009"
const workLineId = "00000000-0000-4000-8000-000000000010"
const sourceMediaId = "00000000-0000-4000-8000-000000000012"
const capturedAt = "2026-07-18T09:45:00Z"
const recordedAt = "2026-07-18T10:00:00Z"

const task: RepairTaskDto = {
  id: repairId,
  version: 4,
  status: "COMPLETED",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "PENDING",
  startedAt: "2026-07-18T08:00:00Z",
  completedAt: recordedAt,
  warehouseId,
  rentalItemId,
  cabinNumber: "БТ-42",
  actorId: "operator-1",
  sourceParty: "Склад",
  dispatchDate: "2026-07-18",
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
          maintenanceMediaReferences: [
            { mediaId: sourceMediaId, generation: 3 },
          ],
        },
      ],
      materialLines: [],
      primaryLineId: workLineId,
      groupComment: "Монтажная группа",
      evidence: [
        {
          evidenceId,
          entryId,
          workerId,
          workerDisplayName: "Иван Петров",
          workerGroupId,
          workerGroupName: "Бригада 1",
          mediaId,
          mediaGeneration: 4,
          capturedAt,
          recordedAt,
          state: "READY",
        },
      ],
      queueId: "00000000-0000-4000-8000-000000000011",
      queueName: "Ремонт",
      routeQueueKind: "REPAIR",
      sortOrder: 0,
      queuePosition: 0,
      plannedDurationMinutes: 30,
      startedAt: "2026-07-18T08:00:00Z",
      completedAt: recordedAt,
      activeStartedAt: null,
      activeWorkSeconds: 3600,
      workerGroup: { id: workerGroupId, name: "Бригада 1" },
      assignments: [],
    },
  ],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: null,
  sourceInventoryFindingId: null,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  readyAt: recordedAt,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-07-18T07:00:00Z",
  updatedAt: recordedAt,
}

function renderDossier(canEdit = false, dossierTask: RepairTaskDto = task) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <RepairAcceptanceDossier
          accessToken="token"
          task={dossierTask}
          mode="ACCEPTANCE"
          canEdit={canEdit}
          canManage={false}
        />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  useServiceOwnerMedia.mockReturnValue({
    assets: [
      {
        id: mediaId,
        kind: "IMAGE",
        status: "READY",
        generation: 4,
      },
    ],
    photos: [
      {
        id: mediaId,
        url: "blob:worker-result",
        variants: { medium: { url: "blob:worker-result" } },
      },
    ],
    query: {
      isLoading: false,
      isError: false,
      isSuccess: true,
    },
    requestFullscreen: vi.fn(),
    previewUnavailable: false,
  })
})

afterEach(cleanup)

describe("repair acceptance worker evidence", () => {
  it("shows the stage, worker facts and exact projected media owner", () => {
    renderDossier()

    expect(screen.getByText("БТ-42")).toBeTruthy()
    expect(screen.getByText("Заменить окно")).toBeTruthy()
    expect(screen.getByText("Основная")).toBeTruthy()
    expect(screen.getByText("Проверить герметичность")).toBeTruthy()
    expect(screen.getByText("Иван Петров")).toBeTruthy()
    expect(screen.getAllByText("Бригада 1").length).toBeGreaterThan(0)
    expect(screen.getByText("Норматив")).toBeTruthy()
    expect(screen.getByText("30 мин")).toBeTruthy()
    expect(screen.getByText("Активное время")).toBeTruthy()
    expect(screen.getByText("1 ч 0 мин")).toBeTruthy()
    expect(screen.getByText("Фото приёмки")).toBeTruthy()
    expect(screen.getByText("Фото после · этап 1")).toBeTruthy()
    expect(screen.getByText("Этап 1: ремонтные работы")).toBeTruthy()
    expect(screen.queryByText("Этапы ремонта")).toBeNull()
    expect(screen.queryByText("Фото ремонта")).toBeNull()
    expect(screen.getByLabelText("Комментариев: 1").textContent).toBe("1")
    expect(screen.getByText(formatAcceptanceDateTime(capturedAt))).toBeTruthy()
    expect(
      screen.getAllByText(formatAcceptanceDateTime(recordedAt)).length
    ).toBeGreaterThanOrEqual(2)
    expect(screen.getByAltText("Фото 1 из 1").getAttribute("src")).toBe(
      "blob:worker-result"
    )
    expect(useServiceOwnerMedia).toHaveBeenCalledWith({
      accessToken: "token",
      owner: {
        ownerType: "TASK_BOARD_ENTRY",
        ownerId: entryId,
        warehouseId,
        context: "WORK_RESULT",
      },
    })
    expect(serviceOwnerPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        owner: {
          ownerType: "MAINTENANCE_ACCEPTANCE",
          ownerId: repairId,
          warehouseId,
          context: "ACCEPTANCE",
        },
      })
    )
    expect(serviceOwnerPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        owner: {
          ownerType: "TASK_BOARD_ENTRY",
          ownerId: entryId,
          warehouseId,
          context: "WORK_RESULT",
        },
        authoritativeReadyReferences: [
          { mediaId: sourceMediaId, generation: 3 },
        ],
      })
    )
  })

  it("records a decision for each work and opens rework for the selected line", async () => {
    const user = userEvent.setup()
    renderDossier(true)

    await user.click(screen.getByRole("button", { name: "Переделать" }))
    expect(
      screen.getByRole("button", { name: "Создать доработку (1)" })
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Создать доработку (1)" })
    )
    expect(screen.getByText("Создать доработку")).toBeTruthy()
    expect(
      screen.getByText(/автоматически попадут отмеченные для переделки работы/)
    ).toBeTruthy()
  })

  it("reads capital work photos from the estimate when no task-board entry exists", () => {
    const capitalTask: RepairTaskDto = {
      ...task,
      origin: "ESTIMATE",
      sourceEstimateId: "00000000-0000-4000-8000-000000000013",
      subtasks: task.subtasks.map((subtask) => ({
        ...subtask,
        taskBoardEntryId: null,
      })),
    }

    renderDossier(false, capitalTask)

    expect(serviceOwnerPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        owner: {
          ownerType: "MAINTENANCE_ESTIMATE",
          ownerId: capitalTask.sourceEstimateId,
          warehouseId,
          context: "ESTIMATE",
        },
        authoritativeReadyReferences: [
          { mediaId: sourceMediaId, generation: 3 },
        ],
      })
    )
  })

  it("does not substitute a different media generation", () => {
    useServiceOwnerMedia.mockReturnValue({
      assets: [
        {
          id: mediaId,
          kind: "IMAGE",
          status: "READY",
          generation: 5,
        },
      ],
      photos: [
        {
          id: mediaId,
          url: "blob:newer-generation",
          variants: { medium: { url: "blob:newer-generation" } },
        },
      ],
      query: {
        isLoading: false,
        isError: false,
        isSuccess: true,
      },
      requestFullscreen: vi.fn(),
      previewUnavailable: false,
    })

    renderDossier()

    expect(screen.queryByAltText("Фото 1 из 1")).toBeNull()
    expect(screen.getByText(/1 фото ещё недоступно/)).toBeTruthy()
  })
})
