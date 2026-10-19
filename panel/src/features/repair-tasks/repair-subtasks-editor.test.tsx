import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const mocks = vi.hoisted(() => ({
  getTaskRegistration: vi.fn(),
  getTaskRequirements: vi.fn(),
  getKpiPalette: vi.fn(),
}))

vi.mock("@/features/task-board/api/task-requirements-api", () => ({
  getTaskRegistration: mocks.getTaskRegistration,
  getTaskRequirements: mocks.getTaskRequirements,
  taskRegistrationQueryKey: (warehouseId: string, externalTaskId: string) =>
    ["task-board", warehouseId, "registration", externalTaskId],
  taskRequirementsQueryKey: (warehouseId: string, taskId: string) =>
    ["task-board", warehouseId, "requirements", taskId],
}))

vi.mock("@/features/settings/kpi/api/kpi-settings-api", () => ({
  getKpiPalette: mocks.getKpiPalette,
  kpiSettingsKeys: { palette: ["task-board", "kpi-palette"] },
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: () => null,
}))

import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairSubtasksEditor } from "@/features/repair-tasks/repair-subtasks-editor"
import { taskRequirementsQueryKey } from "@/features/task-board/api/task-requirements-api"

const warehouseId = "warehouse-1"
const boardTaskId = "board-task-1"

const task = {
  id: "repair-1",
  version: 1,
  status: "COMPLETED",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  startedAt: null,
  completedAt: null,
  warehouseId,
  cabinNumber: "БТ-1",
  actorId: "operator-1",
  subtasks: [
    {
      id: "subtask-1",
      externalTaskId: "external-1",
      taskBoardEntryId: "entry-1",
      kind: "REPAIR_WORK",
      status: "DONE",
      workLines: [
        { id: "missing-work", description: "Заменить окно", lineComment: "", quantity: 1, unit: "шт.", lineType: "WORK" },
        { id: "completed-work", description: "Проверить герметичность", lineComment: "", quantity: 1, unit: "шт.", lineType: "WORK" },
      ],
      materialLines: [
        { id: "missing-material", description: "Стеклопакет", lineComment: "", quantity: 2, unit: "шт.", lineType: "MATERIAL" },
        { id: "completed-material", description: "Клей", lineComment: "", quantity: 1, unit: "шт.", lineType: "MATERIAL" },
        { id: "available-material", description: "Крепёж", lineComment: "", quantity: 4, unit: "шт.", lineType: "MATERIAL" },
      ],
      primaryLineId: "missing-work",
      queuePosition: 0,
      evidence: [],
    },
    {
      id: "subtask-2",
      externalTaskId: "external-1",
      taskBoardEntryId: "entry-2",
      kind: "REPAIR_WORK",
      status: "DONE",
      workLines: [
        { id: "restored-work", description: "Установить окно", lineComment: "", quantity: 1, unit: "шт.", lineType: "WORK" },
      ],
      materialLines: [
        { id: "restored-material", description: "Герметик", lineComment: "", quantity: 1, unit: "шт.", lineType: "MATERIAL" },
      ],
      primaryLineId: "restored-work",
      queuePosition: 1,
      evidence: [],
    },
  ],
} as unknown as RepairTaskDto

const palette = {
  version: 1,
  ranges: [],
  overdueColor: "#f59e0b",
  problemColor: "#ff0000",
  completedColor: "#00aa00",
}

const requirements = {
  taskId: boardTaskId,
  taskVersion: 7,
  hasProblem: true,
  incomplete: true,
  completedWorkPercent: 50,
  items: [
    { itemId: "missing-work", kind: "WORK", name: "Заменить окно", state: "MISSING", linkedItemIds: [] },
    { itemId: "missing-material", kind: "MATERIAL", name: "Стеклопакет", state: "MISSING", linkedItemIds: [] },
    { itemId: "completed-work", kind: "WORK", name: "Проверить герметичность", state: "COMPLETED", linkedItemIds: [] },
    { itemId: "completed-material", kind: "MATERIAL", name: "Клей", state: "COMPLETED", linkedItemIds: [] },
    { itemId: "restored-work", kind: "WORK", name: "Установить окно", state: "RESTORED", linkedItemIds: [] },
    { itemId: "restored-material", kind: "MATERIAL", name: "Герметик", state: "RESTORED", linkedItemIds: [] },
    { itemId: "available-material", kind: "MATERIAL", name: "Крепёж", state: "AVAILABLE", linkedItemIds: [] },
  ],
}

