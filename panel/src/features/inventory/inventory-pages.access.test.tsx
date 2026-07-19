import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  InventoryCompletionPreview,
  InventoryFinding,
  InventoryFrozenStatistics,
  InventorySessionView,
} from "@/features/inventory/model/inventory-service"

const mocks = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
  getActiveInventorySession: vi.fn(),
  getInventorySession: vi.fn(),
  previewInventoryCompletion: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))
vi.mock("@/features/inventory/api/inventory-api", () => ({
  completeInventorySession: vi.fn(),
  createAndAttachInventoryAsset: vi.fn(),
  getActiveInventorySession: mocks.getActiveInventorySession,
  getInventorySession: mocks.getInventorySession,
  getInventoryStatisticsSummary: vi.fn(),
  listInventorySessions: vi.fn(),
  previewInventoryCompletion: mocks.previewInventoryCompletion,
  publishInventoryFindings: vi.fn(),
  resolveInventoryNumber: vi.fn(),
  startInventorySession: vi.fn(),
}))
vi.mock("@/features/inventory/inventory-finding-editor", () => ({
  InventoryFindingEditor: ({
    readOnly,
    mediaReadOnly,
  }: {
    readOnly: boolean
    mediaReadOnly: boolean
  }) => (
    <>
      <div data-testid="finding-command-state">
        {readOnly ? "read-only" : "editable"}
      </div>
      <div data-testid="media-command-state">
        {mediaReadOnly ? "read-only" : "editable"}
      </div>
    </>
  ),
}))

import {
  InventoryEntryPage,
  InventoryFinishPage,
  InventorySessionPage,
} from "@/features/inventory/inventory-pages"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000901"
const INVENTORY_ID = "00000000-0000-4000-8000-000000000902"

