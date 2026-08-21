import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  InventoryFindingDto,
  InventoryFurnitureReviewDto,
  InventorySessionDto,
  InventoryStatisticsDto,
} from "@/features/inventory/model/inventory"
import type { OutcomeRecalculation } from "@/features/inventory/model/inventory-service"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { ApiError } from "@/lib/api-client"

const inventoryApi = vi.hoisted(() => ({
  cancelInventory: vi.fn(),
  completeInventory: vi.fn(),
  getInventoryFinalPlan: vi.fn(),
  getInventoryFurnitureReview: vi.fn(),
  getInventoryPlanningSettings: vi.fn(),
  getInventoryPreliminaryStatistics: vi.fn(),
  getInventory: vi.fn(),
  prepareInventoryFinalPlan: vi.fn(),
  previewInventoryCompletion: vi.fn(),
  recalculateInventoryOutcome: vi.fn(),
  refreshInventorySession: vi.fn(),
  reviewInventoryRegistry: vi.fn(),
  resolveInventoryFindingConflict: vi.fn(),
  saveInventoryFurnitureReview: vi.fn(),
  saveInventoryFinalPlan: vi.fn(),
  saveInventoryFinding: vi.fn(),
  startInventoryFurnitureReview: vi.fn(),
  subscribeInventory: vi.fn(() => () => undefined),
}))
const inspectionWorkspace = vi.hoisted(() => ({
  readyReferences: [] as Array<{ mediaId: string; generation: number }>,
}))
const toast = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
}))
const auth = vi.hoisted(() => ({ useAuth: vi.fn() }))
const warehouse = vi.hoisted(() => ({ useWarehouse: vi.fn() }))
const viewport = vi.hoisted(() => ({ isMobile: false }))
const queueCapabilities = vi.hoisted(() => ({ get: vi.fn() }))
const assetApi = vi.hoisted(() => ({ getRentalItemCreationOptions: vi.fn() }))

vi.mock("sonner", () => ({ toast }))
vi.mock("@/features/auth/use-auth", () => ({ useAuth: auth.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: warehouse.useWarehouse,
}))
vi.mock("@/features/inventory/api/inventory-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/inventory/api/inventory-api")
  >("@/features/inventory/api/inventory-api")

  return {
    ...actual,
    cancelInventory: inventoryApi.cancelInventory,
    getInventory: inventoryApi.getInventory,
    getInventoryPreliminaryStatistics:
      inventoryApi.getInventoryPreliminaryStatistics,
    completeInventory: inventoryApi.completeInventory,
    getInventoryFinalPlan: inventoryApi.getInventoryFinalPlan,
    getInventoryFurnitureReview: inventoryApi.getInventoryFurnitureReview,
    getInventoryPlanningSettings: inventoryApi.getInventoryPlanningSettings,
    prepareInventoryFinalPlan: inventoryApi.prepareInventoryFinalPlan,
    previewInventoryCompletion: inventoryApi.previewInventoryCompletion,
    recalculateInventoryOutcome: inventoryApi.recalculateInventoryOutcome,
    refreshInventorySession: inventoryApi.refreshInventorySession,
    reviewInventoryRegistry: inventoryApi.reviewInventoryRegistry,
    resolveInventoryFindingConflict:
      inventoryApi.resolveInventoryFindingConflict,
    saveInventoryFurnitureReview: inventoryApi.saveInventoryFurnitureReview,
    saveInventoryFinalPlan: inventoryApi.saveInventoryFinalPlan,
    saveInventoryFinding: inventoryApi.saveInventoryFinding,
    startInventoryFurnitureReview: inventoryApi.startInventoryFurnitureReview,
    subscribeInventory: inventoryApi.subscribeInventory,
  }
})
vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => viewport.isMobile,
}))
vi.mock("@/features/repair-estimates/api/warehouse-queue-capabilities", () => ({
  warehouseQueueCapabilitiesQueryKey: (warehouseId: string) => [
    "task-board",
    warehouseId,
    "queue-capabilities",
  ],
  getWarehouseQueueCapabilities: queueCapabilities.get,
}))
vi.mock("@/features/rental-items/api/asset-rental-items-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/rental-items/api/asset-rental-items-api")
  >("@/features/rental-items/api/asset-rental-items-api")

  return {
    ...actual,
    getRentalItemCreationOptions: assetApi.getRentalItemCreationOptions,
  }
})
vi.mock("@/features/inventory/inventory-inspection-workspace", () => ({
  InventoryInspectionWorkspace: ({
    comment,
    onCommentChange,
    onMediaChange,
    onMediaReadyChange,
    onCoverMediaIdChange,
  }: {
    comment: string
    onCommentChange: (value: string) => void
    onMediaChange: (
      references: Array<{ mediaId: string; generation: number }>
    ) => void
    onMediaReadyChange: (ready: boolean) => void
    onCoverMediaIdChange: (mediaId: string | null) => void
  }) => (
    <section aria-label="Редактор осмотра">
      <label>
        Комментарий
        <input
          value={comment}
          onChange={(event) => onCommentChange(event.target.value)}
        />
      </label>
      <button
        type="button"
        onClick={() => {
          onMediaChange(inspectionWorkspace.readyReferences)
          onCoverMediaIdChange(
            inspectionWorkspace.readyReferences[0]?.mediaId ?? null
          )
          onMediaReadyChange(true)
        }}
      >
        Обновить готовые фото
      </button>
    </section>
  ),
}))
vi.mock("@/features/repair-estimates/repair-work-completion-dialog", () => ({
  RepairWorkCompletionDialog: ({
    open,
    movementRouteAvailable,
    initialCompletionMode,
    initialTaskPlans,
    onComplete,
  }: {
    open: boolean
    movementRouteAvailable: boolean
    initialCompletionMode?: RepairEstimateCompletionMode
    initialTaskPlans?: RepairEstimateTaskPlanDto[]
    onComplete: (result: {
      completionMode: RepairEstimateCompletionMode
      movementToRepair: boolean
      logisticsPlanningMode: "FIXED_DATE"
      logisticsScheduledDate: string
      taskPlans: RepairEstimateTaskPlanDto[]
      priority: 3
    }) => void
  }) =>
    open ? (
      <section aria-label="Настройка работ по осмотру">
        {movementRouteAvailable ? (
          <label>
            <input
              type="checkbox"
              aria-label="Создать перемещение на ремонт"
              defaultChecked
            />
            Создать перемещение на ремонт
          </label>
        ) : null}
        <button
          type="button"
          onClick={() =>
            onComplete({
              completionMode: initialCompletionMode ?? "AUTO",
              movementToRepair: true,
              logisticsPlanningMode: "FIXED_DATE",
              logisticsScheduledDate: "2026-08-12",
              taskPlans: initialTaskPlans ?? [],
              priority: 3,
            })
          }
        >
          Подтвердить работы
        </button>
      </section>
    ) : null,
}))

import {
  InventoryFinishPage,
  InventoryHistoryDetailPage,
  InventorySessionPage,
} from "@/features/inventory/inventory-pages"
import { inventoryPublicationQueryKey } from "@/features/inventory/api/inventory-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const INVENTORY_ID = "22222222-2222-4222-8222-222222222222"
const FINDING_ID = "33333333-3333-4333-8333-333333333333"

const workLine: RepairEstimateLineDto = {
  id: "77777777-7777-4777-8777-777777777777",
  sourceLineKey: "inventory-work",
  lineType: "WORK",
  description: "Заменить дверь",
  lineComment: "",
  unit: "шт.",
  quantity: 1,
  normativeMinutes: 60,
  unitPrice: "100.00",
  lineTotal: "100.00",
  catalogSnapshot: {
    nodeId: "88888888-8888-4888-8888-888888888888",
    name: "Заменить дверь",
    nodeType: "WORK",
    furnitureEquipment: null,
    characteristic: null,
  },
  customQueueBinding: null,
}

const finishStatistics: InventoryStatisticsDto = {
  durationSeconds: 3_600,
  expectedCount: 1,
  inspectedCount: 1,
  missingCount: 0,
  readyCount: 1,
  withWorkCount: 1,
  addedCount: 0,
  conflictCount: 0,
  workLineCount: 1,
  materialLineCount: 0,
  plannedDurationMinutes: 60,
  workTotal: "100.00",
  materialTotal: "0.00",
  grandTotal: "100.00",
  aggregates: [],
}

