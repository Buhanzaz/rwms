import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

const repairTasksApi = vi.hoisted(() => ({
  queueRepairTask: vi.fn(),
  saveRepairTaskDraft: vi.fn(),
  writeOffRepairDraft: vi.fn(),
}))
const maintenanceLifecycle = vi.hoisted(() => ({
  getReworkCandidates: vi.fn(),
}))

vi.mock("@/features/repair-tasks/api/repair-tasks-api", () => ({
  REPAIR_TASKS_QUERY_KEY: ["repair-tasks"],
  queueRepairTask: repairTasksApi.queueRepairTask,
  repairTaskDetailQueryKey: (warehouseId: string, taskId: string | null) => [
    "repair-tasks",
    "detail",
    warehouseId,
    taskId,
  ],
  saveRepairTaskDraft: repairTasksApi.saveRepairTaskDraft,
  writeOffRepairDraft: repairTasksApi.writeOffRepairDraft,
}))
vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  () => ({
    getMaintenanceReworkCandidates: maintenanceLifecycle.getReworkCandidates,
  })
)

vi.mock("@/features/repair-estimates/repair-estimate-workspace-layout", () => ({
  RepairEstimateWorkspaceLayout: ({
    controls,
    photos,
    estimate,
    message,
  }: {
    controls: ReactNode
    photos: ReactNode
    estimate: ReactNode
    message: ReactNode
  }) => (
    <section>
      {message}
      {photos}
      {estimate}
      {controls}
    </section>
  ),
}))

vi.mock("@/features/repair-estimates/repair-estimate-catalog-picker", () => ({
  RepairEstimateCatalogPicker: ({
    onChange,
  }: {
    onChange: (lines: unknown[]) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onChange([
          {
            id: "55555555-5555-4555-8555-555555555555",
            sourceLineKey: "work-1",
            lineType: "WORK",
            description: "Заменить панель",
            lineComment: "",
            unit: "шт.",
            quantity: 1,
            normativeMinutes: 60,
            unitPrice: "100.00",
            lineTotal: "100.00",
            catalogSnapshot: null,
          },
        ])
      }
    >
      Добавить работу
    </button>
  ),
}))

vi.mock("@/features/repair-estimates/repair-estimate-lines-editor", () => ({
  RepairEstimateLinesEditor: ({
    lines = [],
  }: {
    lines?: Array<{
      description: string
      rework?: { disposition: "ADDED" | "REPEAT" } | null
    }>
  }) => (
    <div>
      <p>Строки ремонта</p>
      {lines.map((line) => (
        <p key={line.description}>
          {line.rework?.disposition ?? "PRIMARY"} · {line.description}
        </p>
      ))}
    </div>
  ),
}))

vi.mock("@/features/repair-estimates/repair-work-information-fields", () => ({
  RepairWorkInformationFields: () => null,
}))

vi.mock("@/features/repair-estimates/repair-work-completion-dialog", () => ({
  RepairWorkCompletionDialog: ({
    open,
    onComplete,
  }: {
    open: boolean
    onComplete: (completion: {
      completionMode: "MANUAL"
      movementToRepair: false
      logisticsPlanningMode: "AUTO"
      logisticsScheduledDate: null
      taskPlans: []
      priority: 1
    }) => void
  }) =>
    open ? (
      <button
        type="button"
        onClick={() =>
          onComplete({
            completionMode: "MANUAL",
            movementToRepair: false,
            logisticsPlanningMode: "AUTO",
            logisticsScheduledDate: null,
            taskPlans: [],
            priority: 1,
          })
        }
      >
        Подтвердить постановку
      </button>
    ) : null,
}))

vi.mock("@/features/rental-items/cabin-furniture-panel", () => ({
  CabinFurniturePanel: () => null,
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({ toolbarAction }: { toolbarAction?: ReactNode }) => (
    <section>{toolbarAction}</section>
  ),
}))

vi.mock("@/features/repair-estimates/previous-maintenance-photos", () => ({
  PreviousMaintenancePhotos: () => <p>Фотографии до</p>,
}))

vi.mock("@/features/repair-tasks/repair-task-write-off-dialog", () => ({
  RepairTaskWriteOffDialog: () => null,
}))

import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairTaskQueueDraftPersistedError } from "@/features/repair-tasks/ports/repair-tasks-client"
import { RepairTaskEditorWorkspace } from "@/features/repair-tasks/repair-task-editor-workspace"
import { ApiError } from "@/lib/api-client"

