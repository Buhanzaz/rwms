import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

const catalogApi = vi.hoisted(() => ({
  getOperationalRepairEstimateCatalog: vi.fn(),
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

import type {
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogSnapshotDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import { RepairEstimateCatalogPicker } from "@/features/repair-estimates/repair-estimate-catalog-picker"

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
  showInMainMenu = false
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
  })
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

  it("places the compact catalog back button before the breadcrumb path", async () => {
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

    const path = screen.getByRole("navigation", { name: "Путь по каталогу" })
    const backButton = screen.getByRole("button", {
      name: "Назад по каталогу",
    })

    expect(path.firstElementChild).toBe(backButton)
    expect(backButton.getAttribute("data-variant")).toBe("outline")
    expect(backButton.getAttribute("data-size")).toBe("icon")
    expect(screen.queryByText("Назад")).toBeNull()

    await user.click(backButton)

    await waitFor(() => {
      expect(
        screen.queryByRole("button", { name: "Назад по каталогу" })
      ).toBeNull()
    })
  })

  it("continues from an added node through its outgoing single-arrow link", async () => {
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
    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(
      await screen.findByRole("button", { name: "Выбрать: Следующая работа" })
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
    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    const addedLines = onChange.mock.calls[0]?.[0] as Array<{
      description: string
    }>
    expect(addedLines.map((line) => line.description)).toEqual([
      "Монтаж двери",
      "Железная дверь",
    ])
  })

  it("continues from the second double-arrow node through later single and double arrows", async () => {
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
    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    expect(
      await screen.findByRole("button", { name: "Выбрать: Покраска двери" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Выбрать: Дверная ручка" })
    ).toBeTruthy()
  })
})