const currentUser: CurrentUser = {
  id: "44444444-4444-4444-8444-444444444444",
  username: "inventory-user",
  displayName: "Инвентаризатор",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WAREHOUSE_MANAGER",
  rentalAccess: false,
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
}

function finding(
  status: "AFTER_RENT" | "FREE",
  overrides: Partial<InventoryFindingDto> = {}
): InventoryFindingDto {
  return {
    id: FINDING_ID,
    version: 1,
    rentalItemId: "55555555-5555-4555-855555555555",
    canonicalNumber: "БЫТ-001",
    cabinNumber: "БЫТ-001",
    origin: "EXPECTED",
    inspectionStatus: "NOT_INSPECTED",
    reconciliationStatus: "MATCHED",
    expectedSnapshot: {
      rentalItemId: "55555555-5555-4555-855555555555",
      number: "БЫТ-001",
      canonicalNumber: "БЫТ-001",
      warehouseId: WAREHOUSE_ID,
      status: "FREE",
      tenant: null,
      passportSnapshot: {},
      contentsSnapshot: [],
      repairsSnapshot: [],
    },
    currentSnapshot: {
      rentalItemId: "55555555-5555-4555-855555555555",
      number: "БЫТ-001",
      canonicalNumber: "БЫТ-001",
      warehouseId: WAREHOUSE_ID,
      status,
      tenant: null,
      passportSnapshot: {},
      contentsSnapshot: [],
      repairsSnapshot: [],
    },
    inspectionBaseline: null,
    conflictResolution: null,
    conflicts: [],
    comment: "",
    passportObservation: { presence: "ABSENT", value: null },
    equipmentObservation: { presence: "EXPLICIT_EMPTY", value: [] },
    media: [],
    coverMediaId: null,
    inspectionSource: null,
    lines: [],
    repairCompletionMode: null,
    repairPriority: 3,
    movementToRepair: false,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    repairPlans: [],
    publicationStatus: "NOT_REQUIRED",
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    desiredAssetStatus: null,
    publicationError: null,
    ...overrides,
  }
}

function activeSession(
  selectedFinding: InventoryFindingDto,
  overrides: Partial<InventorySessionDto> = {}
): InventorySessionDto {
  const findings = overrides.findings ?? [selectedFinding]
  return {
    id: INVENTORY_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    status: "ACTIVE",
    warehouse: {
      id: WAREHOUSE_ID,
      name: "Склад СПБ",
      timeZone: "Europe/Moscow",
    },
    author: {
      id: currentUser.id,
      displayName: currentUser.displayName,
      permissions: ["VIEW", "EDIT", "MANAGE"],
      authorizedWarehouseIds: [WAREHOUSE_ID],
    },
    businessDate: "2026-07-27",
    startedAt: "2026-07-27T08:00:00Z",
    completedAt: null,
    findingCount: findings.length,
    inspectedCount: 0,
    findings,
    membershipMovements: [],
    statistics: null,
    publicationStatus: "NOT_REQUESTED",
    reviewStage: "CABINS",
    furnitureReconciliationState: "NOT_REQUIRED",
    ...overrides,
    cancellation: overrides.cancellation ?? null,
  }
}

function furnitureReview(
  overrides: Partial<InventoryFurnitureReviewDto> = {}
): InventoryFurnitureReviewDto {
  return {
    inventoryId: INVENTORY_ID,
    sessionRevision: 2,
    stage: "FURNITURE",
    assetSnapshotSha256: "a".repeat(64),
    reviewSha256: null,
    confirmed: false,
    items: [
      {
        equipmentId: "77777777-7777-4777-8777-777777777777",
        catalogVersion: 888,
        equipmentName: "Стол",
        currentStockQuantity: 2,
        observedStockQuantity: 2,
        cabins: [
          {
            findingId: FINDING_ID,
            assetId: "55555555-5555-4555-8555-555555555555",
            cabinNumber: "БЫТ-001",
            status: "WAREHOUSE",
            currentQuantity: 1,
            observedQuantity: 1,
          },
        ],
      },
    ],
    ...overrides,
  }
}

const FINAL_PLAN_SHA256 = "c".repeat(64)

function finalPlan(sessionRevision = 2) {
  return {
    inventoryId: INVENTORY_ID,
    sessionRevision,
    finalPlanVersion: 1,
    finalPlanSha256: FINAL_PLAN_SHA256,
    planningSettingsRevision: 1,
    state: "DRAFT" as const,
    movementScheduleMode: "AUTO" as const,
    repairScheduleMode: "AUTO" as const,
    entries: [
      {
        findingId: FINDING_ID,
        findingRevision: 1,
        planFingerprintSha256: null,
        targetKind: null,
        hasWork: false,
        order: 0,
        priority: null,
        movementToRepair: false,
        movementScheduledDate: null,
        repairScheduledDate: null,
        collisionCandidates: [],
        reconciliationDecision: null,
        forceCapitalRepair: false,
      },
    ],
  }
}

function completedFinalPlan(sessionRevision = 2) {
  return { ...finalPlan(sessionRevision), state: "COMPLETED" as const }
}

function outcomeRecalculation(
  overrides: Partial<OutcomeRecalculation> = {}
): OutcomeRecalculation {
  return {
    inventoryId: INVENTORY_ID,
    sessionRevision: 3,
    finalPlanVersion: 1,
    finalPlanSha256: FINAL_PLAN_SHA256,
    furnitureReconciliationState: "PENDING" as const,
    createdPublicationCount: 75,
    requeuedPublicationCount: 141,
    preservedSucceededPublicationCount: 0,
    publicationBatch: {
      inventoryId: INVENTORY_ID,
      aggregateState: "PENDING" as const,
      intents: [
        {
          id: "99999999-9999-4999-8999-999999999999",
          inventoryId: INVENTORY_ID,
          findingId: FINDING_ID,
          publicationRevision: 2,
          state: "READY" as const,
          sourceRevision: 1,
          attemptCount: 1,
          maintenanceRepairId: null,
          desiredAssetStatus: "REPAIR" as const,
          failureCode: null,
        },
      ],
    },
    ...overrides,
  }
}

function completionEvidence(sessionRevision = 2) {
  return {
    inventoryId: INVENTORY_ID,
    sessionRevision,
    finalPlanVersion: 1,
    finalPlanSha256: FINAL_PLAN_SHA256,
    findingRevisions: [{ findingId: FINDING_ID, expectedFindingRevision: 1 }],
    validationSha256: "d".repeat(64),
    validatedAt: "2026-07-27T08:30:00Z",
    acknowledgementSha256: "e".repeat(64),
    statistics: {},
    risks: [],
    validatedFindings: [],
  }
}

function completionReview(session: InventorySessionDto) {
  return {
    ...session,
    completionEvidence: completionEvidence(session.version),
  }
}

function renderPage(
  initialEntry = `/inventory/${INVENTORY_ID}?findingId=${FINDING_ID}`
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const rendered = render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/inventory/:inventoryId"
            element={<InventorySessionPage />}
          />
          <Route
            path="/inventory/:inventoryId/finish"
            element={<InventoryFinishPage />}
          />
          <Route
            path="/inventory/history/:inventoryId"
            element={<InventoryHistoryDetailPage />}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
  return { ...rendered, queryClient }
}

beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    configurable: true,
    value: vi.fn(),
    writable: true,
  })
  auth.useAuth.mockReturnValue({
    accessToken: "inventory-token",
    currentUser,
  })
  warehouse.useWarehouse.mockReturnValue({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      name: "Склад СПБ",
      timeZone: "Europe/Moscow",
    },
  })
  inspectionWorkspace.readyReferences = []
  viewport.isMobile = false
  inventoryApi.saveInventoryFinding.mockResolvedValue(finding("AFTER_RENT"))
  inventoryApi.getInventoryPreliminaryStatistics.mockResolvedValue(null)
  inventoryApi.getInventoryPlanningSettings.mockResolvedValue({
    warehouseId: WAREHOUSE_ID,
    settingsRevision: 1,
    movementDailyCapacity: 6,
    repairDailyCapacity: 6,
    workingWeekdays: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    holidays: [],
  })
  inventoryApi.getInventoryFinalPlan.mockResolvedValue(finalPlan())
  inventoryApi.prepareInventoryFinalPlan.mockResolvedValue(finalPlan())
  inventoryApi.saveInventoryFinalPlan.mockResolvedValue(finalPlan())
  queueCapabilities.get.mockResolvedValue({
    warehouseId: WAREHOUSE_ID,
    movementQueueDefinitions: [],
  })
  assetApi.getRentalItemCreationOptions.mockResolvedValue({
    newCategory: "Новая",
    usedCategories: ["Обычная"],
    rentalTypes: [{ id: "type-1", name: "БК-1" }],
    dimensions: [{ id: "dimension-1", name: "2.4×6" }],
    finishings: [{ id: "finishing-1", name: "ДВП" }],
    categories: [{ id: "category-1", name: "Новая" }],
    characteristics: [],
    typeDimensions: [
      { typeId: "type-1", dimensionId: "dimension-1", sortOrder: 0 },
    ],
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("InventorySessionPage inspection", () => {
  it("requires a reason, cancels by exact revision and opens the audit history", async () => {
    const user = userEvent.setup()
    const active = activeSession(finding("FREE"), { version: 7 })
    const cancelledAt = "2026-07-27T09:00:00Z"
    const cancelled = activeSession(finding("FREE"), {
      version: 8,
      status: "CANCELLED",
      completedAt: cancelledAt,
      cancellation: {
        reason: "Ошибочно выбран склад",
        cancelledAt,
      },
    })
    inventoryApi.getInventory
      .mockResolvedValueOnce(active)
      .mockResolvedValue(cancelled)
    inventoryApi.cancelInventory.mockResolvedValue(cancelled)

    renderPage(`/inventory/${INVENTORY_ID}`)

    await user.click(await screen.findByRole("button", { name: "Отменить" }))
    const dialog = await screen.findByRole("dialog", {
      name: "Отменить инвентаризацию?",
    })
    await user.click(
      within(dialog).getByRole("button", {
        name: "Отменить инвентаризацию",
      })
    )
    expect(within(dialog).getByText("Укажите причину отмены.")).toBeTruthy()
    expect(inventoryApi.cancelInventory).not.toHaveBeenCalled()

    await user.type(
      within(dialog).getByRole("textbox", { name: "Причина отмены" }),
      "Ошибочно выбран склад"
    )
    await user.click(
      within(dialog).getByRole("button", {
        name: "Отменить инвентаризацию",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.cancelInventory).toHaveBeenCalledWith({
        inventoryId: INVENTORY_ID,
        expectedVersion: 7,
        reason: "Ошибочно выбран склад",
      })
    )
    expect(
      await screen.findByText("Инвентаризация отменена", {
        selector: "[data-slot='badge']",
      })
    ).toBeTruthy()
    expect(screen.getByText("Ошибочно выбран склад")).toBeTruthy()
    expect(
      screen.queryByRole("button", {
        name: "Пересчитать и применить итоги",
      })
    ).toBeNull()
  })

  it("requires a ready photo to accept an empty after-rent estimate", async () => {
    const user = userEvent.setup()
    inventoryApi.getInventory.mockResolvedValue(
      activeSession(finding("AFTER_RENT"))
    )

    renderPage()

    const accept = (await screen.findByRole("button", {
      name: "Принять бытовку",
    })) as HTMLButtonElement
    expect(accept.disabled).toBe(true)
    expect(
      screen.getByText(
        "Чтобы принять бытовку после аренды, добавьте хотя бы одну готовую фотографию."
      )
    ).toBeTruthy()

    await user.click(
      screen.getByRole("button", { name: "Обновить готовые фото" })
    )
    expect(accept.disabled).toBe(true)

    inspectionWorkspace.readyReferences = [
      {
        mediaId: "66666666-6666-4666-8666-666666666666",
        generation: 1,
      },
    ]
    await user.click(
      screen.getByRole("button", { name: "Обновить готовые фото" })
    )

    await waitFor(() => expect(accept.disabled).toBe(false))
    expect(
      screen.queryByText(
        "Чтобы принять бытовку после аренды, добавьте хотя бы одну готовую фотографию."
      )
    ).toBeNull()

    await user.click(accept)
    await waitFor(() =>
      expect(inventoryApi.saveInventoryFinding).toHaveBeenCalledWith(
        expect.objectContaining({
          inventoryId: INVENTORY_ID,
          findingId: FINDING_ID,
          lines: [],
          media: inspectionWorkspace.readyReferences,
          coverMediaId: inspectionWorkspace.readyReferences[0]?.mediaId,
        })
      )
    )
  })

  it("keeps the existing empty-inspection action for statuses other than AFTER_RENT", async () => {
    const user = userEvent.setup()
    inventoryApi.getInventory.mockResolvedValue(activeSession(finding("FREE")))

    renderPage()

    const save = (await screen.findByRole("button", {
      name: "Сохранить осмотр",
    })) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    expect(
      screen.queryByText(
        "Чтобы принять бытовку после аренды, добавьте хотя бы одну готовую фотографию."
      )
    ).toBeNull()

    await user.click(
      screen.getByRole("button", { name: "Обновить готовые фото" })
    )
    await waitFor(() => expect(save.disabled).toBe(false))
  })

  it("hides inventory movement and clamps a programmatic true when the warehouse capability is absent", async () => {
    const user = userEvent.setup()
    inventoryApi.getInventory.mockResolvedValue(
      activeSession(finding("FREE", { lines: [workLine] }))
    )

    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Обновить готовые фото" })
    )
    const save = screen.getByRole("button", { name: "Сохранить осмотр" })
    await waitFor(() =>
      expect((save as HTMLButtonElement).disabled).toBe(false)
    )
    await user.click(save)

    expect(
      await screen.findByRole("region", {
        name: "Настройка работ по осмотру",
      })
    ).toBeTruthy()
    expect(
      screen.queryByRole("checkbox", { name: "Перемещение на отгрузку" })
    ).toBeNull()

    await user.click(screen.getByRole("button", { name: "Подтвердить работы" }))

    await waitFor(() =>
      expect(inventoryApi.saveInventoryFinding).toHaveBeenCalledWith(
        expect.objectContaining({
          movementToRepair: false,
          logisticsPlanningMode: undefined,
          logisticsScheduledDate: null,
        })
      )
    )
  })

  it("asks about furniture before saving an inspection without a furniture observation", async () => {
    const user = userEvent.setup()
    inventoryApi.getInventory.mockResolvedValue(
      activeSession(
        finding("FREE", {
          equipmentObservation: { presence: "ABSENT", value: null },
        })
      )
    )

    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Обновить готовые фото" })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить осмотр" }))

    expect(
      await screen.findByRole("dialog", { name: "Мебель в бытовке" })
    ).toBeTruthy()
    expect(inventoryApi.saveInventoryFinding).not.toHaveBeenCalled()

    await user.click(screen.getByRole("button", { name: "Мебели нет" }))

    await waitFor(() =>
      expect(inventoryApi.saveInventoryFinding).toHaveBeenCalledWith(
        expect.objectContaining({
          equipmentObservation: { presence: "EXPLICIT_EMPTY", value: [] },
        })
      )
    )
  })

  it("shows and preserves inventory movement when the current warehouse capability is available", async () => {
    const existingPlan = {
      id: "88888888-8888-4888-8888-888888888888",
      kind: "REPAIR_WORK" as const,
      includedLineIds: [workLine.id],
      primaryLineId: workLine.id,
      groupComment: "Сохранённая ручная группа",
      queueId: "repair-queue",
      routingCatalogNodeId: "catalog-work-1",
      queueName: "Ремонт",
      routeQueueKind: "REPAIR" as const,
      sortOrder: 10,
      plannedDurationMinutes: 45,
      photoRequired: true,
    }
    queueCapabilities.get.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      movementQueueDefinitions: [
        {
          queueDefinitionId: "movement-definition",
          workQueueId: "warehouse-movement-queue",
        },
      ],
    })
    const user = userEvent.setup()
    inventoryApi.getInventory.mockResolvedValue(
      activeSession(
        finding("FREE", {
          lines: [workLine],
          repairCompletionMode: "MANUAL",
          repairPlans: [existingPlan],
        })
      )
    )

    renderPage()

    await waitFor(() =>
      expect(queueCapabilities.get).toHaveBeenCalledWith(
        "inventory-token",
        WAREHOUSE_ID
      )
    )
    await user.click(
      screen.getByRole("button", { name: "Обновить готовые фото" })
    )
    const save = screen.getByRole("button", { name: "Сохранить осмотр" })
    await waitFor(() =>
      expect((save as HTMLButtonElement).disabled).toBe(false)
    )
    await user.click(save)

    expect(
      await screen.findByRole("checkbox", {
        name: "Создать перемещение на ремонт",
      })
    ).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Подтвердить работы" }))

    await waitFor(() =>
      expect(inventoryApi.saveInventoryFinding).toHaveBeenCalledWith(
        expect.objectContaining({
          repairCompletionMode: "MANUAL",
          repairPlans: [existingPlan],
          movementToRepair: true,
          logisticsPlanningMode: "FIXED_DATE",
          logisticsScheduledDate: "2026-08-12",
          priority: 3,
        })
      )
    )
  })

  it("polls active sessions and preserves an open inspection draft across membership updates", async () => {
    const user = userEvent.setup()
    const initialFinding = finding("FREE")
    const arrivingFinding = {
      ...finding("FREE"),
      id: "77777777-7777-4777-8777-777777777777",
      cabinNumber: "БЫТ-002",
      canonicalNumber: "БЫТ-002",
    }
    inventoryApi.getInventory
      .mockResolvedValueOnce(activeSession(initialFinding))
      .mockResolvedValue(
        activeSession(initialFinding, {
          version: 2,
          findings: [initialFinding, arrivingFinding],
        })
      )

    renderPage()

    const comment = (await screen.findByRole("textbox", {
      name: "Комментарий",
    })) as HTMLInputElement
    await user.type(comment, "Черновик осмотра")

    await waitFor(
      () => expect(inventoryApi.getInventory).toHaveBeenCalledTimes(2),
      { timeout: 6_500 }
    )

    expect(comment.value).toBe("Черновик осмотра")
    await user.click(
      screen.getByRole("button", { name: "Обновить готовые фото" })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить осмотр" }))
    await waitFor(() =>
      expect(inventoryApi.saveInventoryFinding).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedFindingVersion: initialFinding.version,
          comment: "Черновик осмотра",
        })
      )
    )
  }, 8_000)
})