const warehouseId = "11111111-1111-4111-8111-111111111111"
const rentalItemId = "22222222-2222-4222-8222-222222222222"
const repairId = "33333333-3333-4333-8333-333333333333"

const queuedTask: RepairTaskDto = {
  id: repairId,
  version: 5,
  status: "QUEUED",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "NOT_READY",
  startedAt: null,
  completedAt: null,
  warehouseId,
  rentalItemId,
  cabinNumber: "БЫТ-001",
  actorId: "operator-1",
  dispatchDate: "2026-07-24",
  subtasks: [],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: null,
  sourceInventoryFindingId: null,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  movementToRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-07-24T10:00:00Z",
  updatedAt: "2026-07-24T10:01:00Z",
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

maintenanceLifecycle.getReworkCandidates.mockResolvedValue({ items: [] })

describe("RepairTaskEditorWorkspace queue retry", () => {
  it("adds acceptance-selected chain work as an explicit repeat line", async () => {
    maintenanceLifecycle.getReworkCandidates.mockResolvedValueOnce({
      items: [
        {
          sourceRepairId: repairId,
          sourceLineId: "source-line-1",
          lineageRootLineId: "root-line-1",
          line: {
            id: "source-line-1",
            lineType: "WORK",
            description: "Ремонт каркаса",
            unit: "шт",
            quantity: "1",
            normativeMinutes: 30,
            unitPrice: "100.00",
            comment: "Сделано плохо",
            mediaReferences: [],
            lineTotal: "100.00",
            catalogSnapshot: null,
          },
        },
      ],
    })

    render(
      <MemoryRouter>
        <QueryClientProvider
          client={
            new QueryClient({
              defaultOptions: {
                queries: { retry: false },
                mutations: { retry: false },
              },
            })
          }
        >
          <RepairTaskEditorWorkspace
            accessToken="maintenance-token"
            warehouseId={warehouseId}
            task={null}
            seed={{
              type: "repair-rework-seed-v1",
              warehouseId,
              sourceRepairTaskId: repairId,
              sourceRepairTaskVersion: 5,
              sourceOrigin: "DIRECT_REPAIR",
              sourceEstimateId: null,
              sourceEstimateVersion: null,
              rentalItemId,
              lines: [],
              selectedLineageRootIds: ["root-line-1"],
            }}
            onClose={vi.fn()}
            onSaved={vi.fn()}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )

    expect(await screen.findByText("REPEAT · Ремонт каркаса")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Переделать" })).toBeNull()
  })

  it("places editor actions in the top toolbar and keeps catalog paging below", async () => {
    const user = userEvent.setup()
    const onBack = vi.fn()
    const onClose = vi.fn()

    render(
      <MemoryRouter>
        <QueryClientProvider
          client={
            new QueryClient({
              defaultOptions: {
                queries: { retry: false },
                mutations: { retry: false },
              },
            })
          }
        >
          <RepairTaskEditorWorkspace
            accessToken="maintenance-token"
            warehouseId={warehouseId}
            task={null}
            initialRentalItemId={rentalItemId}
            onBack={onBack}
            onClose={onClose}
            onSaved={vi.fn()}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )

    expect(screen.getAllByRole("button", { name: "Назад" })).toHaveLength(1)
    const toolbar = screen
      .getByRole("button", { name: "Назад" })
      .closest('[data-slot="page-toolbar"]')
    if (!(toolbar instanceof HTMLElement)) {
      throw new Error("Не найдена верхняя панель редактора")
    }

    expect(within(toolbar).getByRole("button", { name: "Отмена" })).toBeTruthy()
    expect(
      within(toolbar).getByRole("button", { name: "Сохранить черновик" })
    ).toBeTruthy()
    expect(
      within(toolbar).getByRole("button", { name: "Завершить" })
    ).toBeTruthy()
    expect(
      within(toolbar).queryByRole("button", {
        name: "Предыдущая страница каталога",
      })
    ).toBeNull()
    expect(
      screen.getByRole("button", { name: "Предыдущая страница каталога" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Следующая страница каталога" })
    ).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "Назад" }))
    expect(onBack).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
  })

  it("replaces repair lines with previous photos and restores them", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })

    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <RepairTaskEditorWorkspace
            accessToken="maintenance-token"
            warehouseId={warehouseId}
            task={queuedTask}
            onClose={vi.fn()}
            onSaved={vi.fn()}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )

    expect(screen.getByText("Строки ремонта")).toBeTruthy()
    expect(screen.queryByText("Фотографии до")).toBeNull()

    await user.click(screen.getByRole("button", { name: "Показать до" }))

    expect(screen.queryByText("Строки ремонта")).toBeNull()
    expect(screen.getByText("Фотографии до")).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "Скрыть до" }))

    expect(screen.getByText("Строки ремонта")).toBeTruthy()
    expect(screen.queryByText("Фотографии до")).toBeNull()
  })

  it("reuses the latest draft version after async confirmation times out", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    const invalidateQueries = vi.spyOn(queryClient, "invalidateQueries")
    const onSaved = vi.fn()
    repairTasksApi.queueRepairTask
      .mockRejectedValueOnce(
        new RepairTaskQueueDraftPersistedError({
          taskId: repairId,
          expectedVersion: 7,
          message: `Ремонт ${repairId} сохранён, но его состояние в maintenance-service всё ещё ожидает подтверждения постановки в очередь.`,
        })
      )
      .mockResolvedValueOnce(queuedTask)

    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <RepairTaskEditorWorkspace
            accessToken="maintenance-token"
            warehouseId={warehouseId}
            task={null}
            initialRentalItemId={rentalItemId}
            onClose={vi.fn()}
            onSaved={onSaved}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )

    await user.click(screen.getByRole("button", { name: "Добавить работу" }))
    await user.click(screen.getByRole("button", { name: "Завершить" }))
    await user.click(
      screen.getByRole("button", { name: "Подтвердить постановку" })
    )

    expect((await screen.findByRole("alert")).textContent).toContain(
      `Ремонт ${repairId} сохранён, но его состояние в maintenance-service всё ещё ожидает подтверждения постановки в очередь.`
    )
    expect(repairTasksApi.queueRepairTask).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        draft: expect.objectContaining({
          taskId: null,
          expectedVersion: null,
        }),
        movementToRepair: false,
        priority: 1,
      })
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["repair-tasks"],
    })

    await user.click(
      screen.getByRole("button", { name: "Подтвердить постановку" })
    )

    await waitFor(() =>
      expect(repairTasksApi.queueRepairTask).toHaveBeenNthCalledWith(
        2,
        expect.objectContaining({
          draft: expect.objectContaining({
            taskId: repairId,
            expectedVersion: 7,
          }),
          movementToRepair: false,
          priority: 1,
        })
      )
    )
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(queuedTask))
  })

  it("saves a queued pre-start repair as changes without queueing it again", async () => {
    const user = userEvent.setup()
    const onSaved = vi.fn()
    repairTasksApi.saveRepairTaskDraft.mockResolvedValueOnce(queuedTask)

    render(
      <MemoryRouter>
        <QueryClientProvider
          client={
            new QueryClient({
              defaultOptions: {
                queries: { retry: false },
                mutations: { retry: false },
              },
            })
          }
        >
          <RepairTaskEditorWorkspace
            accessToken="maintenance-token"
            warehouseId={warehouseId}
            task={queuedTask}
            onClose={vi.fn()}
            onSaved={onSaved}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )

    expect(
      screen.getByRole("button", { name: "Сохранить изменения" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Сохранить черновик" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Завершить" })).toBeNull()

    await user.click(screen.getByRole("button", { name: "Добавить работу" }))
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    await waitFor(() =>
      expect(repairTasksApi.saveRepairTaskDraft).toHaveBeenCalledWith({
        draft: expect.objectContaining({
          taskId: repairId,
          expectedVersion: queuedTask.version,
        }),
        warehouseId,
      })
    )
    expect(repairTasksApi.queueRepairTask).not.toHaveBeenCalled()
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(queuedTask))
  })

  it("explains a pre-start edit conflict and refreshes repair queries", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    const invalidateQueries = vi.spyOn(queryClient, "invalidateQueries")
    repairTasksApi.saveRepairTaskDraft.mockRejectedValueOnce(
      new ApiError("repair is no longer amendable", 409)
    )

    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <RepairTaskEditorWorkspace
            accessToken="maintenance-token"
            warehouseId={warehouseId}
            task={queuedTask}
            onClose={vi.fn()}
            onSaved={vi.fn()}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )

    await user.click(screen.getByRole("button", { name: "Добавить работу" }))
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    expect((await screen.findByRole("alert")).textContent).toBe(
      "Ремонт уже изменён или задание уже начато. Данные обновлены — откройте ремонт снова."
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["repair-tasks"],
    })
  })
})
