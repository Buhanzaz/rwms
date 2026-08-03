import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

const catalogApi = vi.hoisted(() => ({
  getOperationalRepairEstimateCatalog: vi.fn(),
}))
const ownerMedia = vi.hoisted(() => ({
  upload: vi.fn(),
  useServiceOwnerMedia: vi.fn(() => ({
    query: { isLoading: false },
    assets: [],
    photos: [],
    readyReferences: [],
    logicalPhotoCount: 0,
    upload: vi.fn(),
    pending: false,
    error: null,
  })),
}))

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/repair-estimate-catalog/api/repair-estimate-catalog-api")
    >()),
    getOperationalRepairEstimateCatalog:
      catalogApi.getOperationalRepairEstimateCatalog,
  })
)

vi.mock("@/features/media/use-service-owner-media", () => ({
  useServiceOwnerMedia: ownerMedia.useServiceOwnerMedia,
}))

import type {
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogSnapshotDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import { RepairEstimateCatalogPicker } from "@/features/repair-estimates/repair-estimate-catalog-picker"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import { maintenanceEstimateMediaOwner } from "@/features/media/media-service"

const catalogVersionId = "11111111-1111-4111-8111-111111111111"
const workId = "33333333-3333-4333-8333-333333333333"
const materialId = "44444444-4444-4444-8444-444444444444"
const followUpId = "55555555-5555-4555-8555-555555555555"
const dependencyNextId = "66666666-6666-4666-8666-666666666666"

function createNode(
  overrides: Partial<RepairEstimateCatalogNodeDto>
): RepairEstimateCatalogNodeDto {
  return {
    id: "22222222-2222-4222-8222-222222222222",
    catalogVersionId,
    name: "Внутренняя отделка",
    nodeType: "CATEGORY",
    parentId: null,
    active: true,
    unit: null,
    unitPrice: null,
    durationMinutes: null,
    showInMainMenu: true,
    routeQueueKind: null,
    queueDefinitionId: null,
    queueDefinitionName: null,
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    forcesCapitalRepair: false,
    characteristic: null,
    comment: null,
    ...overrides,
  }
}

const catalog: RepairEstimateCatalogSnapshotDto = {
  nodes: [
    createNode({}),
    createNode({
      id: "33333333-3333-4333-8333-333333333333",
      name: "Покраска",
      parentId: "22222222-2222-4222-8222-222222222222",
      showInMainMenu: false,
    }),
  ],
  links: [],
}

function estimateNode(
  id: string,
  name: string,
  nodeType: "WORK" | "MATERIAL" | "OPTION",
  showInMainMenu = false,
  overrides: Partial<RepairEstimateCatalogNodeDto> = {}
) {
  return createNode({
    id,
    name,
    nodeType,
    showInMainMenu,
    includeInEstimate: nodeType !== "OPTION",
    unit: "шт.",
    unitPrice: "100.00",
    durationMinutes: nodeType === "WORK" ? 30 : null,
    ...overrides,
  })
}

function catalogWorkLine(
  id: string,
  node: RepairEstimateCatalogNodeDto,
  lineComment: string
): RepairEstimateLineDto {
  return {
    id,
    sourceLineKey: id,
    lineType: "WORK",
    description: node.name,
    lineComment,
    unit: node.unit ?? "ед",
    quantity: 1,
    normativeMinutes: node.durationMinutes ?? 0,
    unitPrice: node.unitPrice ?? "0.00",
    lineTotal: node.unitPrice ?? "0.00",
    catalogSnapshot: {
      nodeId: node.id,
      name: node.name,
      nodeType: "WORK",
      furnitureEquipment: null,
      characteristic: null,
    },
    customQueueBinding: null,
  }
}

function graphCatalog(
  nodes: RepairEstimateCatalogNodeDto[],
  links: RepairEstimateCatalogSnapshotDto["links"]
): RepairEstimateCatalogSnapshotDto {
  return { nodes, links }
}

function graphLink(
  id: string,
  sourceNodeId: string,
  targetNodeId: string,
  linkType: "DEPENDENCY" | "FOLLOW_UP",
  sortOrder = 0
) {
  return {
    id,
    catalogVersionId,
    sourceNodeId,
    targetNodeId,
    linkType,
    sortOrder,
  }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairEstimateCatalogPicker", () => {
  it("uses the configured catalog colour with a readable foreground", async () => {
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue({
      ...catalog,
      nodes: [createNode({ displayColor: "#F8E71C" })],
    })
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={vi.fn()}
        />
      </QueryClientProvider>
    )

    const catalogButton = await screen.findByRole("button", {
      name: "Выбрать: Внутренняя отделка",
    })
    const card = catalogButton.querySelector('[data-slot="card"]')

    expect(catalogButton.getAttribute("data-catalog-display-color")).toBe(
      "#F8E71C"
    )
    expect(card?.getAttribute("style")).toContain(
      "background-color: rgb(248, 231, 28)"
    )
    expect(card?.getAttribute("style")).toContain("color: rgb(17, 24, 39)")
  })

  it("places catalog root and back buttons before the breadcrumb path", async () => {
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(catalog)
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={vi.fn()}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", {
        name: "Открыть: Внутренняя отделка",
      })
    )
    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Покраска" })
    )

    const path = screen.getByRole("navigation", { name: "Путь по каталогу" })
    const rootButton = screen.getByRole("button", {
      name: "К корню каталога",
    })
    const backButton = screen.getByRole("button", {
      name: "Назад по каталогу",
    })

    expect(path.firstElementChild).toBe(rootButton)
    expect(rootButton.getAttribute("data-variant")).toBe("outline")
    expect(rootButton.getAttribute("data-size")).toBe("icon")
    expect(backButton.getAttribute("data-variant")).toBe("outline")
    expect(backButton.getAttribute("data-size")).toBe("icon")
    expect(screen.queryByText("Назад")).toBeNull()

    await user.click(backButton)

    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Покраска" })).toBeNull()
      expect(
        screen.getByRole("button", { name: "К корню каталога" })
      ).toBeTruthy()
    })

    await user.click(screen.getByRole("button", { name: "К корню каталога" }))

    await waitFor(() => {
      expect(
        screen.queryByRole("button", { name: "К корню каталога" })
      ).toBeNull()
      expect(
        screen.queryByRole("button", { name: "Назад по каталогу" })
      ).toBeNull()
    })
  })

  it("uses canvas arrows before flat category membership when showing catalog buttons", async () => {
    const exterior = createNode({
      id: "10111111-1111-4111-8111-111111111111",
      name: "Внешняя отделка",
      showInMainMenu: true,
    })
    const roof = createNode({
      id: "10222222-2222-4222-8222-222222222222",
      name: "Крыша",
      nodeType: "SUBCATEGORY",
      parentId: exterior.id,
      showInMainMenu: false,
    })
    const walls = createNode({
      id: "10333333-3333-4333-8333-333333333333",
      name: "Стены",
      nodeType: "SUBCATEGORY",
      parentId: exterior.id,
      showInMainMenu: false,
    })
    const frame = createNode({
      id: "10444444-4444-4444-8444-444444444444",
      name: "Каркас",
      nodeType: "SUBCATEGORY",
      parentId: exterior.id,
      showInMainMenu: false,
    })
    const paint = estimateNode(
      "10555555-5555-4555-8555-555555555555",
      "Окраска крыши суриком",
      "WORK",
      false,
      { parentId: exterior.id }
    )
    const waterproofing = estimateNode(
      "10666666-6666-4666-8666-666666666666",
      "Гидроизоляция",
      "WORK",
      false,
      { parentId: exterior.id }
    )
    const tapeRepair = estimateNode(
      "10777777-7777-4777-8777-777777777777",
      "Ремонт крыши гидроизоляционной лентой",
      "WORK",
      false,
      { parentId: exterior.id }
    )
    const sheetReplacement = estimateNode(
      "10888888-8888-4888-8888-888888888888",
      "Замена профлиста",
      "WORK",
      false,
      { parentId: exterior.id }
    )
    const tape = estimateNode(
      "10999999-9999-4999-8999-999999999999",
      "Гидроизоляционная лента",
      "MATERIAL",
      false,
      { parentId: exterior.id }
    )
    const looseMastic = estimateNode(
      "11000000-0000-4000-8000-000000000000",
      "Мастика",
      "MATERIAL",
      false,
      { parentId: exterior.id }
    )
    const catalogSnapshot = graphCatalog(
      [
        exterior,
        roof,
        walls,
        frame,
        paint,
        waterproofing,
        tapeRepair,
        sheetReplacement,
        tape,
        looseMastic,
      ],
      [
        graphLink(
          "20111111-1111-4111-8111-111111111111",
          exterior.id,
          roof.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20222222-2222-4222-8222-222222222222",
          exterior.id,
          walls.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20333333-3333-4333-8333-333333333333",
          exterior.id,
          frame.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20444444-4444-4444-8444-444444444444",
          roof.id,
          paint.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20555555-5555-4555-8555-555555555555",
          roof.id,
          waterproofing.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20666666-6666-4666-8666-666666666666",
          roof.id,
          tapeRepair.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20777777-7777-4777-8777-777777777777",
          roof.id,
          sheetReplacement.id,
          "FOLLOW_UP"
        ),
        graphLink(
          "20888888-8888-4888-8888-888888888888",
          tapeRepair.id,
          tape.id,
          "DEPENDENCY"
        ),
      ]
    )
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      catalogSnapshot
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={onChange}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", {
        name: "Открыть: Внешняя отделка",
      })
    )

    expect(screen.getByRole("button", { name: "Открыть: Крыша" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Выбрать: Стены" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Выбрать: Каркас" })).toBeTruthy()
    expect(
      screen.queryByRole("button", {
        name: "Выбрать: Гидроизоляционная лента",
      })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Мастика" })
    ).toBeNull()

    await user.click(screen.getByRole("button", { name: "Открыть: Крыша" }))

    expect(
      screen.getByRole("button", {
        name: "Выбрать: Окраска крыши суриком",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Выбрать: Гидроизоляция" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Выбрать: Ремонт крыши гидроизоляционной лентой",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Выбрать: Замена профлиста" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Мастика" })
    ).toBeNull()

    await user.click(
      screen.getByRole("button", {
        name: "Выбрать: Ремонт крыши гидроизоляционной лентой",
      })
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать: Гидроизоляционная лента",
      })
    )
    await user.click(await screen.findByRole("button", { name: "Далее" }))

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(
      (onChange.mock.calls[0]?.[0] as Array<{ description: string }>).map(
        (line) => line.description
      )
    ).toEqual([
      "Ремонт крыши гидроизоляционной лентой",
      "Гидроизоляционная лента",
    ])
  })

  it("keeps common items out of regular branches and opens them through the common picker", async () => {
    const exterior = createNode({
      id: "12111111-1111-4111-8111-111111111111",
      name: "Внешняя отделка",
      showInMainMenu: true,
    })
    const regularWork = estimateNode(
      "12222222-2222-4222-8222-222222222222",
      "Локальная работа",
      "WORK",
      false,
      { parentId: exterior.id }
    )
    const commonWork = estimateNode(
      "12333333-3333-4333-8333-333333333333",
      "Общая работа",
      "WORK",
      false,
      { parentId: exterior.id, commonItem: true }
    )
    const commonMaterial = estimateNode(
      "12444444-4444-4444-8444-444444444444",
      "Общий материал",
      "MATERIAL",
      false,
      { parentId: exterior.id, commonItem: true }
    )
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog([exterior, regularWork, commonWork, commonMaterial], [])
    )
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={vi.fn()}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", {
        name: "Открыть: Внешняя отделка",
      })
    )

    expect(
      screen.getByRole("button", { name: "Выбрать: Локальная работа" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Общая работа" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Общий материал" })
    ).toBeNull()

    await user.click(screen.getByRole("button", { name: "Добавить общее" }))

    expect(
      screen.getByRole("button", { name: "Выбрать: Общая работа" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Общий материал" })
    ).toBeNull()

    await user.click(screen.getByRole("button", { name: "Материалы" }))

    expect(
      screen.queryByRole("button", { name: "Выбрать: Общая работа" })
    ).toBeNull()
    expect(
      screen.getByRole("button", { name: "Выбрать: Общий материал" })
    ).toBeTruthy()
  })

  it("returns to the catalog root after an added node", async () => {
    const work = estimateNode(workId, "Основная работа", "WORK", true)
    const next = estimateNode(followUpId, "Следующая работа", "WORK")
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog(
        [work, next],
        [
          graphLink(
            "77777777-7777-4777-8777-777777777777",
            work.id,
            next.id,
            "FOLLOW_UP"
          ),
        ]
      )
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={onChange}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Основная работа" })
    )
    await user.click(await screen.findByRole("button", { name: "Далее" }))

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(
      await screen.findByRole("button", { name: "Выбрать: Основная работа" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Следующая работа" })
    ).toBeNull()
  })

  it("persists the first work before attaching its photos without adding it twice", async () => {
    const work = estimateNode(workId, "Основная работа", "WORK", true)
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog([work], [])
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    const ensureMediaOwner = vi.fn().mockResolvedValue(
      maintenanceEstimateMediaOwner(
        "77777777-7777-4777-8777-777777777701",
        "77777777-7777-4777-8777-777777777702"
      )
    )
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          accessToken="media-token"
          ensureMediaOwner={ensureMediaOwner}
          onChange={onChange}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Основная работа" })
    )
    await user.type(
      screen.getByRole("textbox", { name: "Комментарий к работе" }),
      "Фото до ремонта"
    )
    await user.click(
      screen.getByRole("button", { name: "Выбрать из сделанных" })
    )

    await waitFor(() => expect(ensureMediaOwner).toHaveBeenCalledTimes(1))
    expect(ensureMediaOwner).toHaveBeenCalledWith([
      expect.objectContaining({
        lineType: "WORK",
        description: "Основная работа",
        lineComment: "Фото до ремонта",
      }),
    ])
    const photoDialog = await screen.findByRole("dialog", {
      name: "Выбрать фото работы",
    })
    await user.click(
      within(photoDialog).getByRole("button", { name: "Отмена" })
    )

    await user.click(screen.getByRole("button", { name: "Далее" }))

    expect(onChange).toHaveBeenCalledTimes(1)
    const savedLines = onChange.mock.calls[0]?.[0] as RepairEstimateLineDto[]
    expect(savedLines).toHaveLength(1)
    expect(savedLines[0]).toEqual(
      expect.objectContaining({
        lineType: "WORK",
        description: "Основная работа",
        lineComment: "Фото до ремонта",
      })
    )
  })

  it("lets the user choose which duplicate catalog work receives the quantity", async () => {
    const work = estimateNode(workId, "Основная работа", "WORK", true)
    const firstExisting = catalogWorkLine(
      "77777777-7777-4777-8777-777777777701",
      work,
      "Первый комментарий"
    )
    const secondExisting = catalogWorkLine(
      "77777777-7777-4777-8777-777777777702",
      work,
      "Второй комментарий"
    )
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog([work], [])
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[firstExisting, secondExisting]}
          readOnly={false}
          onChange={onChange}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Основная работа" })
    )

    expect(
      await screen.findByRole("heading", {
        name: "Работа уже добавлена в смету",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Добавить к работе 1: Основная работа",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Добавить к работе 2: Основная работа",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Создать отдельную работу" })
    ).toBeTruthy()

    await user.click(
      screen.getByRole("button", {
        name: "Добавить к работе 2: Основная работа",
      })
    )
    await user.type(
      await screen.findByRole("textbox", { name: "Комментарий к работе" }),
      "Не менять существующий комментарий"
    )
    expect(screen.getByRole("button", { name: "Добавить фото" })).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Выбрать из сделанных" })
    ).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Далее" }))

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange.mock.calls[0]?.[0]).toEqual([
      expect.objectContaining({
        id: firstExisting.id,
        quantity: 1,
        lineComment: "Первый комментарий",
      }),
      expect.objectContaining({
        id: secondExisting.id,
        quantity: 2,
        lineComment: "Второй комментарий; Не менять существующий комментарий",
      }),
    ])
  })

  it("checks for duplicate work only after its location is selected", async () => {
    const work = estimateNode(workId, "Монтаж двери", "WORK", true)
    const material = estimateNode(materialId, "Дверь", "MATERIAL")
    const location = createNode({
      id: "77777777-7777-4777-8777-777777777703",
      name: "Секция А",
      nodeType: "LOCATION",
      showInMainMenu: false,
    })
    const existing = {
      ...catalogWorkLine(
        "77777777-7777-4777-8777-777777777704",
        work,
        "Не потерять"
      ),
      description: `${work.name} ${location.name}`,
    }
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog(
        [work, material, location],
        [
          graphLink(
            "77777777-7777-4777-8777-777777777705",
            work.id,
            material.id,
            "DEPENDENCY"
          ),
          graphLink(
            "77777777-7777-4777-8777-777777777706",
            material.id,
            location.id,
            "FOLLOW_UP"
          ),
        ]
      )
    )
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[existing]}
          readOnly={false}
          onChange={vi.fn()}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Монтаж двери" })
    )
    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Дверь" })
    )
    expect(
      screen.queryByRole("heading", {
        name: "Работа уже добавлена в смету",
      })
    ).toBeNull()

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Секция А" })
    )
    expect(
      await screen.findByRole("heading", {
        name: "Работа уже добавлена в смету",
      })
    ).toBeTruthy()
  })

  it("adds both nodes connected by a double-arrow dependency", async () => {
    const work = estimateNode(workId, "Монтаж двери", "WORK", true)
    const material = estimateNode(materialId, "Железная дверь", "MATERIAL")
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog(
        [work, material],
        [
          graphLink(
            "77777777-7777-4777-8777-777777777777",
            work.id,
            material.id,
            "DEPENDENCY"
          ),
        ]
      )
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={onChange}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Монтаж двери" })
    )
    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Железная дверь" })
    )
    await user.click(await screen.findByRole("button", { name: "Далее" }))

    const addedLines = onChange.mock.calls[0]?.[0] as Array<{
      description: string
    }>
    expect(addedLines.map((line) => line.description)).toEqual([
      "Монтаж двери",
      "Железная дверь",
    ])
  })

  it("returns to the root instead of continuing through later graph links", async () => {
    const work = estimateNode(workId, "Замена двери", "WORK", true)
    const material = estimateNode(materialId, "Дверь", "MATERIAL")
    const followUp = estimateNode(followUpId, "Покраска двери", "WORK")
    const dependencyNext = estimateNode(
      dependencyNextId,
      "Дверная ручка",
      "MATERIAL"
    )
    catalogApi.getOperationalRepairEstimateCatalog.mockResolvedValue(
      graphCatalog(
        [work, material, followUp, dependencyNext],
        [
          graphLink(
            "77777777-7777-4777-8777-777777777777",
            work.id,
            material.id,
            "DEPENDENCY"
          ),
          graphLink(
            "88888888-8888-4888-8888-888888888888",
            material.id,
            followUp.id,
            "FOLLOW_UP"
          ),
          graphLink(
            "99999999-9999-4999-8999-999999999999",
            material.id,
            dependencyNext.id,
            "DEPENDENCY"
          ),
        ]
      )
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          onChange={onChange}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Замена двери" })
    )
    await user.click(
      await screen.findByRole("button", { name: "Выбрать: Дверь" })
    )
    await user.click(await screen.findByRole("button", { name: "Далее" }))

    expect(
      await screen.findByRole("button", { name: "Выбрать: Замена двери" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Покраска двери" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выбрать: Дверная ручка" })
    ).toBeNull()
  })
})
