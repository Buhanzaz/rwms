import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type { RepairEstimateDto } from "@/features/repair-estimates/model/repair-estimate"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const ESTIMATE_ID = "22222222-2222-4222-8222-222222222222"
const REPAIR_ID = "33333333-3333-4333-8333-333333333333"
const RENTAL_ITEM_ID = "44444444-4444-4444-8444-444444444444"

const authState = vi.hoisted(() => ({
  level: "VIEW" as WarehouseAccessLevel,
}))

const api = vi.hoisted(() => ({
  amendCompletedRepairEstimate: vi.fn(),
  completeRepairEstimate: vi.fn(),
  getRepairEstimate: vi.fn(),
  getRepairTask: vi.fn(),
  getRepairTaskBySourceEstimateId: vi.fn(),
  listRepairEstimates: vi.fn(),
  listRepairTasks: vi.fn(),
  queueRepairTask: vi.fn(),
  saveRepairEstimateDraft: vi.fn(),
  saveRepairTaskDraft: vi.fn(),
  updateRepairTaskSubtasks: vi.fn(),
  writeOffRepairDraft: vi.fn(),
}))

const actorApi = vi.hoisted(() => ({
  listDossierActorDisplays: vi.fn(),
}))

function currentUser(level: WarehouseAccessLevel): CurrentUser {
  return {
    id: "operator-1",
    username: "operator",
    displayName: "Operator",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    status: "authenticated",
    accessToken: "maintenance-token",
    currentUser: currentUser(authState.level),
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      name: "Москва",
    },
    selectedWarehouseId: WAREHOUSE_ID,
  }),
}))

vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => false,
}))

vi.mock("@/features/repair-estimates/api/repair-estimates-api", () => ({
  REPAIR_ESTIMATES_QUERY_KEY: ["repair-estimates"],
  amendCompletedRepairEstimate: api.amendCompletedRepairEstimate,
  completeRepairEstimate: api.completeRepairEstimate,
  getRepairEstimate: api.getRepairEstimate,
  listRepairEstimates: api.listRepairEstimates,
  repairEstimateDetailQueryKey: (
    warehouseId: string,
    estimateId: string | null
  ) => ["repair-estimates", "detail", warehouseId, estimateId],
  repairEstimateListQueryKey: (warehouseId: string, status?: string) => [
    "repair-estimates",
    "list",
    warehouseId,
    status ?? "all",
  ],
  returnEstimateSourcesQueryKey: (
    warehouseId: string,
    returnId: string | null
  ) => ["repair-estimates", "return-sources", warehouseId, returnId],
  saveRepairEstimateDraft: api.saveRepairEstimateDraft,
}))

vi.mock("@/features/rental-items/dossier/actor/actor-display-api", () => ({
  listDossierActorDisplays: actorApi.listDossierActorDisplays,
}))

vi.mock("@/features/repair-tasks/api/repair-tasks-api", () => ({
  REPAIR_TASKS_QUERY_KEY: ["repair-tasks"],
  getRepairTask: api.getRepairTask,
  getRepairTaskBySourceEstimateId: api.getRepairTaskBySourceEstimateId,
  listRepairTasks: api.listRepairTasks,
  queueRepairTask: api.queueRepairTask,
  repairTaskBySourceEstimateQueryKey: (
    warehouseId: string,
    sourceEstimateId: string | null
  ) => ["repair-tasks", "source-estimate", warehouseId, sourceEstimateId],
  repairTaskDetailQueryKey: (warehouseId: string, taskId: string | null) => [
    "repair-tasks",
    "detail",
    warehouseId,
    taskId,
  ],
  repairTasksListQueryKey: (warehouseId: string) => [
    "repair-tasks",
    "list",
    warehouseId,
  ],
  saveRepairTaskDraft: api.saveRepairTaskDraft,
  updateRepairTaskSubtasks: api.updateRepairTaskSubtasks,
  writeOffRepairDraft: api.writeOffRepairDraft,
}))

vi.mock("@/features/repair-estimates/repair-estimate-workspace-layout", () => ({
  RepairEstimateWorkspaceLayout: ({
    information,
    estimate,
    controls,
    catalogAction,
  }: {
    information: ReactNode
    estimate: ReactNode
    controls: ReactNode
    catalogAction?: ReactNode
  }) => (
    <section aria-label="Рабочая область обслуживания">
      {information}
      {estimate}
      {controls}
      {catalogAction}
    </section>
  ),
  RepairWorkDetailWorkspaceLayout: ({
    information,
    lowerAction,
    lowerContent,
  }: {
    information: ReactNode
    lowerAction?: ReactNode
    lowerContent: ReactNode
  }) => (
    <section>
      {information}
      {lowerAction}
      {lowerContent}
    </section>
  ),
}))

