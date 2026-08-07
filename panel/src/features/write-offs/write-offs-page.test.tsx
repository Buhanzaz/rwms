import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser, UserGlobalRole } from "@/features/auth/auth-model"
import type { PropertyDispositionDecision } from "@/features/write-offs/property-dispositions-api"

const api = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  recover: vi.fn(),
}))
const context = vi.hoisted(() => ({ useAuth: vi.fn(), useWarehouse: vi.fn() }))
const actorApi = vi.hoisted(() => ({ list: vi.fn() }))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: context.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({ useWarehouse: context.useWarehouse }))
vi.mock("@/features/rental-items/dossier/actor/actor-display-api", () => ({
  listDossierActorDisplays: actorApi.list,
}))
vi.mock("@/features/write-offs/property-dispositions-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/write-offs/property-dispositions-api")
  >("@/features/write-offs/property-dispositions-api")
  return {
    ...actual,
    listPropertyDispositions: api.list,
    getPropertyDisposition: api.get,
    approvePropertyDisposition: api.approve,
    rejectPropertyDisposition: api.reject,
    recoverPropertyDisposition: api.recover,
  }
})

import { EquipmentWriteOffsPage } from "./equipment-write-offs-page"
import { WriteOffsPage } from "./write-offs-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DECISION_ID = "22222222-2222-4222-8222-222222222222"
const CABIN_ID = "33333333-3333-4333-8333-333333333333"
const ACTOR_ID = "44444444-4444-4444-8444-444444444444"

function currentUser(
  role: UserGlobalRole,
  level: "VIEW" | "MANAGE" = "MANAGE"
): CurrentUser {
  return {
    id: ACTOR_ID,
    username: "operator",
    displayName: "Оператор",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: role,
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

function decision(
  overrides: Partial<PropertyDispositionDecision> = {}
): PropertyDispositionDecision {
  return {
    id: DECISION_ID,
    version: 3,
    recoveryVersion: 0,
    warehouseId: WAREHOUSE_ID,
    assetKind: "CABIN",
    assetId: CABIN_ID,
    assetDisplayName: "БЫТ-101",
    disposition: "WRITE_OFF",
    source: "REPAIR",
    state: "PENDING_APPROVAL",
    reason: "Корпус не подлежит ремонту",
    evidenceLink: null,
    quantity: null,
    expectedAssetVersion: 7,
    expectedSourceBalanceVersion: null,
    contentsPlan: null,
    rootRepairId: "55555555-5555-4555-8555-555555555555",
    repairChain: [
      { repairId: "55555555-5555-4555-8555-555555555555", repairVersion: 2 },
      { repairId: "66666666-6666-4666-8666-666666666666", repairVersion: 1 },
    ],
    inventorySessionId: null,
    findingId: null,
    assetEffectState: "NOT_STARTED",
    movementTaskId: null,
    failureCode: null,
    failureDetail: null,
    requestedBy: { actorId: ACTOR_ID, actorType: "USER" },
    requestedAt: "2026-08-05T10:00:00Z",
    reviewedBy: null,
    reviewedAt: null,
    reviewComment: null,
    effectiveAt: null,
    updatedAt: "2026-08-05T10:00:00Z",
    ...overrides,
  }
}

function renderPage({
  role = "WAREHOUSE_MANAGER",
  level = "MANAGE",
  path = "/write-offs",
  items = [decision()],
}: {
  role?: UserGlobalRole
  level?: "VIEW" | "MANAGE"
  path?: string
  items?: PropertyDispositionDecision[]
} = {}) {
  context.useAuth.mockReturnValue({
    accessToken: "maintenance-token",
    currentUser: currentUser(role, level),
  })
  context.useWarehouse.mockReturnValue({ selectedWarehouseId: WAREHOUSE_ID })
  api.list.mockResolvedValue({
    items,
    page: 0,
    size: 50,
    totalElements: items.length,
  })
  api.get.mockResolvedValue(null)
  actorApi.list.mockResolvedValue([])
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        {path === "/write-offs/equipment" ? (
          <EquipmentWriteOffsPage />
        ) : (
          <WriteOffsPage />
        )}
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe("maintenance property disposition views", () => {
  it("renders one decision row for a whole repair chain and links a cabin dossier", async () => {
    const { container } = renderPage()
    expect(await screen.findAllByText("БЫТ-101")).not.toHaveLength(0)
    const gridRows = container.querySelectorAll(
      '[data-slot="operations-list-grid"] tbody > tr'
    )
    expect(gridRows).toHaveLength(1)
    expect(
      screen.getAllByRole("link", { name: "БЫТ-101" })[0]?.getAttribute("href")
    ).toBe(`/warehouse/${CABIN_ID}`)
  })

  it("does not call an approved decision written off before the asset effect is applied", async () => {
    renderPage({
      items: [decision({ state: "APPROVED", assetEffectState: "NOT_STARTED" })],
    })
    expect(
      await screen.findAllByText("Одобрено, эффект не начат")
    ).not.toHaveLength(0)
    expect(screen.queryByText("Списано")).toBeNull()
  })

  it("labels unaccounted furniture losses as having no warehouse effect", async () => {
    renderPage({
      path: "/write-offs/equipment",
      items: [
        decision({
          disposition: "LOSS",
          source: "UNACCOUNTED",
          state: "EFFECTIVE",
          assetEffectState: "NOT_REQUIRED",
        }),
      ],
    })

    expect(
      await screen.findAllByText("Неучтённое наполнение")
    ).not.toHaveLength(0)
    expect(screen.getAllByText("Без складского эффекта")).not.toHaveLength(0)
  })

  it("excludes rental managers from initiation even with MANAGE access", async () => {
    renderPage({ role: "RENTAL_MANAGER" })
    await screen.findAllByText("БЫТ-101")
    expect(screen.queryByRole("button", { name: "Списать бытовку" })).toBeNull()
  })

  it("lets a warehouse manager initiate but only an administrator approve", async () => {
    const manager = renderPage()
    await screen.findAllByText("БЫТ-101")
    expect(screen.getByRole("button", { name: "Списать бытовку" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Принять" })).toBeNull()
    manager.unmount()

    const updated = decision({ state: "APPROVED", version: 4 })
    api.approve.mockResolvedValue(updated)
    const operator = userEvent.setup()
    renderPage({ role: "WMS_ADMIN" })
    expect(
      await screen.findByRole("button", { name: "Списать бытовку" })
    ).toBeTruthy()
    await screen.findAllByRole("button", { name: "Принять" })
    await operator.click(screen.getAllByRole("button", { name: "Принять" })[0]!)
    await operator.click(
      screen.getAllByRole("button", { name: "Принять" }).at(-1)!
    )
    await waitFor(() =>
      expect(api.approve).toHaveBeenCalledWith(
        expect.objectContaining({
          decisionId: DECISION_ID,
          expectedVersion: 3,
        })
      )
    )
  })

  it("uses the legacy second route as the clear loss view", async () => {
    renderPage({
      path: "/write-offs/equipment",
      items: [decision({ disposition: "LOSS" })],
    })
    await screen.findAllByText("БЫТ-101")
    expect(api.list).toHaveBeenCalledWith(
      expect.objectContaining({
        disposition: "LOSS",
        warehouseId: WAREHOUSE_ID,
      })
    )
    const lossTab = screen.getByRole("tab", { name: "Утраты" })
    expect(lossTab.getAttribute("data-state")).toBe("active")
    fireEvent.click(lossTab)
  })
})