describe("Inventory mobile restrictions", () => {
  it("shows the app warning instead of opening a cabin inspection", async () => {
    const user = userEvent.setup()
    viewport.isMobile = true
    inventoryApi.getInventory.mockResolvedValue(
      activeSession(finding("FREE", { inspectionStatus: "READY" }))
    )

    renderPage(`/inventory/${INVENTORY_ID}`)

    await user.click(
      (await screen.findAllByRole("button", { name: "Открыть" }))[0]
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Осмотр бытовки доступно в мобильном приложении",
    })
    expect(
      within(dialog)
        .getByRole("link", { name: "Скачать приложение" })
        .getAttribute("href")
    ).toBe("/downloads/rwms-manager-app-debug.apk")
    expect(
      screen.queryByRole("region", { name: "Редактор осмотра" })
    ).toBeNull()
  })

  it("opens the add-cabin dialog on mobile", async () => {
    const user = userEvent.setup()
    viewport.isMobile = true
    inventoryApi.getInventory.mockResolvedValue(activeSession(finding("FREE")))

    renderPage(`/inventory/${INVENTORY_ID}`)

    await user.click(
      await screen.findByRole("button", { name: "Добавить бытовку" })
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Добавить бытовку",
    })
    expect(
      within(dialog).getByText(
        "Введите точный номер. Поиск повторно проверит весь реестр."
      )
    ).toBeTruthy()
    expect(
      screen.queryByRole("dialog", {
        name: "Добавление бытовки доступно в мобильном приложении",
      })
    ).toBeNull()
  })

  it("blocks a direct cabin-inspection link and returns to the session list", async () => {
    const user = userEvent.setup()
    viewport.isMobile = true
    inventoryApi.getInventory.mockResolvedValue(activeSession(finding("FREE")))

    renderPage(`/inventory/${INVENTORY_ID}?findingId=${FINDING_ID}`)

    const dialog = await screen.findByRole("dialog", {
      name: "Осмотр бытовки доступно в мобильном приложении",
    })
    expect(
      screen.queryByRole("region", { name: "Редактор осмотра" })
    ).toBeNull()

    await user.click(within(dialog).getByRole("button", { name: "Понятно" }))

    expect(
      await screen.findByRole("button", { name: "Добавить бытовку" })
    ).toBeTruthy()
  })

  it("keeps filters, add and completion actions in one non-wrapping toolbar row", async () => {
    inventoryApi.getInventory.mockResolvedValue(activeSession(finding("FREE")))

    renderPage(`/inventory/${INVENTORY_ID}`)

    await screen.findByRole("button", { name: "Добавить бытовку" })
    const actions = document.querySelector('[data-slot="page-toolbar-actions"]')
    expect(actions?.className).toContain("flex-nowrap")
    expect(
      within(actions as HTMLElement)
        .getAllByRole("button")
        .map((button) => button.textContent?.trim())
    ).toEqual(["", "Добавить бытовку", "Отменить", "Закончить"])
  })
})

describe("InventorySessionPage filters", () => {
  it("filters findings and keeps the detailed filter state when the panel is hidden", async () => {
    const user = userEvent.setup()
    const firstFinding = finding("FREE")
    const secondFinding = {
      ...finding("FREE"),
      id: "77777777-7777-4777-8777-777777777777",
      cabinNumber: "БЫТ-002",
      canonicalNumber: "БЫТ-002",
    }
    inventoryApi.getInventory.mockResolvedValue(
      activeSession(firstFinding, {
        findings: [firstFinding, secondFinding],
      })
    )

    renderPage(`/inventory/${INVENTORY_ID}`)

    await screen.findAllByText("БЫТ-001")
    const filterLabels = [
      "Источник",
      "Статус",
      "Сверка",
      "Осмотр",
      "Добавление",
      "Наличие",
      "Работы",
    ]
    const filtersPanel = document.getElementById("inventory-finding-filters")
    expect(filtersPanel).not.toBeNull()
    filterLabels.forEach((label) => {
      expect(
        within(filtersPanel as HTMLElement).getByRole("button", {
          name: label,
        })
      ).toBeTruthy()
    })
    const numberFilter = screen.getByRole("textbox", {
      name: "Номер бытовки",
    }) as HTMLInputElement
    await user.type(numberFilter, "002")

    await waitFor(() => expect(screen.queryByText("БЫТ-001")).toBeNull())
    expect(screen.getAllByText("БЫТ-002")).toHaveLength(2)

    const hideFilters = screen.getByRole("button", {
      name: "Скрыть фильтры инвентаризации",
    })
    expect(hideFilters.getAttribute("aria-controls")).toBe(
      "inventory-finding-filters"
    )
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")

    await user.click(hideFilters)

    expect(document.getElementById("inventory-finding-filters")?.hidden).toBe(
      true
    )
    const showFilters = screen.getByRole("button", {
      name: "Показать фильтры инвентаризации",
    })
    expect(showFilters.getAttribute("aria-expanded")).toBe("false")

    await user.click(showFilters)

    expect(
      (
        screen.getByRole("textbox", {
          name: "Номер бытовки",
        }) as HTMLInputElement
      ).value
    ).toBe("002")
  })
})

