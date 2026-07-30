import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type {
  HtmlImport,
  HtmlImportRowsPage,
} from "@/features/rental-items/api/asset-rental-items-api"
import type {
  PageResponse,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const IMPORT_ID = "22222222-2222-4222-8222-222222222222"
const RENTAL_ITEM_ID = "33333333-3333-4333-8333-333333333333"
const RENTAL_TYPE_ID = "33333333-3333-4333-8333-333333333334"
const DIMENSION_ID = "33333333-3333-4333-8333-333333333335"
const FINISHING_ID = "44444444-4444-4444-8444-444444444444"
const MEDIA_JOB_ID = "55555555-5555-4555-8555-555555555556"

const authRuntime = vi.hoisted(() => ({
  globalRole: "WAREHOUSE_MANAGER" as "WAREHOUSE_MANAGER" | "WMS_ADMIN",
}))

const htmlImportApi = vi.hoisted(() => ({
  cancelHtmlImport: vi.fn(),
  createHtmlImport: vi.fn(),
  createIdempotencyKey: vi.fn(() => "55555555-5555-4555-8555-555555555555"),
  getHtmlImport: vi.fn(),
  listAssetRentalItems: vi.fn(),
  listHtmlImportRows: vi.fn(),
  listHtmlImports: vi.fn(),
  replaceHtmlImportMedia: vi.fn(),
  retryHtmlImportMedia: vi.fn(),
  saveHtmlImportPlan: vi.fn(),
  skipHtmlImportMedia: vi.fn(),
  commitHtmlImport: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "html-import-token",
    currentUser: {
      id: "html-import-user",
      username: "operator",
      displayName: "Оператор",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: authRuntime.globalRole,
      rentalAccess: false,
      warehouseAccessAll: false,
      warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "EDIT" }],
    },
  }),
}))

vi.mock(
  "@/features/rental-items/api/asset-rental-items-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/rental-items/api/asset-rental-items-api")
    >()),
    ...htmlImportApi,
  })
)

import { HtmlImportWorkspace } from "@/features/rental-items/html-import/html-import-workspace"

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterAll(() => {
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

const imported: HtmlImport = {
  id: IMPORT_ID,
  warehouseId: WAREHOUSE_ID,
  version: 1,
  state: "REVIEW_REQUIRED",
  diagnostics: [],
  catalogTargets: [
    {
      id: RENTAL_TYPE_ID,
      label: "БК-2",
      kind: "TYPE",
      active: true,
    },
    {
      id: DIMENSION_ID,
      label: "2.4x6",
      kind: "DIMENSION",
      active: true,
    },
    {
      id: FINISHING_ID,
      label: "ЛДСП",
      kind: "FINISHING",
      active: true,
    },
  ],
  catalogMappings: [
    {
      id: "finishing-dvp",
      group: "CATALOG",
      kind: "FINISHING",
      sourceId: "ДВП",
      sourceValue: "ДВП",
      sourceLabel: "ДВП",
      suggestedValue: null,
      occurrences: 1,
      required: true,
      targets: [
        {
          id: FINISHING_ID,
          label: "ЛДСП",
          kind: "FINISHING",
          active: true,
        },
      ],
      suggestedTargetId: null,
      plannedAction: null,
      plannedTargetId: null,
    },
  ],
  equipmentMappings: [],
  statusCandidates: [],
  statusMappings: [],
  summary: {
    sourceRows: 1,
    mappedCatalogValues: null,
    mappedEquipmentValues: null,
    createRows: null,
    mergeRows: null,
    excludedRows: null,
    committedRows: null,
    mediaPendingRows: null,
  },
  mediaJobId: null,
  failureCode: null,
  createdAt: "2026-07-29T10:00:00Z",
  updatedAt: "2026-07-29T10:00:00Z",
}

const rows: HtmlImportRowsPage = {
  content: [
    {
      id: "import-row-1",
      sourceRowId: "legacy-row-1",
      number: "БЫТ-042",
      proposedNumber: "БЫТ-042",
      source: {
        id: null,
        version: null,
        label: "БЫТ-042",
        fields: {
          rentalType: "БК-2",
          dimensions: "2.4x6",
          finishing: "ДВП",
          comment: "Комментарий из HTML",
        },
        mediaCount: 1,
      },
      existing: {
        id: RENTAL_ITEM_ID,
        version: 3,
        label: "БЫТ-042",
        fields: {
          rentalType: "БК-2",
          dimensions: "2.4x6",
          finishing: "ЛДСП",
          comment: "Текущий комментарий",
        },
        mediaCount: 4,
      },
      suggestedAction: "MERGE",
      plannedAction: null,
      plannedExistingRentalItemId: RENTAL_ITEM_ID,
      plannedRentalTypeId: null,
      plannedDimensionId: null,
      plannedFinishingId: null,
      fieldDecisions: {
        rentalType: "TARGET",
        dimensions: "TARGET",
      },
      diagnostics: [],
    },
  ],
  page: 0,
  size: 100,
  totalElements: 1,
  totalPages: 1,
}

const systemCabin: RentalItemDto = {
  id: RENTAL_ITEM_ID,
  version: 3,
  warehouseId: WAREHOUSE_ID,
  number: "БЫТ-042",
  rentalTypeId: RENTAL_TYPE_ID,
  dimensionId: DIMENSION_ID,
  finishingId: FINISHING_ID,
  type: "БК-2",
  dimensions: "2.4x6",
  finishing: "ЛДСП",
  category: null,
  characteristics: [],
  linoleum: false,
  status: "WAREHOUSE",
  comment: "Текущий комментарий",
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
  passport: {},
  tags: [],
}

const systemCabinsPage: PageResponse<RentalItemDto> = {
  content: [systemCabin],
  page: 0,
  size: 200,
  totalElements: 1,
  totalPages: 1,
}

function renderWorkspace() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const onOpenChange = vi.fn()

  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <HtmlImportWorkspace
        open
        warehouseId={WAREHOUSE_ID}
        warehouseName="СПБ2"
        onOpenChange={onOpenChange}
      />
    </QueryClientProvider>
  )

  return { ...rendered, onOpenChange }
}