function renderEditor(
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
) {
  render(
    <QueryClientProvider client={queryClient}>
      <RepairSubtasksEditor accessToken="token" task={task} readOnly />
    </QueryClientProvider>
  )
  return queryClient
}

beforeEach(() => {
  mocks.getTaskRegistration.mockReset()
  mocks.getTaskRequirements.mockReset()
  mocks.getKpiPalette.mockReset()
  mocks.getTaskRegistration.mockResolvedValue({ taskId: boardTaskId })
  mocks.getTaskRequirements.mockResolvedValue(requirements)
  mocks.getKpiPalette.mockResolvedValue({ palette })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairSubtasksEditor requirement snapshot", () => {
  it("maps missing, completed, restored and available states onto every line", async () => {
    renderEditor()

    await waitFor(() => expect(screen.getAllByText("Выполнено")).toHaveLength(2))
    expect(screen.getByText("Не выполнено")).toBeTruthy()
    expect(screen.getByText("Нет материала")).toBeTruthy()
    expect(screen.getAllByText("Восстановлено")).toHaveLength(2)
    expect(screen.getByRole("article", { name: "Заменить окно" }).style.borderColor).toBe("rgb(255, 0, 0)")
    expect(screen.getByRole("article", { name: "Стеклопакет × 2 шт." }).style.borderColor).toBe("rgb(255, 0, 0)")
    expect(screen.getByRole("article", { name: "Проверить герметичность" }).style.borderColor).toBe("rgb(0, 170, 0)")
    expect(screen.getByRole("article", { name: "Клей × 1 шт." }).style.borderColor).toBe("rgb(0, 170, 0)")
    expect(screen.getByRole("article", { name: "Установить окно" }).style.borderColor).toBe("rgb(0, 170, 0)")
    expect(screen.getByRole("article", { name: "Герметик × 1 шт." }).style.borderColor).toBe("rgb(0, 170, 0)")
    expect(screen.getByRole("article", { name: "Крепёж × 4 шт." }).style.borderColor).toBe("")
    expect(mocks.getTaskRegistration).toHaveBeenCalledTimes(1)
    expect(mocks.getTaskRequirements).toHaveBeenCalledTimes(1)
  })

  it("refreshes the authoritative requirement snapshot after invalidation", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    mocks.getTaskRequirements
      .mockResolvedValueOnce(requirements)
      .mockResolvedValueOnce({
        ...requirements,
        items: requirements.items.map((item) =>
          item.itemId === "missing-work"
            ? { ...item, state: "AVAILABLE" }
            : item
        ),
      })
    renderEditor(queryClient)
    await waitFor(() =>
      expect(screen.getByRole("article", { name: "Заменить окно" }).style.borderColor).toBe("rgb(255, 0, 0)")
    )

    await queryClient.invalidateQueries({
      queryKey: taskRequirementsQueryKey(warehouseId, boardTaskId),
    })
    await waitFor(() =>
      expect(screen.getByRole("article", { name: "Заменить окно" }).style.borderColor).toBe("")
    )
    expect(mocks.getTaskRequirements).toHaveBeenCalledTimes(2)
  })

  it("shows loading errors instead of presenting a false successful snapshot", async () => {
    mocks.getTaskRequirements.mockRejectedValue(new Error("requirements unavailable"))
    renderEditor()

    const alert = await screen.findByText(
      "Не удалось загрузить отметки работ и материалов. Повторите загрузку задания."
    )
    expect(alert.getAttribute("role")).toBe("alert")
    expect(screen.getByRole("article", { name: "Заменить окно" }).style.borderColor).toBe("")
  })
})
