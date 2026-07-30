import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, within } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { KpiPage } from "@/features/kpi/kpi-page"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const warehouse = {
  id: warehouseId,
  version: 0,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: 1,
}
const viewer = {
  id: "00000000-0000-4000-8000-000000000010",
  username: "viewer",
  displayName: "Наблюдатель",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WAREHOUSE_VIEWER",
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId, level: "VIEW" }],
}

const mocks = vi.hoisted(() => ({
  auth: {
    accessToken: "access-token" as string | null,
    currentUser: null as Record<string, unknown> | null,
  },
  warehouse: {
    selectedWarehouse: null as Record<string, unknown> | null,
  },
  getGroupKpi: vi.fn(),
  listKpiWorkerGroups: vi.fn(),
  getKpiSettings: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => mocks.auth,
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => mocks.warehouse,
}))
vi.mock("@/features/kpi/api/kpi-api", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("@/features/kpi/api/kpi-api")>()
  return {
    ...original,
    getGroupKpi: mocks.getGroupKpi,
    listKpiWorkerGroups: mocks.listKpiWorkerGroups,
  }
})
vi.mock(
  "@/features/settings/kpi/api/kpi-settings-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/kpi/api/kpi-settings-api")
      >()
    return { ...original, getKpiSettings: mocks.getKpiSettings }
  }
)

function renderPage() {
  return render(
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
      <KpiPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth.accessToken = "access-token"
  mocks.auth.currentUser = viewer
  mocks.warehouse.selectedWarehouse = warehouse
  mocks.getKpiSettings.mockResolvedValue({
    warehouseId,
    timeZone: "Europe/Moscow",
    status: "ACTIVE",
    version: 4,
    dataAvailableFrom: "2026-07-01",
    palette: {
      version: 2,
      ranges: [
        { fromPercent: 0, toPercent: 35, color: "#DC2626" },
        { fromPercent: 35, toPercent: 70, color: "#EAB308" },
        { fromPercent: 70, toPercent: 100, color: "#16A34A" },
      ],
      overdueColor: "#7F1D1D",
    },
    activeSchedule: null,
    pendingSchedule: null,
  })
  mocks.listKpiWorkerGroups.mockResolvedValue([
    { id: "active-group", name: "Слесари", active: true },
    { id: "inactive-group", name: "Старая бригада", active: false },
    { id: "inactive-empty", name: "Без истории", active: false },
  ])
  mocks.getGroupKpi.mockResolvedValue({
    warehouseId,
    periodType: "DAY",
    periodStart: "2026-07-30",
    periodEnd: "2026-07-30",
    coverageStart: "2026-07-30",
    coverageEnd: "2026-07-30",
    status: "PROVISIONAL",
    dataAvailableFrom: "2026-07-01",
    formulaVersion: "v1",
    asOf: "2026-07-30T12:00:00Z",
    groups: [
      {
        workerGroupId: "active-group",
        kpi: 52,
        speed: 65,
        utilization: 80,
        completedTaskCount: 4,
        completedBudgetSeconds: 14400,
        activeSeconds: 10800,
        penalizedIdleSeconds: 2700,
      },
      {
        workerGroupId: "inactive-group",
        kpi: 20,
        speed: 40,
        utilization: 50,
        completedTaskCount: 1,
        completedBudgetSeconds: 3600,
        activeSeconds: 1800,
        penalizedIdleSeconds: 1800,
      },
      {
        workerGroupId: "deleted-group",
        kpi: 10,
        speed: 20,
        utilization: 50,
        completedTaskCount: 1,
        completedBudgetSeconds: 3600,
        activeSeconds: 900,
        penalizedIdleSeconds: 900,
      },
    ],
  })
})

afterEach(cleanup)

describe("KpiPage", () => {
  it("keeps period filters visible and merges active and historical groups", async () => {
    renderPage()

    expect(
      await screen.findByRole("combobox", { name: "Тип периода" })
    ).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "Год" })).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "Месяц" })).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "День" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Сбросить период" })).toBeTruthy()

    expect(await screen.findByText("Слесари")).toBeTruthy()
    expect(screen.getByText("Старая бригада")).toBeTruthy()
    expect(screen.getByText("Бригада deleted-")).toBeTruthy()
    expect(screen.queryByText("Без истории")).toBeNull()

    const card = screen.getByTestId("kpi-group-active-group")
    expect(within(card).getByLabelText("KPI бригады: 52%")).toBeTruthy()
    expect(within(card).getByText(/Скорость 65%/)).toBeTruthy()
    expect(within(card).getByText(/Простой 45 мин/)).toBeTruthy()
    expect(screen.getByText("Предварительный результат")).toBeTruthy()
  })

  it("shows active groups with a dash when the selected period has no data", async () => {
    mocks.getGroupKpi.mockResolvedValue({
      warehouseId,
      periodType: "DAY",
      periodStart: "2026-07-30",
      periodEnd: "2026-07-30",
      coverageStart: null,
      coverageEnd: null,
      status: "NO_DATA",
      dataAvailableFrom: "2026-07-01",
      formulaVersion: "v1",
      asOf: "2026-07-30T12:00:00Z",
      groups: [],
    })

    renderPage()

    const card = await screen.findByTestId("kpi-group-active-group")
    expect(within(card).getByText("—")).toBeTruthy()
    expect(screen.getByText("За выбранный период нет данных KPI.")).toBeTruthy()
  })
})