function user(level: "VIEW" | "EDIT" | "MANAGE"): CurrentUser {
  return {
    id: "00000000-0000-4000-8000-000000000903",
    username: "inventory-operator",
    displayName: "Inventory Operator",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

const finding: InventoryFinding = {
  id: "00000000-0000-4000-8000-000000000904",
  inventoryId: INVENTORY_ID,
  findingRevision: 1,
  origin: "EXPECTED",
  inspection: "NOT_INSPECTED",
  reconciliation: "MATCHED",
  assetId: "00000000-0000-4000-8000-000000000905",
  assetVersion: 1,
  displayCanonicalNumber: "CAB-901",
  identityMatchKey: "CAB-901",
  passportObservation: { presence: "ABSENT", value: null },
  equipmentObservation: { presence: "ABSENT", value: null },
  mutationState: "IDLE",
  planFingerprintSha256: null,
  expectedSnapshot: null,
  frozenPlan: null,
  media: [],
  publication: null,
}

const session: InventorySessionView = {
  id: INVENTORY_ID,
  sessionRevision: 2,
  warehouseId: WAREHOUSE_ID,
  warehouseVersion: 1,
  warehouseTimeZone: "Europe/Moscow",
  businessDate: "2026-07-18",
  lifecycle: "ACTIVE",
  expectedCount: 1,
  findingCount: 1,
  inspectedCount: 0,
  startedAt: "2026-07-18T10:00:00Z",
  terminalAt: null,
  publicationState: "NOT_REQUESTED",
  statistics: null,
  cancellation: null,
  findings: [finding],
}

const statistics: InventoryFrozenStatistics = {
  expectedCount: 1,
  inspectedCount: 1,
  missingCount: 0,
  readyCount: 1,
  withWorkCount: 0,
  addedCount: 0,
  unexpectedExistingCount: 0,
  conflictCount: 0,
  workLineCount: 0,
  materialLineCount: 0,
  workTotalMinor: 0,
  materialTotalMinor: 0,
  grandTotalMinor: 0,
  roundingAdjustmentMinor: 0,
  normativeMinutes: "0",
  durationSeconds: 60,
  aggregateLines: [],
}

const preview: InventoryCompletionPreview = {
  inventoryId: INVENTORY_ID,
  sessionRevision: 2,
  findingRevisions: [{ findingId: finding.id, expectedFindingRevision: 1 }],
  validationSha256: "a".repeat(64),
  validatedAt: "2026-07-18T10:01:00Z",
  acknowledgementSha256: "b".repeat(64),
  statistics,
  risks: [],
}

function configureAccess(level: "VIEW" | "EDIT" | "MANAGE") {
  mocks.useAuth.mockReturnValue({
    accessToken: "inventory-token",
    currentUser: user(level),
  })
  mocks.useWarehouse.mockReturnValue({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      name: "Основной склад",
    },
  })
}

function renderRoute(path: string, route: string, element: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
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
        <Routes>
          <Route path={route} element={element} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("inventory command access", () => {
  it("keeps a VIEW user on read-only entry and finding surfaces", async () => {
    configureAccess("VIEW")
    mocks.getActiveInventorySession.mockResolvedValue(null)
    const entry = renderRoute(
      "/inventory",
      "/inventory",
      <InventoryEntryPage />
    )

    await screen.findByText(/Активной сессии для склада/)
    expect(
      screen.queryByRole("button", { name: "Начать инвентаризацию" })
    ).toBeNull()
    expect(screen.getByRole("button", { name: "История" })).toBeTruthy()
    entry.unmount()

    mocks.getInventorySession.mockResolvedValue(session)
    renderRoute(
      `/inventory/${INVENTORY_ID}`,
      "/inventory/:inventoryId",
      <InventorySessionPage />
    )

    await screen.findByText("CAB-901")
    expect(screen.queryByRole("button", { name: "Проверить номер" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Завершить" })).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Открыть" }))
    expect(screen.getByTestId("finding-command-state").textContent).toBe(
      "read-only"
    )
    expect(screen.getByTestId("media-command-state").textContent).toBe(
      "read-only"
    )
  })

  it("allows EDIT start and finding commands but not completion", async () => {
    configureAccess("EDIT")
    mocks.getActiveInventorySession.mockResolvedValue(null)
    const entry = renderRoute(
      "/inventory",
      "/inventory",
      <InventoryEntryPage />
    )

    expect(
      await screen.findByRole("button", { name: "Начать инвентаризацию" })
    ).toBeTruthy()
    entry.unmount()

    mocks.getInventorySession.mockResolvedValue(session)
    renderRoute(
      `/inventory/${INVENTORY_ID}`,
      "/inventory/:inventoryId",
      <InventorySessionPage />
    )

    expect(
      await screen.findByRole("button", { name: "Проверить номер" })
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Завершить" })).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Открыть" }))
    expect(screen.getByTestId("finding-command-state").textContent).toBe(
      "editable"
    )
    expect(screen.getByTestId("media-command-state").textContent).toBe(
      "editable"
    )
  })

  it("reserves completion preview and command UI for MANAGE", async () => {
    configureAccess("VIEW")
    mocks.getInventorySession.mockResolvedValue(session)
    const view = renderRoute(
      `/inventory/${INVENTORY_ID}/finish`,
      "/inventory/:inventoryId/finish",
      <InventoryFinishPage />
    )

    expect(
      await screen.findByText(
        "Для завершения инвентаризации требуется уровень MANAGE."
      )
    ).toBeTruthy()
    expect(mocks.previewInventoryCompletion).not.toHaveBeenCalled()
    view.unmount()

    configureAccess("MANAGE")
    mocks.getInventorySession.mockResolvedValue(session)
    mocks.previewInventoryCompletion.mockResolvedValue(preview)
    renderRoute(
      `/inventory/${INVENTORY_ID}/finish`,
      "/inventory/:inventoryId/finish",
      <InventoryFinishPage />
    )

    expect(
      await screen.findByRole("button", {
        name: "Завершить инвентаризацию",
      })
    ).toBeTruthy()
    expect(mocks.previewInventoryCompletion).toHaveBeenCalledOnce()
  })
})