function importAtState(
  state: HtmlImport["state"],
  overrides: Partial<HtmlImport> = {}
): HtmlImport {
  return { ...imported, ...overrides, state }
}

function renderResumedWorkspace(importRecord: HtmlImport) {
  htmlImportApi.listHtmlImports.mockResolvedValue([importRecord])
  htmlImportApi.getHtmlImport.mockResolvedValue(importRecord)
  return renderWorkspace()
}

beforeEach(() => {
  vi.clearAllMocks()
  authRuntime.globalRole = "WAREHOUSE_MANAGER"
  htmlImportApi.listHtmlImports.mockResolvedValue([])
  htmlImportApi.cancelHtmlImport.mockResolvedValue(undefined)
  htmlImportApi.createHtmlImport.mockResolvedValue(imported)
  htmlImportApi.getHtmlImport.mockResolvedValue(imported)
  htmlImportApi.listAssetRentalItems.mockResolvedValue(systemCabinsPage)
  htmlImportApi.listHtmlImportRows.mockResolvedValue(rows)
})

afterEach(cleanup)

describe("HtmlImportWorkspace", () => {
  it("cancels a draft only after confirmation and returns to a new import", async () => {
    const user = userEvent.setup()
    htmlImportApi.listHtmlImports
      .mockResolvedValueOnce([imported])
      .mockResolvedValue([])
    htmlImportApi.getHtmlImport.mockResolvedValue(imported)
    renderWorkspace()

    await user.click(
      await screen.findByRole("button", { name: "Удалить импорт" })
    )
    const confirmation = await screen.findByRole("alertdialog", {
      name: "Удалить текущий импорт?",
    })
    expect(htmlImportApi.cancelHtmlImport).not.toHaveBeenCalled()

    await user.click(
      within(confirmation).getByRole("button", { name: "Удалить импорт" })
    )

    await waitFor(() =>
      expect(htmlImportApi.cancelHtmlImport).toHaveBeenCalledWith({
        accessToken: "html-import-token",
        importId: IMPORT_ID,
        expectedVersion: 1,
      })
    )
    expect(
      await screen.findByRole("textbox", { name: "HTML для импорта" })
    ).toBeTruthy()
  })

  it("submits pasted HTML, keeps an accessible dialog title and supports click pairing", async () => {
    const user = userEvent.setup()
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(
      importAtState("READY", { version: 2 })
    )
    renderWorkspace()

    expect(
      screen.getByRole("dialog", { name: "Импортировать из HTML" })
    ).toBeTruthy()
    expect(screen.getByText("Склад назначения: СПБ2")).toBeTruthy()
    const source = screen.getByRole("textbox", { name: "HTML для импорта" })
    await user.type(source, "<html><body>legacy cabin</body></html>")
    await user.click(screen.getByRole("button", { name: "Далее" }))

    await waitFor(() =>
      expect(htmlImportApi.createHtmlImport).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "html-import-token",
          warehouseId: WAREHOUSE_ID,
          idempotencyKey: "55555555-5555-4555-8555-555555555555",
        })
      )
    )
    const createInput = htmlImportApi.createHtmlImport.mock.calls[0][0] as {
      html: Blob
    }
    expect(await createInput.html.text()).toContain("legacy cabin")

    await user.click(
      await screen.findByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )
    const sourceMapping = screen.getByRole("button", { name: "ДВП" })
    await user.click(sourceMapping)
    expect(sourceMapping.getAttribute("aria-pressed")).toBe("true")
    const target = screen.getByRole("button", { name: "ЛДСП" })
    await user.click(target)
    expect(
      screen.getByText(
        "Все 1 значений уже сопоставлены. Совпадающие пары не требуют ручного решения и скрыты."
      )
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "ДВП" })).toBeNull()

    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )
    expect(
      screen.getAllByRole("columnheader", { name: "Отделка" })
    ).toHaveLength(2)
    expect(screen.getByRole("cell", { name: "ДВП" })).toBeTruthy()
    expect(screen.getByRole("cell", { name: "ЛДСП" })).toBeTruthy()
    const htmlRow = await screen.findByRole("button", {
      name: "Выбрать HTML: БЫТ-042",
    })
    await user.click(htmlRow)
    await user.click(
      screen.getByRole("button", {
        name: "Выбрать в системе: БЫТ-042",
      })
    )
    expect(
      screen.queryByRole("button", { name: "Выбрать HTML: БЫТ-042" })
    ).toBeNull()
    expect(screen.getByText("БЫТ-042 — связана с БЫТ-042")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "К итогу" }))
    await user.click(
      await screen.findByRole("button", { name: "Сохранить план" })
    )
    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          rows: [
            expect.objectContaining({
              sourceRowId: "legacy-row-1",
              action: "MERGE",
              targetRentalItemId: RENTAL_ITEM_ID,
              targetExpectedVersion: 3,
              mergeChoices: expect.objectContaining({
                rentalType: "TARGET",
                dimension: "TARGET",
                finishing: "SOURCE",
                comment: "SOURCE",
              }),
            }),
          ],
        })
      )
    )
  })

  it("keeps optional HTML composition and equipment on the matched warehouse cabin", async () => {
    const user = userEvent.setup()
    const optionalMergeRows: HtmlImportRowsPage = {
      ...rows,
      content: [
        {
          ...rows.content[0],
          fieldDecisions: {},
          source: {
            ...rows.content[0].source,
            fields: {
              rentalType: "Тип из HTML",
              dimensions: "3.0x7.0",
              finishing: "ЛДСП",
              category: "Категория из HTML",
              characteristics: "Характеристика из HTML",
              furniture: "Стол из HTML × 2",
            },
          },
          existing: {
            ...rows.content[0].existing!,
            fields: {
              rentalType: "Тип на складе",
              dimensions: "2.4x6",
              finishing: "ЛДСП",
              category: "Категория на складе",
              characteristics: "Характеристика на складе",
              furniture: "Стол на складе × 1",
            },
          },
        },
      ],
    }
    const reviewRequired = importAtState("REVIEW_REQUIRED")
    htmlImportApi.getHtmlImport.mockResolvedValue(reviewRequired)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(optionalMergeRows)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(
      importAtState("READY", { version: 2 })
    )
    renderResumedWorkspace(reviewRequired)

    await user.click(
      await screen.findByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )
    await user.click(screen.getByRole("button", { name: "К итогу" }))

    expect(
      screen.queryByText(
        /выберите источник поля «(?:Тип|Габариты|Категория|Характеристики|Комплектация)»/iu
      )
    ).toBeNull()
    const savePlan = await screen.findByRole("button", {
      name: "Сохранить план",
    })
    expect(savePlan).toHaveProperty("disabled", false)
    await user.click(savePlan)

    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          rows: [
            expect.objectContaining({
              sourceRowId: "legacy-row-1",
              action: "MERGE",
              mergeChoices: expect.objectContaining({
                rentalType: "TARGET",
                dimension: "TARGET",
                dimensions: "TARGET",
                category: "TARGET",
                characteristics: "TARGET",
                furniture: "TARGET",
              }),
            }),
          ],
        })
      )
    )
  })

  it("does not require incomplete optional mappings when creating a cabin", async () => {
    const user = userEvent.setup()
    const optionalMappings = [
      ["optional-type", "TYPE", "Тип из HTML"],
      ["optional-dimension", "DIMENSION", "Габариты из HTML"],
      ["optional-category", "CATEGORY", "Категория из HTML"],
      ["optional-characteristic", "CHARACTERISTIC", "Характеристика из HTML"],
    ].map(([id, kind, sourceValue]) => ({
      ...imported.catalogMappings[0],
      id,
      kind,
      sourceId: sourceValue,
      sourceValue,
      sourceLabel: sourceValue,
      required: true,
      targets: [],
      suggestedTargetId: null,
      plannedAction: "MAP" as const,
      plannedTargetId: null,
    }))
    const importWithOptionalMappings = importAtState("REVIEW_REQUIRED", {
      catalogMappings: optionalMappings,
      equipmentMappings: [
        {
          id: "optional-equipment",
          group: "EQUIPMENT",
          kind: null,
          sourceId: "Стол из HTML",
          sourceValue: "Стол из HTML",
          sourceLabel: "Стол из HTML",
          suggestedValue: null,
          occurrences: 1,
          required: true,
          targets: [],
          suggestedTargetId: null,
          plannedAction: "MAP",
          plannedTargetId: null,
        },
      ],
    })
    const createRows: HtmlImportRowsPage = {
      ...rows,
      content: [
        {
          ...rows.content[0],
          id: "optional-create-row",
          sourceRowId: "legacy-optional-create-row",
          number: "БЫТ-048",
          proposedNumber: "БЫТ-048",
          source: {
            ...rows.content[0].source,
            label: "БЫТ-048",
            fields: {
              rentalType: "Тип из HTML",
              dimensions: "Габариты из HTML",
              category: "Категория из HTML",
              characteristics: "Характеристика из HTML",
              furniture: "Стол из HTML × 1",
              finishing: "ЛДСП",
              status: "WAREHOUSE",
            },
          },
          existing: null,
          suggestedAction: "CREATE",
          plannedAction: "CREATE",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [],
        },
      ],
    }
    htmlImportApi.getHtmlImport.mockResolvedValue(importWithOptionalMappings)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(createRows)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(
      importAtState("READY", { ...importWithOptionalMappings, version: 2 })
    )
    renderResumedWorkspace(importWithOptionalMappings)

    await user.click(
      await screen.findByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )
    await user.click(screen.getByRole("button", { name: "К итогу" }))

    const savePlan = await screen.findByRole("button", {
      name: "Сохранить план",
    })
    expect(savePlan).toHaveProperty("disabled", false)
    await user.click(savePlan)

    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          catalogMappings: expect.arrayContaining(
            optionalMappings.map((mapping) =>
              expect.objectContaining({
                sourceValue: mapping.sourceValue,
                action: "IGNORE",
              })
            )
          ),
          equipmentMappings: [
            expect.objectContaining({
              sourceValue: "Стол из HTML",
              action: "IGNORE",
            }),
          ],
          rows: [
            expect.objectContaining({
              sourceRowId: "legacy-optional-create-row",
              action: "CREATE",
            }),
          ],
        })
      )
    )
    const savedPlan = htmlImportApi.saveHtmlImportPlan.mock.calls[0][0] as {
      rows: Array<Record<string, unknown>>
    }
    expect(savedPlan.rows[0]).not.toHaveProperty("rentalTypeId")
    expect(savedPlan.rows[0]).not.toHaveProperty("dimensionId")
    expect(savedPlan.rows[0]).not.toHaveProperty("categoryId")
    expect(savedPlan.rows[0]).not.toHaveProperty("characteristicIds")
  })

  it("hides a unique catalog match when at least 60 percent of words coincide", async () => {
    const user = userEvent.setup()
    const fuzzyImport = importAtState("REVIEW_REQUIRED", {
      catalogMappings: [
        {
          ...imported.catalogMappings[0],
          id: "type-guard-post",
          kind: "TYPE",
          sourceId: "legacy-guard-post",
          sourceValue: "Пост охраны",
          sourceLabel: "Пост охраны",
          suggestedValue: null,
          targets: [
            {
              id: RENTAL_TYPE_ID,
              label: "БК-Пост охраны",
              kind: "TYPE",
              active: true,
            },
          ],
          suggestedTargetId: null,
          plannedAction: null,
          plannedTargetId: null,
        },
      ],
    })
    renderResumedWorkspace(fuzzyImport)

    await user.click(
      await screen.findByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )

    expect(
      screen.getByText(
        "Все 1 значений уже сопоставлены. Совпадающие пары не требуют ручного решения и скрыты."
      )
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Пост охраны" })).toBeNull()
  })

  it("resolves an unknown source status to an allowed status and never saves the raw label", async () => {
    const user = userEvent.setup()
    const importWithStatuses = importAtState("REVIEW_REQUIRED", {
      statusCandidates: [
        {
          id: "status-conservation",
          sourceValue: "Консервация",
          sourceLabel: "Консервация",
          suggestedTargetStatus: null,
          occurrences: 1,
          required: true,
          plannedTargetStatus: null,
        },
        {
          id: "status-warehouse",
          sourceValue: "На складе",
          sourceLabel: "На складе",
          suggestedTargetStatus: "WAREHOUSE",
          occurrences: 1,
          required: true,
          plannedTargetStatus: null,
        },
      ],
      statusMappings: [],
    })
    const rowsWithUnknownStatus: HtmlImportRowsPage = {
      ...rows,
      content: [
        {
          ...rows.content[0],
          id: "import-row-unknown-status",
          sourceRowId: "legacy-row-unknown-status",
          number: "БЫТ-043",
          proposedNumber: "БЫТ-043",
          source: {
            ...rows.content[0].source,
            label: "БЫТ-043",
            fields: {
              rentalType: "БК-2",
              dimension: "2.4x6",
              finishing: "ДВП",
              status: "Консервация",
            },
          },
          existing: null,
          suggestedAction: "CREATE",
          plannedAction: "CREATE",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [
            {
              severity: "ERROR",
              code: "STATUS_REQUIRES_MAPPING",
              field: "UF_STATUS_ID",
            },
          ],
        },
      ],
    }
    const readyWithStatuses = importAtState("READY", {
      ...importWithStatuses,
      version: 2,
    })
    htmlImportApi.createHtmlImport.mockResolvedValue(importWithStatuses)
    htmlImportApi.getHtmlImport.mockResolvedValue(importWithStatuses)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(rowsWithUnknownStatus)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(readyWithStatuses)
    renderWorkspace()

    await user.type(
      screen.getByRole("textbox", { name: "HTML для импорта" }),
      "<html><body>unknown status</body></html>"
    )
    await user.click(screen.getByRole("button", { name: "Далее" }))

    expect(await screen.findByText("Статус не распознан")).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Ручное разрешение конфликтов" })
    )

    const automaticallyMappedTarget = screen.getByRole("button", {
      name: "Склад",
    })
    expect(automaticallyMappedTarget.getAttribute("data-variant")).toBe(
      "outline"
    )
    expect(screen.queryByRole("button", { name: "На складе" })).toBeNull()
    expect(screen.queryByRole("button", { name: "В перемещении" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Списана" })).toBeNull()

    await user.click(screen.getByRole("button", { name: "Консервация" }))
    await user.click(automaticallyMappedTarget)
    expect(
      screen.getByText(
        "Все 2 статусов уже распознаны и сопоставлены. Совпадающие пары скрыты."
      )
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )
    expect(
      await screen.findByText(
        "Все конфликтные строки HTML уже обработаны."
      )
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать HTML: БЫТ-043" })
    ).toBeNull()
    await user.click(screen.getByRole("button", { name: "К итогу" }))

    const savePlan = await screen.findByRole("button", {
      name: "Сохранить план",
    })
    expect(savePlan).toHaveProperty("disabled", false)
    await user.click(savePlan)

    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          statusMappings: expect.arrayContaining([
            { sourceValue: "Консервация", targetStatus: "WAREHOUSE" },
            { sourceValue: "На складе", targetStatus: "WAREHOUSE" },
          ]),
          rows: [
            expect.objectContaining({
              sourceRowId: "legacy-row-unknown-status",
              status: "WAREHOUSE",
            }),
          ],
        })
      )
    )
    const savedPlan = htmlImportApi.saveHtmlImportPlan.mock.calls[0][0] as {
      rows: Array<{ status?: string }>
    }
    expect(savedPlan.rows[0]?.status).not.toBe("Консервация")
  })

  it("loads every row page before showing exact conflict and skipped-photo counts", async () => {
    const firstPage: HtmlImportRowsPage = {
      ...rows,
      content: [
        {
          ...rows.content[0],
          diagnostics: [],
        },
      ],
      page: 0,
      totalElements: 2,
      totalPages: 2,
    }
    const secondPage: HtmlImportRowsPage = {
      ...rows,
      content: [
        {
          ...rows.content[0],
          id: "import-row-2",
          sourceRowId: "legacy-row-2",
          number: "БЫТ-044",
          diagnostics: [
            {
              severity: "WARNING",
              code: "INVALID_YANDEX_PUBLIC_URL",
              field: "UF_PHOTO_URL",
            },
          ],
        },
      ],
      page: 1,
      totalElements: 2,
      totalPages: 2,
    }
    htmlImportApi.listHtmlImportRows
      .mockResolvedValueOnce(firstPage)
      .mockResolvedValueOnce(secondPage)
    renderResumedWorkspace(importAtState("REVIEW_REQUIRED"))

    expect(
      await screen.findByText("Битые ссылки на Яндекс‑фото пропущены: 1")
    ).toBeTruthy()
    await waitFor(() =>
      expect(htmlImportApi.listHtmlImportRows).toHaveBeenCalledTimes(2)
    )
    expect(
      screen.getByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    ).toHaveProperty("disabled", false)
  })

  it("uses dashes for unresolved catalog values, changes an invalid number, and skips broken Yandex photos", async () => {
    const user = userEvent.setup()
    const importWithConflicts = importAtState("REVIEW_REQUIRED", {
      catalogMappings: [
        {
          ...imported.catalogMappings[0],
          id: "unknown-type",
          kind: "TYPE",
          sourceId: "Неизвестный тип",
          sourceValue: "Неизвестный тип",
          sourceLabel: "Неизвестный тип",
          targets: [
            {
              id: RENTAL_TYPE_ID,
              label: "БК-2",
              kind: "TYPE",
              active: true,
            },
          ],
        },
        {
          ...imported.catalogMappings[0],
          id: "unknown-finishing",
          kind: "FINISHING",
          sourceId: "Неизвестная отделка",
          sourceValue: "Неизвестная отделка",
          sourceLabel: "Неизвестная отделка",
          targets: [
            {
              id: FINISHING_ID,
              label: "ЛДСП",
              kind: "FINISHING",
              active: true,
            },
          ],
        },
      ],
      equipmentMappings: [],
      summary: {
        ...imported.summary,
        sourceRows: 2,
      },
    })
    const rowsWithConflicts: HtmlImportRowsPage = {
      content: [
        {
          ...rows.content[0],
          id: "import-row-catalog-conflicts",
          sourceRowId: "legacy-row-catalog-conflicts",
          number: "БЫТ-046",
          proposedNumber: "БЫТ-046",
          source: {
            ...rows.content[0].source,
            label: "БЫТ-046",
            fields: {
              rentalType: "Неизвестный тип",
              dimension: "Неизвестный размер",
              finishing: "Неизвестная отделка",
              status: "WAREHOUSE",
            },
          },
          existing: null,
          suggestedAction: "CREATE",
          plannedAction: "CREATE",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [
            {
              severity: "ERROR",
              code: "TYPE_REQUIRES_MAPPING",
              field: "UF_TYPE_ID",
            },
            {
              severity: "ERROR",
              code: "DIMENSION_REQUIRES_MAPPING",
              field: "UF_GABARIT_ID",
            },
            {
              severity: "ERROR",
              code: "FINISHING_REQUIRES_MAPPING",
              field: "UF_OTDELKA_ID",
            },
            {
              severity: "WARNING",
              code: "INVALID_YANDEX_PUBLIC_URL",
              field: "UF_PHOTO_URL",
            },
          ],
        },
        {
          ...rows.content[0],
          id: "import-row-invalid-number",
          sourceRowId: "legacy-row-invalid-number",
          number: "???",
          proposedNumber: null,
          source: {
            ...rows.content[0].source,
            label: "???",
          },
          existing: null,
          suggestedAction: "REVIEW",
          plannedAction: "REVIEW",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [
            {
              severity: "ERROR",
              code: "INVALID_RENTAL_NUMBER",
              field: "number",
            },
          ],
        },
      ],
      page: 0,
      size: 200,
      totalElements: 2,
      totalPages: 1,
    }
    const ready = importAtState("READY", {
      ...importWithConflicts,
      version: 2,
    })
    htmlImportApi.createHtmlImport.mockResolvedValue(importWithConflicts)
    htmlImportApi.getHtmlImport.mockResolvedValue(importWithConflicts)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(rowsWithConflicts)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(ready)
    renderWorkspace()

    await user.type(
      screen.getByRole("textbox", { name: "HTML для импорта" }),
      "<html><body>manual conflicts</body></html>"
    )
    await user.click(screen.getByRole("button", { name: "Далее" }))

    expect(
      await screen.findByText("Некорректные номера — отдельно")
    ).toBeTruthy()
    expect(
      screen.getByText("Битые ссылки на Яндекс‑фото пропущены: 1")
    ).toBeTruthy()
    expect(
      screen.getByText("Размер не распознан").parentElement?.textContent
    ).toContain("0")
    expect(
      screen.getByText("Отделка не распознана").parentElement?.textContent
    ).toContain("0")
    await user.click(
      screen.getByRole("button", { name: "Ручное разрешение конфликтов" })
    )
    expect(
      screen.getByText(
        /Ничего выбирать не обязательно: нераспознанные тип, размер и отделка получат «—»/
      )
    ).toBeTruthy()
    expect(
      screen.getAllByRole("radio", { name: "Оставить —" })
    ).toHaveLength(2)
    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )

    expect(screen.getByText("Сопоставление бытовок")).toBeTruthy()
    expect(
      screen.queryByRole("button", {
        name: "Выбрать HTML: БЫТ-046",
      })
    ).toBeNull()
    await user.click(
      await screen.findByRole("button", {
        name: "Изменить номер",
      })
    )
    const numberDialog = await screen.findByRole("dialog", {
      name: "Изменить номер бытовки",
    })
    const numberInput = within(numberDialog).getByRole("textbox", {
      name: "Новый номер",
    })
    await user.clear(numberInput)
    await user.type(numberInput, "БЫТ-047")
    await user.click(
      within(numberDialog).getByRole("button", {
        name: "Изменить номер и создать",
      })
    )
    expect(
      screen.getByText("??? — будет создана с номером БЫТ-047")
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", {
        name: "Выбрать в системе: БЫТ-042",
      })
    )
    await user.click(
      screen.getAllByRole("button", {
        name: "Пропустить выбранную строку",
      })[1]
    )
    await user.click(screen.getByRole("button", { name: "К итогу" }))

    expect(screen.getByText("Пропущенные записи")).toBeTruthy()
    expect(
      screen.queryByText("Строка legacy-row-invalid-number")
    ).toBeNull()
    expect(screen.getByText("Пропущена в системе")).toBeTruthy()
    const savePlan = screen.getByRole("button", { name: "Сохранить план" })
    expect(savePlan).toHaveProperty("disabled", false)
    await user.click(savePlan)

    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          catalogMappings: expect.arrayContaining([
            expect.objectContaining({
              sourceValue: "Неизвестный тип",
              action: "IGNORE",
            }),
            expect.objectContaining({
              sourceValue: "Неизвестная отделка",
              action: "IGNORE",
            }),
          ]),
          rows: [
            expect.objectContaining({
              sourceRowId: "legacy-row-catalog-conflicts",
              action: "CREATE",
            }),
            expect.objectContaining({
              sourceRowId: "legacy-row-invalid-number",
              action: "CREATE",
              proposedNumber: "БЫТ-047",
            }),
          ],
        })
      )
    )
  })

  it("adds all remaining HTML conflicts in one action and keeps their numbers", async () => {
    const user = userEvent.setup()
    const bulkImport = importAtState("REVIEW_REQUIRED", {
      summary: {
        ...imported.summary,
        sourceRows: 2,
      },
    })
    const bulkRows: HtmlImportRowsPage = {
      content: ["БЫТ-050", "БЫТ-051"].map((number, index) => ({
        ...rows.content[0],
        id: `bulk-row-${index}`,
        sourceRowId: `legacy-bulk-row-${index}`,
        number,
        proposedNumber: number,
        source: {
          ...rows.content[0].source,
          label: number,
        },
        existing: null,
        suggestedAction: "REVIEW",
        plannedAction: "REVIEW",
        plannedExistingRentalItemId: null,
        fieldDecisions: {},
        diagnostics: [
          {
            severity: "ERROR" as const,
            code: "SOURCE_ROW_REQUIRES_REVIEW",
            field: null,
          },
        ],
      })),
      page: 0,
      size: 200,
      totalElements: 2,
      totalPages: 1,
    }
    htmlImportApi.createHtmlImport.mockResolvedValue(bulkImport)
    htmlImportApi.getHtmlImport.mockResolvedValue(bulkImport)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(bulkRows)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(
      importAtState("READY", { ...bulkImport, version: 2 })
    )
    renderWorkspace()

    await user.type(
      screen.getByRole("textbox", { name: "HTML для импорта" }),
      "<html><body>bulk conflicts</body></html>"
    )
    await user.click(screen.getByRole("button", { name: "Далее" }))
    await user.click(
      await screen.findByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(screen.getByRole("button", { name: "ЛДСП" }))
    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )

    await user.click(
      await screen.findByRole("button", {
        name: "Добавить все из HTML (2)",
      })
    )
    expect(
      screen.getByText("Все оставшиеся строки добавлены в план: 2.")
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать HTML: БЫТ-050" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выбрать HTML: БЫТ-051" })
    ).toBeNull()

    await user.click(screen.getByRole("button", { name: "К итогу" }))
    await user.click(
      await screen.findByRole("button", { name: "Сохранить план" })
    )
    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          rows: [
            expect.objectContaining({
              sourceRowId: "legacy-bulk-row-0",
              action: "CREATE",
              proposedNumber: "БЫТ-050",
            }),
            expect.objectContaining({
              sourceRowId: "legacy-bulk-row-1",
              action: "CREATE",
              proposedNumber: "БЫТ-051",
            }),
          ],
        })
      )
    )
  })

  it("blocks duplicate planned numbers and lets the operator correct one before saving", async () => {
    const user = userEvent.setup()
    const duplicateImport = importAtState("REVIEW_REQUIRED", {
      summary: {
        ...imported.summary,
        sourceRows: 2,
      },
    })
    const duplicateRows: HtmlImportRowsPage = {
      content: [
        {
          ...rows.content[0],
          id: "duplicate-row-renumbered",
          sourceRowId: "legacy-row-renumbered",
          number: "241138 — заменить на 250633",
          proposedNumber: "250633",
          source: {
            ...rows.content[0].source,
            label: "241138 — заменить на 250633",
          },
          existing: null,
          suggestedAction: "CREATE",
          plannedAction: "CREATE",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [],
        },
        {
          ...rows.content[0],
          id: "duplicate-row-existing-plan",
          sourceRowId: "legacy-row-existing-plan",
          number: "250633",
          proposedNumber: "250633",
          source: {
            ...rows.content[0].source,
            label: "250633",
          },
          existing: null,
          suggestedAction: "CREATE",
          plannedAction: "CREATE",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [],
        },
      ],
      page: 0,
      size: 200,
      totalElements: 2,
      totalPages: 1,
    }
    htmlImportApi.createHtmlImport.mockResolvedValue(duplicateImport)
    htmlImportApi.getHtmlImport.mockResolvedValue(duplicateImport)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(duplicateRows)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(
      importAtState("READY", { ...duplicateImport, version: 2 })
    )
    renderWorkspace()

    await user.type(
      screen.getByRole("textbox", { name: "HTML для импорта" }),
      "<html><body>duplicate numbers</body></html>"
    )
    await user.click(screen.getByRole("button", { name: "Далее" }))

    expect(
      await screen.findByText("Повторяющиеся номера — требуется решение")
    ).toBeTruthy()
    expect(screen.getByText("250633")).toBeTruthy()
    await user.click(
      screen.getByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )

    expect(
      await screen.findByText("Повторяющиеся номера в плане")
    ).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Выбрать HTML: 241138 — заменить на 250633",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Выбрать HTML: 250633" })
    ).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "К итогу" }))
    const blockedSave = await screen.findByRole("button", {
      name: "Сохранить план",
    })
    expect(blockedSave).toHaveProperty("disabled", true)
    expect(
      screen.getByText(
        /Номер «250633» выбран для нескольких новых бытовок/
      )
    ).toBeTruthy()

    await user.click(screen.getAllByRole("button", { name: "Назад" })[1])
    await user.click(
      screen.getAllByRole("button", { name: "Изменить номер" })[0]
    )
    const numberDialog = await screen.findByRole("dialog", {
      name: "Изменить номер бытовки",
    })
    const numberInput = within(numberDialog).getByRole("textbox", {
      name: "Новый номер",
    })
    expect(
      within(numberDialog).getByText(/повторяется в плане импорта/)
    ).toBeTruthy()
    await user.clear(numberInput)
    await user.type(numberInput, "250634")
    await user.click(
      within(numberDialog).getByRole("button", {
        name: "Изменить номер и создать",
      })
    )

    expect(screen.queryByText("Повторяющиеся номера в плане")).toBeNull()
    await user.click(screen.getByRole("button", { name: "К итогу" }))
    const savePlan = await screen.findByRole("button", {
      name: "Сохранить план",
    })
    expect(savePlan).toHaveProperty("disabled", false)
    await user.click(savePlan)

    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          rows: expect.arrayContaining([
            expect.objectContaining({
              sourceRowId: "legacy-row-renumbered",
              action: "CREATE",
              proposedNumber: "250634",
            }),
            expect.objectContaining({
              sourceRowId: "legacy-row-existing-plan",
              action: "CREATE",
              proposedNumber: "250633",
            }),
          ]),
        })
      )
    )
  })

  it("saves suggested and manually edited staged names instead of verbose HTML labels", async () => {
    const user = userEvent.setup()
    const verboseType = "Блок-контейнер № 7, 6,0 м ДВП"
    const verboseEquipment = "Стол из старой панели"
    const importWithStagedNames = importAtState("REVIEW_REQUIRED", {
      catalogMappings: [
        {
          ...imported.catalogMappings[0],
          id: "type-legacy-bk7",
          kind: "TYPE",
          sourceId: "legacy-type-bk7",
          sourceValue: verboseType,
          sourceLabel: verboseType,
          suggestedValue: "БК-7",
          targets: [],
          suggestedTargetId: null,
          plannedAction: null,
          plannedTargetId: null,
        },
      ],
      equipmentMappings: [
        {
          id: "equipment-legacy-table",
          group: "EQUIPMENT",
          kind: null,
          sourceId: "legacy-equipment-table",
          sourceValue: verboseEquipment,
          sourceLabel: verboseEquipment,
          suggestedValue: "Стол",
          occurrences: 1,
          required: false,
          targets: [],
          suggestedTargetId: null,
          plannedAction: null,
          plannedTargetId: null,
        },
      ],
    })
    const rowsForStagedNames: HtmlImportRowsPage = {
      ...rows,
      content: [
        {
          ...rows.content[0],
          id: "import-row-staged-names",
          sourceRowId: "legacy-row-staged-names",
          number: "БЫТ-045",
          proposedNumber: "БЫТ-045",
          source: {
            ...rows.content[0].source,
            label: "БЫТ-045",
            fields: {
              rentalType: verboseType,
              dimension: "2.4x6",
              finishing: "ДВП",
              status: "WAREHOUSE",
            },
          },
          existing: null,
          suggestedAction: "CREATE",
          plannedAction: "CREATE",
          plannedExistingRentalItemId: null,
          fieldDecisions: {},
          diagnostics: [],
        },
      ],
    }
    const readyWithStagedNames = importAtState("READY", {
      ...importWithStagedNames,
      version: 2,
    })
    htmlImportApi.createHtmlImport.mockResolvedValue(importWithStagedNames)
    htmlImportApi.getHtmlImport.mockResolvedValue(importWithStagedNames)
    htmlImportApi.listHtmlImportRows.mockResolvedValue(rowsForStagedNames)
    htmlImportApi.saveHtmlImportPlan.mockResolvedValue(readyWithStagedNames)
    renderWorkspace()

    await user.type(
      screen.getByRole("textbox", { name: "HTML для импорта" }),
      "<html><body>staged names</body></html>"
    )
    await user.click(screen.getByRole("button", { name: "Далее" }))
    await user.click(
      await screen.findByRole("button", {
        name: "Ручное разрешение конфликтов",
      })
    )

    await user.click(screen.getAllByRole("radio", { name: "Создать" })[0])
    await user.click(screen.getAllByRole("radio", { name: "Создать" })[1])
    const typeName = screen.getByRole("textbox", {
      name: `Имя нового значения для ${verboseType}`,
    }) as HTMLInputElement
    expect(typeName).toHaveProperty("value", "БК-7")
    await user.clear(typeName)
    expect(
      screen.getByText("Укажите непустое имя для нового значения.")
    ).toBeTruthy()
    await user.type(typeName, "БК-7")
    const equipmentName = screen.getByRole("textbox", {
      name: `Имя нового значения для ${verboseEquipment}`,
    })
    await user.clear(equipmentName)
    await user.type(equipmentName, "Стол складной")

    await user.click(
      screen.getByRole("button", { name: "К конфликтам по бытовкам" })
    )
    await user.click(screen.getByRole("button", { name: "К итогу" }))
    await user.click(
      await screen.findByRole("button", { name: "Сохранить план" })
    )

    await waitFor(() =>
      expect(htmlImportApi.saveHtmlImportPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          catalogMappings: [
            expect.objectContaining({
              sourceValue: verboseType,
              action: "CREATE",
              stagedName: "БК-7",
            }),
          ],
          equipmentMappings: [
            expect.objectContaining({
              sourceValue: verboseEquipment,
              action: "CREATE",
              stagedName: "Стол складной",
            }),
          ],
        })
      )
    )
    const savedPlan = htmlImportApi.saveHtmlImportPlan.mock.calls[0][0] as {
      catalogMappings: Array<{ stagedName?: string }>
    }
    expect(savedPlan.catalogMappings[0]?.stagedName).not.toBe(verboseType)
  })

  it("resumes REVIEW_REQUIRED in diagnostics and READY at the import summary", async () => {
    const reviewRequired = importAtState("REVIEW_REQUIRED")
    const { unmount } = renderResumedWorkspace(reviewRequired)

    expect(
      (await screen.findAllByText("HTML обработан")).length
    ).toBeGreaterThanOrEqual(2)
    expect(screen.getByText("Требуется проверка")).toBeTruthy()

    unmount()
    vi.clearAllMocks()

    const ready = importAtState("READY")
    authRuntime.globalRole = "WMS_ADMIN"
    renderResumedWorkspace(ready)

    expect(await screen.findByText("Итог импорта")).toBeTruthy()
    expect(screen.getByText("Готов к запуску")).toBeTruthy()
    expect(screen.getByText("Склад назначения: СПБ2")).toBeTruthy()
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Запустить импорт" })
      ).toBeTruthy()
    )
    expect(screen.queryByRole("button", { name: "Сохранить план" })).toBeNull()
  })

  it.each([
    ["COMMITTING", "Импорт запускается", null, false],
    ["ASSETS_COMMITTED", "Бытовки созданы", MEDIA_JOB_ID, true],
    ["MEDIA_IMPORTING", "Импортируются медиа", MEDIA_JOB_ID, false],
  ] as const)(
    "shows %s as an in-progress import",
    async (state, label, mediaJobId, canRetryMedia) => {
      authRuntime.globalRole = "WMS_ADMIN"
      renderResumedWorkspace(importAtState(state, { mediaJobId }))

      expect(await screen.findByText("Итог импорта")).toBeTruthy()
      expect(screen.getByText(label)).toBeTruthy()
      expect(
        screen.queryByRole("button", { name: "Сохранить план" })
      ).toBeNull()
      expect(
        screen.queryByRole("button", { name: "Запустить импорт" })
      ).toBeNull()

      if (canRetryMedia) {
        expect(
          screen.getByRole("button", { name: "Повторить медиа" })
        ).toBeTruthy()
      } else {
        expect(
          screen.queryByRole("button", { name: "Повторить медиа" })
        ).toBeNull()
      }
    }
  )

  it("offers media retry for FAILED only when the service returned a media job", async () => {
    const user = userEvent.setup()
    authRuntime.globalRole = "WMS_ADMIN"
    const failed = importAtState("FAILED", {
      mediaJobId: MEDIA_JOB_ID,
      failureCode: "MEDIA_GATEWAY_FAILED",
    })
    htmlImportApi.retryHtmlImportMedia.mockResolvedValue(failed)
    renderResumedWorkspace(failed)

    expect(await screen.findByText("Импорт завершился с ошибкой")).toBeTruthy()
    expect(screen.getByText("Код ошибки: MEDIA_GATEWAY_FAILED")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Повторить медиа" }))

    await waitFor(() =>
      expect(htmlImportApi.retryHtmlImportMedia).toHaveBeenCalledWith({
        accessToken: "html-import-token",
        importId: IMPORT_ID,
        expectedVersion: 1,
        idempotencyKey: "55555555-5555-4555-8555-555555555555",
      })
    )
  })

  it("lists media-linked cabins, accepts a replacement link, and restarts the rejected Yandex preflight", async () => {
    const user = userEvent.setup()
    authRuntime.globalRole = "WMS_ADMIN"
    const failed = importAtState("FAILED", {
      mediaJobId: MEDIA_JOB_ID,
      failureCode: "YANDEX_RESOURCE_REJECTED",
    })
    htmlImportApi.replaceHtmlImportMedia.mockResolvedValue(
      importAtState("ASSETS_COMMITTED", {
        version: 2,
        mediaJobId: MEDIA_JOB_ID,
      })
    )
    renderResumedWorkspace(failed)

    expect(await screen.findByText("Ссылки на фото бытовок")).toBeTruthy()
    const link = await screen.findByLabelText("Бытовка БЫТ-042")
    await user.type(link, "https://disk.yandex.ru/d/AbCdEfGhIjKlMn")
    await user.click(
      screen.getByRole("button", { name: "Заменить ссылки и повторить" })
    )

    await waitFor(() =>
      expect(htmlImportApi.replaceHtmlImportMedia).toHaveBeenCalledWith({
        accessToken: "html-import-token",
        importId: IMPORT_ID,
        expectedVersion: 1,
        idempotencyKey: "55555555-5555-4555-8555-555555555555",
        replacements: [
          {
            rowId: "import-row-1",
            publicUrl: "https://disk.yandex.ru/d/AbCdEfGhIjKlMn",
          },
        ],
      })
    )
  })

  it("lets an administrator finish a failed import without media", async () => {
    const user = userEvent.setup()
    authRuntime.globalRole = "WMS_ADMIN"
    const failed = importAtState("FAILED", {
      mediaJobId: MEDIA_JOB_ID,
      failureCode: "MEDIA_GATEWAY_FAILED",
    })
    htmlImportApi.skipHtmlImportMedia.mockResolvedValue(
      importAtState("COMPLETED_WITH_WARNINGS", { version: 2 })
    )
    renderResumedWorkspace(failed)

    await user.click(
      await screen.findByRole("button", { name: "Пропустить медиа" })
    )

    await waitFor(() =>
      expect(htmlImportApi.skipHtmlImportMedia).toHaveBeenCalledWith({
        accessToken: "html-import-token",
        importId: IMPORT_ID,
        expectedVersion: 1,
        idempotencyKey: "55555555-5555-4555-8555-555555555555",
      })
    )
  })

  it("lets an EDIT user prepare a plan but delegates launch and media retry to an administrator", async () => {
    const ready = importAtState("READY")
    const { unmount } = renderResumedWorkspace(ready)

    expect(await screen.findByText("Итог импорта")).toBeTruthy()
    expect(
      screen.getByText("Готовый импорт должен запустить администратор.")
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Запустить импорт" })
    ).toBeNull()

    unmount()
    vi.clearAllMocks()
    const failedWithMedia = importAtState("FAILED", {
      mediaJobId: MEDIA_JOB_ID,
    })
    renderResumedWorkspace(failedWithMedia)

    expect(await screen.findByText("Импорт завершился с ошибкой")).toBeTruthy()
    expect(
      screen.getByText("Готовый импорт должен запустить администратор.")
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Повторить медиа" })).toBeNull()
  })

  it("does not resume terminal COMPLETED states", async () => {
    htmlImportApi.listHtmlImports.mockResolvedValue([
      importAtState("COMPLETED"),
      importAtState("COMPLETED_WITH_WARNINGS", { version: 2 }),
    ])
    renderWorkspace()

    await waitFor(() =>
      expect(htmlImportApi.listHtmlImports).toHaveBeenCalledWith(
        "html-import-token",
        WAREHOUSE_ID
      )
    )
    expect(
      screen.getByRole("textbox", { name: "HTML для импорта" })
    ).toBeTruthy()
    expect(htmlImportApi.getHtmlImport).not.toHaveBeenCalled()
  })

  it("polls ASSETS_COMMITTED until COMPLETED_WITH_WARNINGS", async () => {
    const assetsCommitted = importAtState("ASSETS_COMMITTED", {
      mediaJobId: MEDIA_JOB_ID,
    })
    const completedWithWarnings = importAtState("COMPLETED_WITH_WARNINGS", {
      version: 2,
      mediaJobId: MEDIA_JOB_ID,
    })
    htmlImportApi.listHtmlImports.mockResolvedValue([assetsCommitted])
    htmlImportApi.getHtmlImport
      .mockResolvedValueOnce(assetsCommitted)
      .mockResolvedValueOnce(completedWithWarnings)
    renderWorkspace()

    expect(await screen.findByText("Бытовки созданы")).toBeTruthy()
    await waitFor(
      () => expect(htmlImportApi.getHtmlImport).toHaveBeenCalledTimes(2),
      { timeout: 3_000 }
    )
    expect(
      await screen.findByText("Импорт завершён с предупреждениями")
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Закрыть" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Повторить медиа" })).toBeNull()
  })
})