describe("InventoryFinishPage conflict resolution", () => {
  function conflictFinding(overrides: Partial<InventoryFindingDto> = {}) {
    const base = finding("FREE")
    return {
      ...base,
      version: 6,
      inspectionStatus: "READY" as const,
      reconciliationStatus: "CONFLICT" as const,
      inspectionBaseline: {
        ...base.currentSnapshot!,
        status: "AFTER_RENT" as const,
      },
      conflicts: [
        {
          code: "STATUS_CHANGED" as const,
          message: "Статус бытовки изменился",
          expected: "Ожидает осмотра",
          actual: "Свободна",
        },
      ],
      ...overrides,
    }
  }

  it("moves from cabin review without a completion preview and sends the incomplete acknowledgement", async () => {
    const user = userEvent.setup()
    const reviewed = activeSession(finding("FREE"), { version: 8 })
    inventoryApi.getInventory.mockResolvedValue(reviewed)
    inventoryApi.startInventoryFurnitureReview.mockResolvedValue(
      furnitureReview()
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    const transition = (await screen.findByRole("button", {
      name: "Завершить проверку бытовок и перейти к мебели",
    })) as HTMLButtonElement
    expect(transition.disabled).toBe(true)
    expect(
      screen.queryByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    ).toBeNull()

    await user.click(screen.getByRole("checkbox"))
    await user.click(transition)

    await waitFor(() =>
      expect(inventoryApi.startInventoryFurnitureReview).toHaveBeenCalledWith({
        session: reviewed,
        acknowledgeIncomplete: true,
      })
    )
    expect(inventoryApi.previewInventoryCompletion).not.toHaveBeenCalled()
  })

  it("does not acknowledge incomplete cabins when every cabin has been inspected", async () => {
    const user = userEvent.setup()
    const reviewed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      { version: 8 }
    )
    inventoryApi.getInventory.mockResolvedValue(reviewed)
    inventoryApi.startInventoryFurnitureReview.mockResolvedValue(
      furnitureReview()
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", {
        name: "Завершить проверку бытовок и перейти к мебели",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.startInventoryFurnitureReview).toHaveBeenCalledWith({
        session: reviewed,
        acknowledgeIncomplete: false,
      })
    )
  })

  it("does not request cabin statistics-preview after moving from CABINS to FURNITURE", async () => {
    const user = userEvent.setup()
    const cabins = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      { version: 8 }
    )
    const furniture = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 9,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    const review = furnitureReview({ sessionRevision: furniture.version })
    inventoryApi.getInventory
      .mockResolvedValueOnce(cabins)
      .mockResolvedValue(furniture)
    inventoryApi.getInventoryPreliminaryStatistics.mockResolvedValue(
      finishStatistics
    )
    inventoryApi.startInventoryFurnitureReview.mockResolvedValue(review)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(review)

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await waitFor(() =>
      expect(
        inventoryApi.getInventoryPreliminaryStatistics
      ).toHaveBeenCalledTimes(1)
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Завершить проверку бытовок и перейти к мебели",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.getInventory).toHaveBeenCalledTimes(2)
    )
    expect(await screen.findByText("Сверка мебели")).toBeTruthy()
    expect(
      inventoryApi.getInventoryPreliminaryStatistics
    ).toHaveBeenCalledTimes(1)
  })

  it("opens a stale-data dialog and moves to the refreshed conflict resolution section", async () => {
    const user = userEvent.setup()
    const secondFindingId = "66666666-6666-4666-8666-666666666666"
    const firstInitialFinding = finding("FREE", {
      inspectionStatus: "READY",
      version: 6,
    })
    const secondInitialFinding = finding("FREE", {
      id: secondFindingId,
      canonicalNumber: "БЫТ-002",
      cabinNumber: "БЫТ-002",
      inspectionStatus: "READY",
      version: 6,
    })
    const reviewed = activeSession(firstInitialFinding, {
      version: 8,
      findings: [firstInitialFinding, secondInitialFinding],
    })
    const firstConflict = conflictFinding()
    const secondConflict = conflictFinding({
      id: secondFindingId,
      canonicalNumber: "БЫТ-002",
      cabinNumber: "БЫТ-002",
    })
    const persisted = activeSession(firstConflict, {
      version: 9,
      findings: [firstConflict, secondInitialFinding],
    })
    const refreshed = activeSession(firstConflict, {
      version: 9,
      findings: [firstConflict, secondConflict],
    })
    inventoryApi.getInventory
      .mockResolvedValueOnce(reviewed)
      .mockResolvedValue(persisted)
    inventoryApi.startInventoryFurnitureReview.mockRejectedValueOnce(
      new ApiError("Версия инвентаризации изменилась", 409)
    )
    inventoryApi.reviewInventoryRegistry.mockResolvedValue(refreshed)

    const { queryClient } = renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", {
        name: "Завершить проверку бытовок и перейти к мебели",
      })
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Данные инвентаризации изменились",
    })
    expect(
      within(dialog).getByText(
        /Перед переходом к мебели сравните данные и разрешите конфликты/
      )
    ).toBeTruthy()
    await user.click(
      within(dialog).getByRole("button", {
        name: "Перейти к разрешению конфликтов",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.reviewInventoryRegistry).toHaveBeenCalledWith(
        INVENTORY_ID
      )
    )
    const conflictSection = (
      await screen.findByRole("heading", { name: "Конфликты реестра" })
    ).closest("section")!
    expect(within(conflictSection).getByText("БЫТ-002")).toBeTruthy()
    await waitFor(() =>
      expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalledWith({
        behavior: "smooth",
        block: "start",
      })
    )

    await queryClient.refetchQueries({
      queryKey: ["inventory-service", "detail", INVENTORY_ID],
    })

    expect(within(conflictSection).getByText("БЫТ-001")).toBeTruthy()
    expect(within(conflictSection).getByText("БЫТ-002")).toBeTruthy()
  })

  it("shows completion actions only after the full furniture review is confirmed", async () => {
    const user = userEvent.setup()
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    const pendingReview = furnitureReview()
    const confirmedReview = furnitureReview({
      confirmed: true,
      reviewSha256: "b".repeat(64),
    })
    inventoryApi.getInventory.mockResolvedValue(staged)
    inventoryApi.getInventoryFurnitureReview
      .mockResolvedValueOnce(pendingReview)
      .mockResolvedValue(confirmedReview)
    inventoryApi.getInventoryFinalPlan
      .mockResolvedValueOnce(finalPlan())
      .mockResolvedValue(null)
    inventoryApi.saveInventoryFurnitureReview.mockImplementation(
      async ({ review }: { review: InventoryFurnitureReviewDto }) => {
        const savedReview = {
          ...review,
          confirmed: true,
          reviewSha256: "b".repeat(64),
        }
        inventoryApi.getInventoryFurnitureReview.mockResolvedValue(savedReview)
        return savedReview
      }
    )
    inventoryApi.previewInventoryCompletion.mockResolvedValue(
      completionReview(staged)
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    expect(await screen.findByText("Сверка мебели")).toBeTruthy()
    expect(
      screen.queryByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    ).toBeNull()

    await user.click(
      screen.getByRole("button", { name: "Сохранить сверку мебели" })
    )

    await waitFor(() =>
      expect(inventoryApi.saveInventoryFurnitureReview).toHaveBeenCalledWith({
        review: expect.objectContaining({
          inventoryId: INVENTORY_ID,
          sessionRevision: 2,
        }),
        session: staged,
      })
    )
    expect(
      await screen.findByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    ).toBeTruthy()

    const stock = screen.getByLabelText(
      "Посчитано на складе"
    ) as HTMLInputElement
    await user.clear(stock)
    await user.type(stock, "1")
    await waitFor(() =>
      expect(
        screen.queryByRole("button", {
          name: "Завершить и применить итоговый план",
        })
      ).toBeNull()
    )

    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения сверки" })
    )
    await waitFor(() =>
      expect(inventoryApi.saveInventoryFurnitureReview).toHaveBeenCalledTimes(2)
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Подготовить план и перейти к сверке заданий",
      })
    )
    await waitFor(() =>
      expect(inventoryApi.prepareInventoryFinalPlan).toHaveBeenCalledWith({
        inventoryId: INVENTORY_ID,
        expectedSessionRevision: 2,
        expectedSettingsRevision: 1,
        movementScheduleMode: "AUTO",
        repairScheduleMode: "AUTO",
      })
    )
    expect(
      await screen.findByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    ).toBeTruthy()
  })

  it("waits for the matching detail revision before previewing a confirmed furniture review", async () => {
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    inventoryApi.getInventory.mockResolvedValue(staged)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(
      furnitureReview({
        sessionRevision: 3,
        confirmed: true,
        reviewSha256: "b".repeat(64),
      })
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    expect(
      await screen.findByText("Обновляем версию инвентаризации...")
    ).toBeTruthy()
    expect(inventoryApi.previewInventoryCompletion).not.toHaveBeenCalled()
    expect(
      screen.queryByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    ).toBeNull()
  })

  it("recovers a stuck confirmed furniture revision by refreshing all derived state", async () => {
    const user = userEvent.setup()
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 3,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    const refreshed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 4,
        reviewStage: "CABINS",
        furnitureReconciliationState: "NOT_REQUIRED",
      }
    )
    const staleReview = furnitureReview({
      sessionRevision: 2,
      confirmed: true,
      reviewSha256: "b".repeat(64),
    })
    inventoryApi.getInventory
      .mockResolvedValueOnce(staged)
      .mockResolvedValue(refreshed)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(staleReview)
    inventoryApi.refreshInventorySession.mockResolvedValue(refreshed)

    const { queryClient } = renderPage(`/inventory/${INVENTORY_ID}/finish`)

    expect(
      await screen.findByText("Обновляем версию инвентаризации...")
    ).toBeTruthy()
    queryClient.setQueryData(
      ["inventory-service", "final-plan", INVENTORY_ID, 3],
      finalPlan(3)
    )
    queryClient.setQueryData(
      ["inventory-service", "finish-preview", INVENTORY_ID, 3, 1],
      completionReview(staged)
    )

    await user.click(
      screen.getByRole("button", { name: "Пересчитать изменения сессии" })
    )

    await waitFor(() =>
      expect(inventoryApi.refreshInventorySession).toHaveBeenCalledWith({
        inventoryId: INVENTORY_ID,
        expectedSessionRevision: 3,
      })
    )
    expect(await screen.findByText("Итоговая сверка бытовок")).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Завершить проверку бытовок и перейти к мебели",
      })
    ).toBeTruthy()
    expect(
      queryClient.getQueryData([
        "inventory-service",
        "furniture-review",
        INVENTORY_ID,
      ])
    ).toBeUndefined()
    expect(
      queryClient.getQueryData([
        "inventory-service",
        "final-plan",
        INVENTORY_ID,
        3,
      ])
    ).toBeUndefined()
    expect(
      queryClient.getQueryData([
        "inventory-service",
        "finish-preview",
        INVENTORY_ID,
        3,
        1,
      ])
    ).toBeUndefined()
    expect(toast.success).toHaveBeenCalledWith(
      "Изменения сессии пересчитаны. Сохранённые осмотры бытовок сохранены."
    )
  })

  it("blocks session refresh while furniture changes are unsaved", async () => {
    const user = userEvent.setup()
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    inventoryApi.getInventory.mockResolvedValue(staged)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(
      furnitureReview()
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    const refresh = (await screen.findByRole("button", {
      name: "Пересчитать изменения сессии",
    })) as HTMLButtonElement
    expect(refresh.disabled).toBe(false)

    const stock = screen.getByLabelText(
      "Посчитано на складе"
    ) as HTMLInputElement
    await user.clear(stock)
    await user.type(stock, "1")

    await waitFor(() => expect(refresh.disabled).toBe(true))
    expect(
      screen.getByText(
        /Сначала сохраните или отмените несохранённые изменения сверки мебели/
      )
    ).toBeTruthy()
    expect(inventoryApi.refreshInventorySession).not.toHaveBeenCalled()
  })

  it("shows the server reason when session refresh is rejected", async () => {
    const user = userEvent.setup()
    const reviewed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      { version: 8 }
    )
    inventoryApi.getInventory.mockResolvedValue(reviewed)
    inventoryApi.refreshInventorySession.mockRejectedValue(
      new Error("Dependency rejected invalid inventory input")
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", {
        name: "Пересчитать изменения сессии",
      })
    )

    expect(
      await screen.findByText(
        /Не удалось пересчитать изменения сессии: Dependency rejected invalid inventory input/
      )
    ).toBeTruthy()
  })

  it("blocks session refresh while another finish mutation is pending", async () => {
    const user = userEvent.setup()
    const reviewed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      { version: 8 }
    )
    inventoryApi.getInventory.mockResolvedValue(reviewed)
    inventoryApi.startInventoryFurnitureReview.mockImplementation(
      () => new Promise(() => undefined)
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    const refresh = (await screen.findByRole("button", {
      name: "Пересчитать изменения сессии",
    })) as HTMLButtonElement
    expect(refresh.disabled).toBe(false)

    await user.click(
      screen.getByRole("button", {
        name: "Завершить проверку бытовок и перейти к мебели",
      })
    )

    await waitFor(() => expect(refresh.disabled).toBe(true))
    expect(inventoryApi.startInventoryFurnitureReview).toHaveBeenCalledWith({
      session: reviewed,
      acknowledgeIncomplete: false,
    })
    expect(inventoryApi.refreshInventorySession).not.toHaveBeenCalled()
  })

  it("does not preview or apply a stale final plan and requires rebuilding it", async () => {
    const user = userEvent.setup()
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    inventoryApi.getInventory.mockResolvedValue(staged)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(
      furnitureReview({
        confirmed: true,
        reviewSha256: "b".repeat(64),
      })
    )
    inventoryApi.getInventoryFinalPlan.mockResolvedValue({
      ...finalPlan(),
      state: "STALE",
    })

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    expect(await screen.findByText("Итоговый план устарел")).toBeTruthy()
    expect(inventoryApi.previewInventoryCompletion).not.toHaveBeenCalled()
    expect(
      screen.queryByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    ).toBeNull()

    await user.click(
      screen.getByRole("button", {
        name: "Перестроить план по актуальным данным",
      })
    )
    await waitFor(() =>
      expect(inventoryApi.prepareInventoryFinalPlan).toHaveBeenCalledWith({
        inventoryId: INVENTORY_ID,
        expectedSessionRevision: 2,
        expectedSettingsRevision: 1,
        movementScheduleMode: "AUTO",
        repairScheduleMode: "AUTO",
      })
    )
  })

  it("does not refetch the completion preview after successful completion", async () => {
    const user = userEvent.setup()
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 3,
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "BLOCKED",
      }
    )
    inventoryApi.getInventory
      .mockResolvedValueOnce(staged)
      .mockResolvedValue(completed)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(
      furnitureReview({ confirmed: true, reviewSha256: "b".repeat(64) })
    )
    inventoryApi.previewInventoryCompletion
      .mockResolvedValueOnce(completionReview(staged))
      .mockRejectedValue(
        new ApiError(
          "Завершённую инвентаризацию нельзя проверять повторно",
          409
        )
      )
    inventoryApi.completeInventory.mockResolvedValue(completed)

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    )

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        "Применение мебельных остатков заблокировано конфликтом актуальных данных."
      )
    )
    await screen.findByText("Статус передачи")
    expect(inventoryApi.previewInventoryCompletion).toHaveBeenCalledTimes(1)
  })

  it("shows and resolves a fresh registry conflict from the furniture preview", async () => {
    const user = userEvent.setup()
    const staged = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "READY",
      }
    )
    const conflictedFurniture = activeSession(conflictFinding(), {
      version: 3,
      reviewStage: "FURNITURE",
      furnitureReconciliationState: "READY",
    })
    const resolvedCabins = activeSession(
      {
        ...conflictFinding(),
        version: 7,
        reconciliationStatus: "MATCHED",
        conflicts: [],
        conflictResolution: {
          strategy: "ACCEPT_REGISTRY",
          reason: null,
          resolvedAt: "2026-07-27T10:00:00Z",
        },
      },
      {
        version: 4,
        reviewStage: "CABINS",
        furnitureReconciliationState: "NOT_REQUIRED",
      }
    )
    inventoryApi.getInventory
      .mockResolvedValueOnce(staged)
      .mockResolvedValue(conflictedFurniture)
    inventoryApi.getInventoryFurnitureReview.mockResolvedValue(
      furnitureReview({ confirmed: true, reviewSha256: "b".repeat(64) })
    )
    inventoryApi.previewInventoryCompletion.mockResolvedValue(
      completionReview(staged)
    )
    inventoryApi.completeInventory.mockRejectedValue(
      new Error("Реестр изменился во время завершения")
    )
    inventoryApi.resolveInventoryFindingConflict.mockResolvedValue(
      resolvedCabins
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", {
        name: "Завершить и применить итоговый план",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.completeInventory).toHaveBeenCalledWith({
        inventoryId: INVENTORY_ID,
        expectedVersion: 2,
        actor: expect.objectContaining({ id: currentUser.id }),
        completionEvidence: completionEvidence(2),
      })
    )
    expect(
      await screen.findByRole("button", {
        name: "Сохранить актуальные данные реестра",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Применить данные инвентаризации",
      })
    ).toBeTruthy()

    inventoryApi.getInventory.mockResolvedValue(resolvedCabins)

    await user.click(
      screen.getByRole("button", {
        name: "Сохранить актуальные данные реестра",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.resolveInventoryFindingConflict).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedVersion: 3,
          expectedFindingVersion: 6,
          strategy: "ACCEPT_REGISTRY",
        })
      )
    )
    expect(await screen.findByText("Итоговая сверка бытовок")).toBeTruthy()
  })

  it("formats structured conflict values without breaking ordinary text", async () => {
    const conflict = conflictFinding()
    const reviewed = activeSession(
      {
        ...conflict,
        conflicts: [
          {
            ...conflict.conflicts[0],
            expected: '{"status":"old","equipment":{"table":1}}',
            actual: '{"status":"new","equipment":{"table":0}}',
          },
        ],
      },
      { version: 8 }
    )
    inventoryApi.getInventory.mockResolvedValue(reviewed)

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    const [structured] = await screen.findAllByText(
      (_, node) =>
        node?.tagName === "PRE" && node.textContent?.includes('"status": "old"')
    )
    expect(structured.className).toContain("break-words")
    const plainValue = screen
      .getAllByText("Свободна")
      .find(
        (candidate) =>
          candidate.tagName === "SPAN" && candidate.textContent === "Свободна"
      )
    expect(plainValue?.className).not.toContain("break-words")
  })

  it("shows server-issued totals and all aggregate positions before furniture review", async () => {
    const statisticsWithPositions: InventoryStatisticsDto = {
      ...finishStatistics,
      materialLineCount: 1,
      materialTotal: "50.00",
      grandTotal: "150.00",
      aggregates: [
        {
          key: "work",
          lineType: "WORK",
          description: "Заменить дверь",
          catalogNodeId: "88888888-8888-4888-8888-888888888888",
          unit: "шт.",
          quantity: 1,
          unitPrice: "100.00",
          total: "100.00",
        },
        {
          key: "material",
          lineType: "MATERIAL",
          description: "Дверь металлическая",
          catalogNodeId: "99999999-9999-4999-8999-999999999999",
          unit: "шт.",
          quantity: 1,
          unitPrice: "50.00",
          total: "50.00",
        },
      ],
    }
    const reviewed = activeSession(
      finding("FREE", {
        inspectionStatus: "WORK_STAGED",
        lines: [workLine],
        movementToRepair: true,
      }),
      { statistics: null }
    )
    inventoryApi.getInventory.mockResolvedValue(reviewed)
    inventoryApi.getInventoryPreliminaryStatistics.mockResolvedValue(
      statisticsWithPositions
    )

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    const completionSummary = (
      await screen.findByText("Итоговая сверка бытовок")
    ).closest('[data-slot="card"]')
    if (!(completionSummary instanceof HTMLElement)) {
      throw new Error("Не найдена карточка итоговой сверки")
    }
    expect(within(completionSummary).getByText("Ожидалось: 1")).toBeTruthy()
    expect(within(completionSummary).getByText("Проверено: 1")).toBeTruthy()
    expect(
      within(completionSummary).getByText("Перемещения на ремонт и вывозы: 1")
    ).toBeTruthy()
    expect(
      await within(completionSummary).findByText("Итого: 150,00 ₽")
    ).toBeTruthy()
    expect(
      within(screen.getByLabelText("Статистика инвентаризации")).queryByText(
        "Ожидалось: 1"
      )
    ).toBeNull()
    const workTotals = await screen.findByRole("table", { name: "Итоги работ" })
    const materialTotals = screen.getByRole("table", {
      name: "Итоги материалов",
    })
    expect(within(workTotals).getByText("Заменить дверь")).toBeTruthy()
    expect(within(workTotals).queryByText("Дверь металлическая")).toBeNull()
    expect(within(materialTotals).getByText("Дверь металлическая")).toBeTruthy()
    expect(within(materialTotals).queryByText("Заменить дверь")).toBeNull()
    expect(screen.getAllByText("150,00 ₽").length).toBeGreaterThan(0)
  })

  it("blocks completion and resolves a conflict by accepting the registry", async () => {
    const user = userEvent.setup()
    const conflict = conflictFinding()
    const reviewed = activeSession(conflict, { version: 8 })
    const resolved = activeSession(
      {
        ...conflict,
        version: 7,
        reconciliationStatus: "MATCHED",
        conflicts: [],
        conflictResolution: {
          strategy: "ACCEPT_REGISTRY",
          reason: null,
          resolvedAt: "2026-07-27T10:00:00Z",
        },
      },
      { version: 9 }
    )
    inventoryApi.getInventory
      .mockResolvedValueOnce(reviewed)
      .mockResolvedValue(resolved)
    inventoryApi.resolveInventoryFindingConflict.mockResolvedValue(resolved)

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    const moveToFurniture = (await screen.findByRole("button", {
      name: "Завершить проверку бытовок и перейти к мебели",
    })) as HTMLButtonElement
    expect(moveToFurniture.disabled).toBe(false)
    expect(
      screen.getByText(
        "Урегулируйте все конфликты реестра перед переходом к сверке мебели."
      )
    ).toBeTruthy()
    expect(screen.queryByText(/Я проверил.*конфликт/i)).toBeNull()
    expect(screen.getByLabelText("Было → Стало")).toBeTruthy()
    expect(screen.getByText("Ожидает осмотра")).toBeTruthy()
    expect(screen.getAllByText("Свободна").length).toBeGreaterThan(0)

    await user.click(
      screen.getByRole("button", {
        name: "Сохранить актуальные данные реестра",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.resolveInventoryFindingConflict).toHaveBeenCalledWith(
        {
          inventoryId: INVENTORY_ID,
          expectedVersion: 8,
          expectedFindingVersion: 6,
          actor: expect.objectContaining({
            id: currentUser.id,
          }),
          findingId: FINDING_ID,
          strategy: "ACCEPT_REGISTRY",
          reason: null,
        }
      )
    )
  })

  it("requires an audited reason before keeping inspection data", async () => {
    const user = userEvent.setup()
    const conflict = conflictFinding()
    const reviewed = activeSession(conflict, { version: 8 })
    const resolved = activeSession(
      {
        ...conflict,
        version: 7,
        reconciliationStatus: "MATCHED",
        conflicts: [],
        conflictResolution: {
          strategy: "KEEP_INSPECTION",
          reason: "Подтверждено повторным осмотром",
          resolvedAt: "2026-07-27T10:00:00Z",
        },
      },
      { version: 9 }
    )
    inventoryApi.getInventory.mockResolvedValue(reviewed)
    inventoryApi.resolveInventoryFindingConflict.mockResolvedValue(resolved)

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", {
        name: "Применить данные инвентаризации",
      })
    )
    const dialog = screen.getByRole("dialog", {
      name: "Применить данные инвентаризации",
    })
    await user.click(
      within(dialog).getByRole("button", { name: "Сохранить решение" })
    )
    expect(within(dialog).getByText("Введите причину решения")).toBeTruthy()
    expect(inventoryApi.resolveInventoryFindingConflict).not.toHaveBeenCalled()

    await user.type(
      within(dialog).getByRole("textbox", { name: "Причина" }),
      "Подтверждено повторным осмотром"
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Сохранить решение" })
    )

    await waitFor(() =>
      expect(inventoryApi.resolveInventoryFindingConflict).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedVersion: 8,
          expectedFindingVersion: 6,
          strategy: "KEEP_INSPECTION",
          reason: "Подтверждено повторным осмотром",
        })
      )
    )
  })

  it("opens the existing inspection editor from a conflict", async () => {
    const user = userEvent.setup()
    const reviewed = activeSession(conflictFinding(), { version: 8 })
    inventoryApi.getInventory.mockResolvedValue(reviewed)

    renderPage(`/inventory/${INVENTORY_ID}/finish`)

    await user.click(
      await screen.findByRole("button", { name: "Дополнить осмотр" })
    )

    expect(
      await screen.findByRole("region", { name: "Редактор осмотра" })
    ).toBeTruthy()
  })
})

