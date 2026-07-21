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
const authState = vi.hoisted(() => ({
  level: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["logistics", "returns"],
  ...returnApi,
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
          title.endsWith("2")
            ? {
                mediaId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                generation: 2,
              }
            : {
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
const DRAFT_ID = "22222222-2222-4222-8222-222222222222"
const INSPECTION_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const EQUIPMENT_ID = "66666666-6666-4666-8666-666666666666"
const IDEMPOTENCY_KEY = "77777777-7777-4777-8777-777777777777"
const MEDIA_ID = "88888888-8888-4888-8888-888888888888"
const SECOND_LINE_ID = "99999999-9999-4999-8999-999999999999"
const SECOND_ASSET_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const SECOND_EQUIPMENT_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
const SECOND_MEDIA_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
const CREATED_RETURN_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
const UI_KEY_ONE = "12121212-1212-4212-8212-121212121212"
const UI_KEY_TWO = "13131313-1313-4313-8313-131313131313"
const UNUSED_KEY = "14141414-1414-4414-8414-141414141414"

function returnLine(
  id: string,
  assetId: string,
  lineNumber: number
): ReturnLine {
  return {
    id,
    version: 1,
    lineNumber,
    assetId,
    assetVersion: 8,
    state: "PENDING",
    tenantSnapshot: `ООО Тест ${lineNumber}`,
  }
}

const singleLine = returnLine(LINE_ID, ASSET_ID, 1)
const inspectionLines = [
  singleLine,
  returnLine(SECOND_LINE_ID, SECOND_ASSET_ID, 2),
]

function returnDocument(
  id: string,
  state: ReturnDocument["state"],
  version: number,
  lines: ReturnLine[] = [singleLine]
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
    lines,
    createdAt: "2026-07-18T08:00:00Z",
    updatedAt: "2026-07-18T08:10:00Z",
  }
}

function listedDocuments() {
  return [
    returnDocument(DRAFT_ID, "DRAFT", 2),
    returnDocument(INSPECTION_ID, "INSPECTION_REQUIRED", 4, inspectionLines),
  ]
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

async function openAcceptDialog(user: ReturnType<typeof userEvent.setup>) {
  const buttons = await screen.findAllByRole("button", {
    name: "Принять без повреждений",
  })
  await user.click(buttons[0]!)
  await screen.findByRole("heading", {
    name: "Принять возврат без повреждений",
  })
}

async function fillAcceptanceReferences(
  user: ReturnType<typeof userEvent.setup>
) {
  await user.click(
    screen.getByRole("button", { name: "Подготовить Фотографии строки 1" })
  )
  await user.click(
    screen.getByRole("button", { name: "Подготовить Фотографии строки 2" })
  )
}

beforeEach(() => {
  authState.level = "EDIT"
  vi.stubGlobal("crypto", { randomUUID: () => IDEMPOTENCY_KEY })
  returnApi.listReturns.mockResolvedValue(listedDocuments())
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
    expect(screen.queryByRole("button", { name: "Создать возврат" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Зарегистрировать" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Принять без повреждений" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Запросить смету" })).toBeNull()
    expect(screen.getByRole("button", { name: "Обновить" })).not.toBeNull()
  })

  it("uses service projections and exposes no superseded browser-owned controls", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    expect(
      screen.queryByRole("button", { name: /Добавить бытовку из аренды/i })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: /Редактировать/i })).toBeNull()
    expect(document.querySelector('input[type="file"]')).toBeNull()

    await openAcceptDialog(user)
    expect(
      screen.getAllByRole("button", { name: /^Подготовить Фотографии строки/ })
    ).toHaveLength(2)
    expect(
      screen.getByText(/Загрузите фотографии осмотра для каждой строки/)
    ).not.toBeNull()
    expect(document.querySelector('input[type="file"]')).toBeNull()
    await user.click(screen.getByRole("button", { name: "Отмена" }))

    await user.click(screen.getByRole("button", { name: "Создать возврат" }))
    expect(
      screen.getByRole("heading", { name: "Создать документ возврата" })
    ).not.toBeNull()
    expect(screen.getByLabelText("Asset UUID")).not.toBeNull()
    expect(screen.getByLabelText("Текущая версия asset")).not.toBeNull()
    expect(screen.getByLabelText("Снимок контрагента")).not.toBeNull()
  })

  it("creates a return with a stable caller identity and no direct asset mutation", async () => {
    const user = userEvent.setup()
    returnApi.createReturn.mockResolvedValue(
      returnDocument(CREATED_RETURN_ID, "DRAFT", 0)
    )
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать возврат" })
    )
    await user.type(screen.getByLabelText("Asset UUID"), ASSET_ID)
    await user.clear(screen.getByLabelText("Текущая версия asset"))
    await user.type(screen.getByLabelText("Текущая версия asset"), "8")
    await user.type(screen.getByLabelText("Снимок контрагента"), "ООО Тест")
    await user.click(screen.getByRole("button", { name: "Создать черновик" }))

    await waitFor(() =>
      expect(returnApi.createReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        warehouseId: WAREHOUSE_ID,
        idempotencyKey: IDEMPOTENCY_KEY,
        lines: [
          {
            assetId: ASSET_ID,
            assetVersion: 8,
            tenantSnapshot: "ООО Тест",
          },
        ],
      })
    )
  })

  it("rejects duplicate asset IDs before creating a return", async () => {
    const user = userEvent.setup()
    const randomUUID = vi
      .fn()
      .mockReturnValueOnce(UI_KEY_ONE)
      .mockReturnValueOnce(UI_KEY_TWO)
      .mockReturnValue(IDEMPOTENCY_KEY)
    vi.stubGlobal("crypto", { randomUUID })
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать возврат" })
    )
    await user.click(screen.getByRole("button", { name: "Добавить строку" }))

    const assetInputs = screen.getAllByLabelText("Asset UUID")
    const tenantInputs = screen.getAllByLabelText("Снимок контрагента")
    expect(assetInputs).toHaveLength(2)
    await user.type(assetInputs[0]!, SECOND_ASSET_ID)
    await user.type(assetInputs[1]!, SECOND_ASSET_ID.toUpperCase())
    await user.type(tenantInputs[0]!, "ООО Первый")
    await user.type(tenantInputs[1]!, "ООО Второй")
    await user.click(screen.getByRole("button", { name: "Создать черновик" }))

    expect(
      await screen.findByText(
        "Каждый asset UUID можно добавить в документ возврата только один раз."
      )
    ).not.toBeNull()
    expect(returnApi.createReturn).not.toHaveBeenCalled()
  })

  it("registers with the service-issued document version", async () => {
    const user = userEvent.setup()
    returnApi.registerReturn.mockResolvedValue(
      returnDocument(DRAFT_ID, "REGISTERING", 3)
    )
    renderPage()

    const registerButtons = await screen.findAllByRole("button", {
      name: "Зарегистрировать",
    })
    await user.click(registerButtons[0]!)

    await waitFor(() =>
      expect(returnApi.registerReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: DRAFT_ID,
        expectedVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
  })

  it("accepts every server-issued line and reuses the key for an unchanged retry", async () => {
    const user = userEvent.setup()
    const randomUUID = vi
      .fn()
      .mockReturnValueOnce(IDEMPOTENCY_KEY)
      .mockReturnValue(UNUSED_KEY)
    vi.stubGlobal("crypto", { randomUUID })
    returnApi.acceptUndamagedReturn
      .mockRejectedValueOnce(new Error("Временная ошибка media-service"))
      .mockResolvedValueOnce(
        returnDocument(INSPECTION_ID, "ACCEPTING", 5, inspectionLines)
      )
    renderPage()

    await openAcceptDialog(user)
    await fillAcceptanceReferences(user)
    await user.click(
      screen.getByRole("button", { name: "Подтвердить приёмку" })
    )
    await screen.findByText("Временная ошибка media-service")
    await user.click(
      screen.getByRole("button", { name: "Подтвердить приёмку" })
    )

    const expectedCommand = {
      accessToken: "return-token",
      documentId: INSPECTION_ID,
      expectedVersion: 4,
      idempotencyKey: IDEMPOTENCY_KEY,
      lines: [
        {
          lineId: LINE_ID,
          references: [{ mediaId: MEDIA_ID, generation: 1 }],
        },
        {
          lineId: SECOND_LINE_ID,
          references: [{ mediaId: SECOND_MEDIA_ID, generation: 2 }],
        },
      ],
    }
    await waitFor(() =>
      expect(returnApi.acceptUndamagedReturn).toHaveBeenCalledTimes(2)
    )
    expect(returnApi.acceptUndamagedReturn.mock.calls[0]?.[0]).toEqual(
      expectedCommand
    )
    expect(returnApi.acceptUndamagedReturn.mock.calls[1]?.[0]).toEqual(
      expectedCommand
    )
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it("closes a stale accept form, reports 409 and refreshes service state", async () => {
    const user = userEvent.setup()
    returnApi.acceptUndamagedReturn.mockRejectedValue(
      Object.assign(new Error("Версия возврата устарела"), { status: 409 })
    )
    renderPage()

    await openAcceptDialog(user)
    await fillAcceptanceReferences(user)
    await user.click(
      screen.getByRole("button", { name: "Подтвердить приёмку" })
    )

    expect(await screen.findByText("Версия возврата устарела")).not.toBeNull()
    expect(
      screen.queryByRole("heading", {
        name: "Принять возврат без повреждений",
      })
    ).toBeNull()
    await waitFor(() =>
      expect(returnApi.listReturns.mock.calls.length).toBeGreaterThan(1)
    )
    expect(returnApi.acceptUndamagedReturn).toHaveBeenCalledWith(
      expect.objectContaining({
        documentId: INSPECTION_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
  })

  it("requests an estimate for the exact server-issued line set", async () => {
    const user = userEvent.setup()
    returnApi.requestReturnEstimate.mockResolvedValue(
      returnDocument(INSPECTION_ID, "ESTIMATE_PENDING", 5, inspectionLines)
    )
    renderPage()

    const estimateButtons = await screen.findAllByRole("button", {
      name: "Запросить смету",
    })
    await user.click(estimateButtons[0]!)
    const equipmentInputs = screen.getAllByLabelText(/^Equipment UUID ·/)
    expect(equipmentInputs).toHaveLength(2)
    await user.type(equipmentInputs[0]!, EQUIPMENT_ID)
    await user.type(equipmentInputs[1]!, SECOND_EQUIPMENT_ID)
    const quantities = screen.getAllByLabelText(/^Недостающее количество ·/)
    await user.clear(quantities[1]!)
    await user.type(quantities[1]!, "2")
    await user.click(screen.getByRole("button", { name: "Запросить" }))

    await waitFor(() =>
      expect(returnApi.requestReturnEstimate).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: INSPECTION_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
        lines: [
          {
            lineId: LINE_ID,
            shortages: [{ equipmentId: EQUIPMENT_ID, missingQuantity: 1 }],
          },
          {
            lineId: SECOND_LINE_ID,
            shortages: [
              { equipmentId: SECOND_EQUIPMENT_ID, missingQuantity: 2 },
            ],
          },
        ],
      })
    )
  })
})