vi.mock("@/features/repair-estimates/repair-work-information-fields", () => ({
  RepairWorkInformationFields: ({
    readOnly,
    disabled,
    rentalItemId,
  }: {
    readOnly?: boolean
    disabled: boolean
    rentalItemId: string
  }) => (
    <>
      <p data-testid="information-access">
        {readOnly || disabled ? "read-only" : "editable"}
      </p>
      <p data-testid="selected-rental-item">{rentalItemId || "unselected"}</p>
    </>
  ),
}))

vi.mock("@/features/repair-estimates/repair-estimate-lines-editor", () => ({
  RepairEstimateLinesEditor: ({ readOnly }: { readOnly: boolean }) => (
    <p data-testid="lines-access">{readOnly ? "read-only" : "editable"}</p>
  ),
}))

vi.mock("@/features/repair-estimates/repair-estimate-catalog-picker", () => ({
  RepairEstimateCatalogPicker: ({ readOnly }: { readOnly: boolean }) => (
    <p data-testid="catalog-access">{readOnly ? "read-only" : "editable"}</p>
  ),
}))

vi.mock(
  "@/features/repair-estimates/repair-estimate-completion-dialog",
  () => ({ RepairEstimateCompletionDialog: () => null })
)

vi.mock("@/features/repair-estimates/repair-work-completion-dialog", () => ({
  RepairWorkCompletionDialog: () => null,
}))

vi.mock("@/features/repair-tasks/repair-task-write-off-dialog", () => ({
  RepairTaskWriteOffDialog: () => null,
}))

import { RepairEstimatesPage } from "@/features/repair-estimates/repair-estimates-page"
import { RepairsPage } from "@/features/repairs/repairs-page"

const estimate: RepairEstimateDto = {
  id: ESTIMATE_ID,
  version: 1,
  status: "DRAFT",
  warehouseId: WAREHOUSE_ID,
  rentalItemId: RENTAL_ITEM_ID,
  cabinNumber: "БЫТ-001",
  authorName: "operator-1",
  sourceParty: "Склад",
  dispatchDate: "2026-07-18",
  comment: "",
  totalAmount: "0.00",
  lines: [],
  media: [],
  completionMode: null,
  movementToRepair: null,
  forceCapitalRepair: false,
  taskPlans: [],
  createdAt: "2026-07-18T10:00:00Z",
  updatedAt: "2026-07-18T10:00:00Z",
}

const repair: RepairTaskDto = {
  id: REPAIR_ID,
  version: 1,
  status: "DRAFT",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "NOT_READY",
  startedAt: null,
  completedAt: null,
  warehouseId: WAREHOUSE_ID,
  rentalItemId: RENTAL_ITEM_ID,
  cabinNumber: "БЫТ-001",
  actorId: "operator-1",
  sourceParty: "Склад",
  dispatchDate: "2026-07-18",
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
  createdAt: "2026-07-18T10:00:00Z",
  updatedAt: "2026-07-18T10:00:00Z",
}

