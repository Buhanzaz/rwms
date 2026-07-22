import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  ReturnDocument,
  ReturnLine,
} from "@/features/logistics/returns/model"

const returnApi = vi.hoisted(() => ({
  acceptUndamagedReturn: vi.fn(),
  createReturn: vi.fn(),
  listReturns: vi.fn(),
  registerReturn: vi.fn(),
  requestReturnEstimate: vi.fn(),
}))
const driverDirectoryApi = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))
const authState = vi.hoisted(() => ({
  level: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["logistics", "returns"],
  ...returnApi,
}))

vi.mock("@/features/repair-tasks/api/repair-worker-directory-api", () => ({
  repairWorkerGroupsQueryKey: (query: unknown) => [
    "repair-worker-groups",
    query,
  ],
  listRepairWorkerGroups: driverDirectoryApi.listRepairWorkerGroups,
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: vi.fn().mockResolvedValue([]),
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({
    title,
    onReadyReferencesChange,
  }: {
    title: string
    onReadyReferencesChange?: (
      references: Array<{ mediaId: string; generation: number }>
    ) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onReadyReferencesChange?.([
          {
            mediaId: "88888888-8888-4888-8888-888888888888",
            generation: 1,
          },
        ])
      }
    >
      Подготовить {title}
    </button>
  ),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "return-token",
    currentUser: {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      username: "operator",
      displayName: "Оператор",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: "11111111-1111-4111-8111-111111111111",
          level: authState.level,
        },
      ],
    },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
  }),
}))

import { LogisticsReturnsPage } from "@/features/logistics/logistics-returns-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DOCUMENT_ID = "22222222-2222-4222-8222-222222222222"
const INSPECTION_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const CLIENT_ID = "66666666-6666-4666-8666-666666666666"
const ORDER_ID = "77777777-7777-4777-8777-777777777777"
const IDEMPOTENCY_KEY = "99999999-9999-4999-8999-999999999999"

function returnLine(
  id: string,
  assetId: string,
  lineNumber: number
): ReturnLine {
  return {
    id,
    version: 2,
    lineNumber,
    assetId,
    assetVersion: 8,
    state: "PENDING",
    tenantSnapshot: "ООО Тест",
    rentalOrderId: ORDER_ID,
  }
}

function returnDocument(
  id: string,
  state: ReturnDocument["state"],
  version: number,
  lines: ReturnLine[] = [returnLine(LINE_ID, ASSET_ID, 1)]
): ReturnDocument {
  return {
    id,
    version,
    documentType: "RETURN",
    state,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: null,
    driverSnapshot: null,
    clientId: CLIENT_ID,
    equipmentMovementTaskId: null,
    lines,
    createdAt: "2026-07-18T08:00:00Z",
    updatedAt: "2026-07-18T08:10:00Z",
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <LogisticsReturnsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  authState.level = "EDIT"
  vi.stubGlobal("crypto", { randomUUID: () => IDEMPOTENCY_KEY })
  driverDirectoryApi.listRepairWorkerGroups.mockResolvedValue([
    {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      warehouseId: WAREHOUSE_ID,
      name: "Водители",
      active: true,
      queueCodes: [],
      routeQueueKinds: ["MOVEMENT"],
      members: [
        {
          id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          name: "Иванов Иван",
        },
      ],
    },
  ])
  returnApi.listReturns.mockResolvedValue([
    returnDocument(DOCUMENT_ID, "DRAFT", 2),
    returnDocument(INSPECTION_ID, "INSPECTION_REQUIRED", 4),
  ])
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("LogisticsReturnsPage", () => {
  it("keeps VIEW access read-only while preserving service reads", async () => {
    authState.level = "VIEW"
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    expect(returnApi.listReturns).toHaveBeenCalledWith(
      "return-token",
      WAREHOUSE_ID
    )
    expect(
      screen.queryByRole("button", { name: "Добавить возврат" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Зарегистрировать" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Принять без повреждений" })
    ).toBeNull()
  })

  it("retains the fixed state filter and removes the duplicate add-from-rental control", async () => {
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    expect(
      screen.queryByText(
        "Выберите контрагента и его активную аренду. Список бытовок строится по серверным резервам и текущему статусу аренды."
      )
    ).toBeNull()
    const filter = screen.getByRole("button", { name: "Показать все" })
    expect(filter.className).toContain("w-40")
    expect(filter.className).toContain("shrink-0")
    expect(
      screen.queryByRole("button", { name: /Добавить бытовку из аренды/i })
    ).toBeNull()
    expect(
      screen.getByRole("button", { name: "Добавить возврат" })
    ).toBeTruthy()
    expect(
      screen.getAllByRole("button", { name: "Создать смету" })
    ).not.toHaveLength(0)
  })

  it("opens the configured driver picker for a new return", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Добавить возврат" })
    )

    expect(screen.getByRole("combobox", { name: "Водитель" })).toBeTruthy()
  })

  it("uses the server document version for registration", async () => {
    const user = userEvent.setup()
    returnApi.registerReturn.mockResolvedValue(
      returnDocument(DOCUMENT_ID, "REGISTERING", 3)
    )
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Зарегистрировать" }))[0]!
    )

    await waitFor(() =>
      expect(returnApi.registerReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
  })

  it("sends media and explicit additional-equipment lines to one service command", async () => {
    const user = userEvent.setup()
    returnApi.acceptUndamagedReturn.mockResolvedValue(
      returnDocument(INSPECTION_ID, "ACCEPTING", 5)
    )
    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Принять без повреждений",
        })
      )[0]!
    )
    expect(screen.getByText("Дополнительная мебель")).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Подготовить Фотографии строки 1" })
    )
    await user.click(
      screen.getByRole("button", { name: "Подтвердить приёмку" })
    )

    await waitFor(() =>
      expect(returnApi.acceptUndamagedReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: INSPECTION_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
        lines: [
          {
            lineId: LINE_ID,
            references: [
              {
                mediaId: "88888888-8888-4888-8888-888888888888",
                generation: 1,
              },
            ],
            additionalEquipment: [],
          },
        ],
      })
    )
  })
})