describe("InventoryHistoryDetailPage authoritative outcome recovery", () => {
  it("keeps the completed-history action disabled without MANAGE access", async () => {
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
      }
    )
    auth.useAuth.mockReturnValue({
      accessToken: "inventory-token",
      currentUser: {
        ...currentUser,
        warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "VIEW" }],
      },
    })
    inventoryApi.getInventory.mockResolvedValue(completed)

    renderPage(`/inventory/history/${INVENTORY_ID}`)

    const action = (await screen.findByRole("button", {
      name: "Пересчитать и применить итоги",
    })) as HTMLButtonElement
    expect(action.disabled).toBe(true)
    expect(action.title).toContain("MANAGE")
    expect(inventoryApi.getInventoryFinalPlan).not.toHaveBeenCalled()
  })

  it("keeps the action disabled when the completed final-plan identity is absent", async () => {
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
      }
    )
    inventoryApi.getInventory.mockResolvedValue(completed)
    inventoryApi.getInventoryFinalPlan.mockResolvedValue(null)

    renderPage(`/inventory/history/${INVENTORY_ID}`)

    const action = (await screen.findByRole("button", {
      name: "Пересчитать и применить итоги",
    })) as HTMLButtonElement
    await waitFor(() => expect(action.disabled).toBe(true))
    expect(action.title).toContain("итоговый план")
    expect(inventoryApi.recalculateInventoryOutcome).not.toHaveBeenCalled()
  })

  it("requires explicit confirmation and disables the dialog while recalculation is pending", async () => {
    const user = userEvent.setup()
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
      }
    )
    let resolveOutcome!: (value: OutcomeRecalculation) => void
    const pendingOutcome = new Promise<OutcomeRecalculation>((resolve) => {
      resolveOutcome = resolve
    })
    inventoryApi.getInventory.mockResolvedValue(completed)
    inventoryApi.getInventoryFinalPlan.mockResolvedValue(completedFinalPlan(2))
    inventoryApi.recalculateInventoryOutcome.mockReturnValue(pendingOutcome)

    renderPage(`/inventory/history/${INVENTORY_ID}`)

    const action = (await screen.findByRole("button", {
      name: "Пересчитать и применить итоги",
    })) as HTMLButtonElement
    await waitFor(() => expect(action.disabled).toBe(false))
    await user.click(action)

    const dialog = await screen.findByRole("alertdialog", {
      name: "Применить итоги завершённой инвентаризации?",
    })
    expect(within(dialog).getByText(/текущей истиной/)).toBeTruthy()
    expect(
      within(dialog).getByText(/аренда, резерв, внутреннее перемещение/)
    ).toBeTruthy()
    expect(within(dialog).getByText(/Бытовка без работ/).textContent).toContain(
      "капитальном ремонте"
    )
    expect(
      within(dialog).getByText(/Фотографии, доказательства и история/)
    ).toBeTruthy()
    expect(inventoryApi.recalculateInventoryOutcome).not.toHaveBeenCalled()

    await user.click(
      within(dialog).getByRole("button", {
        name: "Пересчитать и применить",
      })
    )

    await waitFor(() =>
      expect(inventoryApi.recalculateInventoryOutcome).toHaveBeenCalledWith({
        inventoryId: INVENTORY_ID,
        expectedSessionRevision: 2,
        finalPlanVersion: 1,
        finalPlanSha256: FINAL_PLAN_SHA256,
      })
    )
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Запускаем пересчёт…",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Отмена",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)

    resolveOutcome(outcomeRecalculation())
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
  })

  it("stores the publication response, refreshes exact caches and reports all counts", async () => {
    const user = userEvent.setup()
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
      }
    )
    const outcome = outcomeRecalculation({
      finalPlanVersion: 2,
      finalPlanSha256: "d".repeat(64),
    })
    inventoryApi.getInventory.mockResolvedValue(completed)
    inventoryApi.getInventoryFinalPlan.mockResolvedValue(completedFinalPlan(2))
    inventoryApi.recalculateInventoryOutcome.mockResolvedValue(outcome)

    const { queryClient } = renderPage(`/inventory/history/${INVENTORY_ID}`)
    const invalidateQueries = vi.spyOn(queryClient, "invalidateQueries")

    const action = (await screen.findByRole("button", {
      name: "Пересчитать и применить итоги",
    })) as HTMLButtonElement
    await waitFor(() => expect(action.disabled).toBe(false))
    await user.click(action)
    await user.click(
      within(await screen.findByRole("alertdialog")).getByRole("button", {
        name: "Пересчитать и применить",
      })
    )

    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith(
        "Пересчёт запущен: создано 75, заново поставлено 141, уже полностью применено 0."
      )
    )
    expect(
      queryClient.getQueryData(inventoryPublicationQueryKey(INVENTORY_ID))
    ).toEqual(outcome.publicationBatch)
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["inventory-service", "detail", INVENTORY_ID],
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["inventory-service", "final-plan", INVENTORY_ID],
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["inventory-service", "statistics-preview", INVENTORY_ID],
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["inventory-service", "list", WAREHOUSE_ID],
    })
    expect(inventoryApi.getInventory).toHaveBeenCalledTimes(2)
    expect(inventoryApi.getInventoryFinalPlan).toHaveBeenCalledTimes(2)
  })

  it("keeps a Problem Details failure recoverable in the confirmation", async () => {
    const user = userEvent.setup()
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        version: 2,
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
      }
    )
    inventoryApi.getInventory.mockResolvedValue(completed)
    inventoryApi.getInventoryFinalPlan.mockResolvedValue(completedFinalPlan(2))
    inventoryApi.recalculateInventoryOutcome.mockRejectedValue(
      new ApiError(
        "Dependency rejected invalid inventory input",
        422,
        "INVALID_INVENTORY_INPUT"
      )
    )

    renderPage(`/inventory/history/${INVENTORY_ID}`)

    const action = (await screen.findByRole("button", {
      name: "Пересчитать и применить итоги",
    })) as HTMLButtonElement
    await waitFor(() => expect(action.disabled).toBe(false))
    await user.click(action)
    const dialog = await screen.findByRole("alertdialog")
    await user.click(
      within(dialog).getByRole("button", {
        name: "Пересчитать и применить",
      })
    )

    const failure = await within(dialog).findByRole("alert")
    expect(within(failure).getByText("Пересчёт не запущен")).toBeTruthy()
    expect(failure.textContent).toContain(
      "Dependency rejected invalid inventory input"
    )
    expect(failure.textContent).toContain("фотографии и история сохранены")
    expect(failure.textContent).toContain("повторите пересчёт")
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Пересчитать и применить",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
  })
})

describe("InventoryHistoryDetailPage furniture reconciliation", () => {
  it("shows a prominent blocked furniture reconciliation status without a retry control", async () => {
    const completed = activeSession(
      finding("FREE", { inspectionStatus: "READY" }),
      {
        status: "COMPLETED",
        completedAt: "2026-07-27T09:00:00Z",
        reviewStage: "FURNITURE",
        furnitureReconciliationState: "BLOCKED",
      }
    )
    inventoryApi.getInventory.mockResolvedValue(completed)

    renderPage(`/inventory/history/${INVENTORY_ID}`)

    const statusCard = (
      await screen.findByText("Статус сверки мебели")
    ).closest('[data-slot="card"]')
    if (!(statusCard instanceof HTMLElement)) {
      throw new Error("Не найдена карточка статуса сверки мебели")
    }
    const statusBadge = within(statusCard).getByText(
      "Применение мебельных остатков заблокировано"
    )
    expect(statusBadge.getAttribute("data-variant")).toBe("destructive")
    expect(within(statusCard).getByRole("alert").textContent).toContain(
      "заблокировано конфликтом актуальных данных"
    )
    expect(
      within(statusCard).queryByRole("button", { name: /повтор/i })
    ).toBeNull()
  })
})