function renderPage(path: string, page: ReactNode, state?: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const [pathname, search = ""] = path.split("?", 2)

  return render(
    <MemoryRouter
      initialEntries={[
        {
          pathname,
          search: search ? `?${search}` : "",
          state,
        },
      ]}
    >
      <QueryClientProvider client={queryClient}>{page}</QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  authState.level = "VIEW"
  api.getRepairEstimate.mockResolvedValue(estimate)
  api.getRepairTask.mockResolvedValue(repair)
  api.getRepairTaskBySourceEstimateId.mockResolvedValue(null)
  api.listRepairEstimates.mockResolvedValue([])
  api.listRepairTasks.mockResolvedValue([])
  actorApi.listDossierActorDisplays.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("maintenance command access", () => {
  it("collapses estimate filters without resetting the search", async () => {
    const user = userEvent.setup()
    renderPage("/estimates", <RepairEstimatesPage />)

    const hideFilters = await screen.findByRole("button", {
      name: "Скрыть фильтры смет",
    })
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")
    expect(hideFilters.getAttribute("aria-controls")).toBe(
      "repair-estimate-filters"
    )

    const search = screen.getByRole("searchbox", {
      name: /Поиск по номеру бытовки/,
    }) as HTMLInputElement
    await user.type(search, "001")
    expect(search.value).toBe("001")

    await user.click(hideFilters)
    expect(
      screen
        .getByRole("button", { name: "Показать фильтры смет" })
        .getAttribute("aria-expanded")
    ).toBe("false")
    expect(document.getElementById("repair-estimate-filters")?.hidden).toBe(
      true
    )

    await user.click(
      screen.getByRole("button", { name: "Показать фильтры смет" })
    )
    expect(search.value).toBe("001")
  })

  it("hides create commands and keeps existing drafts read-only for VIEW", async () => {
    const estimatesList = renderPage("/estimates", <RepairEstimatesPage />)

    expect(screen.queryByRole("button", { name: "Создать смету" })).toBeNull()
    estimatesList.unmount()

    const repairsList = renderPage("/repairs", <RepairsPage />)
    expect(screen.queryByRole("button", { name: "Создать задание" })).toBeNull()
    repairsList.unmount()

    const estimateDraft = renderPage(
      `/estimates?estimateId=${ESTIMATE_ID}`,
      <RepairEstimatesPage />
    )
    expect((await screen.findByTestId("information-access")).textContent).toBe(
      "read-only"
    )
    expect(
      screen.queryByRole("button", { name: "Сохранить черновик" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Завершить" })).toBeNull()
    estimateDraft.unmount()

    renderPage(`/repairs?repairId=${REPAIR_ID}`, <RepairsPage />)
    expect((await screen.findByTestId("information-access")).textContent).toBe(
      "read-only"
    )
    expect(
      screen.queryByRole("button", { name: "Сохранить черновик" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Завершить" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Списать" })).toBeNull()
  })

  it("blocks direct create links for VIEW", () => {
    const estimateCreate = renderPage(
      "/estimates?create=1",
      <RepairEstimatesPage />
    )
    expect(screen.getByText("Создание сметы недоступно")).toBeTruthy()
    estimateCreate.unmount()

    renderPage("/repairs?create=1", <RepairsPage />)
    expect(screen.getByText("Создание ремонта недоступно")).toBeTruthy()
  })

  it("allows EDIT to create and update estimates and repairs", async () => {
    authState.level = "EDIT"
    const estimateCreate = renderPage(
      "/estimates?create=1",
      <RepairEstimatesPage />
    )
    expect(
      screen.getByRole("button", { name: "Сохранить черновик" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Завершить" })).toBeTruthy()
    estimateCreate.unmount()

    const estimateUpdate = renderPage(
      `/estimates?estimateId=${ESTIMATE_ID}`,
      <RepairEstimatesPage />
    )
    expect(
      await screen.findByRole("button", { name: "Сохранить черновик" })
    ).toBeTruthy()
    estimateUpdate.unmount()

    const repairCreate = renderPage("/repairs?create=1", <RepairsPage />)
    expect(
      screen.getByRole("button", { name: "Сохранить черновик" })
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Списать" })).toBeNull()
    repairCreate.unmount()

    renderPage(`/repairs?repairId=${REPAIR_ID}`, <RepairsPage />)
    expect(
      await screen.findByRole("button", { name: "Сохранить черновик" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Завершить" })).toBeTruthy()
  })

  it("allows EDIT to change a queued repair before work starts", async () => {
    authState.level = "EDIT"
    api.getRepairTask.mockResolvedValueOnce({
      ...repair,
      status: "QUEUED",
    })

    renderPage(`/repairs?repairId=${REPAIR_ID}&edit=1`, <RepairsPage />)

    expect(
      await screen.findByRole("button", { name: "Сохранить изменения" })
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Завершить" })).toBeNull()
  })

  it("allows EDIT to amend a completed estimate linked to a draft repair", async () => {
    authState.level = "EDIT"
    api.getRepairEstimate.mockResolvedValueOnce({
      ...estimate,
      status: "COMPLETED",
    })
    api.getRepairTaskBySourceEstimateId.mockResolvedValueOnce(repair)

    renderPage(`/estimates?estimateId=${ESTIMATE_ID}`, <RepairEstimatesPage />)

    expect(
      await screen.findByRole("button", { name: "Редактировать смету" })
    ).toBeTruthy()
  })

  it("preselects the rental item supplied by the warehouse card", () => {
    authState.level = "EDIT"

    renderPage("/repairs?create=1", <RepairsPage />, {
      workspaceEntry: true,
      rentalItemSeed: {
        type: "rental-item-repair-seed-v1",
        warehouseId: WAREHOUSE_ID,
        rentalItemId: RENTAL_ITEM_ID,
        number: "БЫТ-001",
      },
    })

    expect(screen.getByTestId("selected-rental-item").textContent).toBe(
      RENTAL_ITEM_ID
    )
  })

  it("does not use a rental item seed from another warehouse", () => {
    authState.level = "EDIT"

    renderPage("/repairs?create=1", <RepairsPage />, {
      workspaceEntry: true,
      rentalItemSeed: {
        type: "rental-item-repair-seed-v1",
        warehouseId: "55555555-5555-4555-8555-555555555555",
        rentalItemId: RENTAL_ITEM_ID,
        number: "БЫТ-001",
      },
    })

    expect(screen.getByTestId("selected-rental-item").textContent).toBe(
      "unselected"
    )
  })

  it("shows cabin write-off only for MANAGE", async () => {
    authState.level = "MANAGE"
    renderPage(`/repairs?repairId=${REPAIR_ID}`, <RepairsPage />)

    expect(await screen.findByRole("button", { name: "Списать" })).toBeTruthy()
  })
})
