import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import { RepairWorkCompletionDialog } from "@/features/repair-estimates/repair-work-completion-dialog"

const capabilities = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock("@/features/repair-estimates/api/warehouse-queue-capabilities", () => ({
  warehouseQueueCapabilitiesQueryKey: (warehouseId: string) => [
    "capabilities",
    warehouseId,
  ],
  getWarehouseQueueCapabilities: capabilities.get,
}))

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  () => ({
    createRepairEstimateCatalogIndex: () => ({
      nodesById: new Map([["catalog-work-1", { id: "catalog-work-1" }]]),
      getEffectiveQueueBinding: () => ({
        queueId: "queue-internal-works",
        queueName: "Внутренние работы",
        queueKind: "REPAIR",
      }),
    }),
    getOperationalRepairEstimateCatalog: vi.fn(async () => ({
      version: 1,
      nodes: [],
    })),
  })
)

const line: RepairEstimateLineDto = {
  id: "00000000-0000-4000-8000-000000000001",
  sourceLineKey: "work-1",
  lineType: "WORK",
  description: "Заменить панель",
  lineComment: "",
  unit: "шт.",
  quantity: 1,
  unitPrice: "100.00",
  lineTotal: "100.00",
  catalogSnapshot: {
    nodeId: "catalog-work-1",
    name: "Заменить панель",
    nodeType: "WORK",
    furnitureEquipment: null,
    characteristic: null,
  },
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("repair completion priority step", () => {
  it("uses catalog-derived AUTO plans without manual mode or queue controls", async () => {
    capabilities.get.mockResolvedValue({
      warehouseId: "warehouse-1",
      movementToShipmentAvailable: true,
      movementQueueDefinitions: [],
    })
    const user = userEvent.setup()
    const onComplete = vi.fn()
    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <RepairWorkCompletionDialog
          open
          accessToken="access-token"
          warehouseId="warehouse-1"
          lines={[line]}
          pending={false}
          error={null}
          title="Завершение задания"
          description="Проверьте план."
          completeLabel="Создать задание"
          pendingLabel="Создание..."
          previewKey="priority-test"
          initialMovementRequired={false}
          onOpenChange={vi.fn()}
          onComplete={onComplete}
        />
      </QueryClientProvider>
    )

    expect(
      await screen.findByRole("checkbox", {
        name: "Перемещение на отгрузку",
      })
    ).toBeTruthy()
    expect(screen.queryByText("Автоматическое распределение")).toBeNull()
    expect(
      screen.queryByRole("combobox", { name: "Режим завершения" })
    ).toBeNull()
    expect(screen.queryByText("Выбрать вручную")).toBeNull()
    expect(screen.queryByText("Маршрут очереди")).toBeNull()
    expect(screen.queryByText("Удержание")).toBeNull()

    await user.click(screen.getByRole("button", { name: "Далее" }))

    expect(screen.getByText("Выберите приоритет задания")).toBeTruthy()
    expect(onComplete).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("radio", {
        name: "Приоритет 1: Самый срочный",
      })
    )
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    expect(onComplete).toHaveBeenCalledWith(
      expect.objectContaining({
        priority: 1,
        completionMode: "AUTO",
        movementRequired: false,
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
        taskPlans: [
          expect.objectContaining({
            queueId: "queue-internal-works",
            queueName: "Внутренние работы",
            routeQueueKind: "REPAIR",
            includedLineIds: [line.id],
          }),
        ],
      })
    )
  })

  it("uses the neutral priority and skips priority choice for AUTO movement", async () => {
    capabilities.get.mockResolvedValue({
      warehouseId: "warehouse-1",
      movementToShipmentAvailable: true,
      movementQueueDefinitions: [
        { queueDefinitionId: "movement", workQueueId: "warehouse-movement" },
      ],
    })
    const user = userEvent.setup()
    const onComplete = vi.fn()
    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <RepairWorkCompletionDialog
          open
          accessToken="access-token"
          warehouseId="warehouse-1"
          lines={[line]}
          pending={false}
          error={null}
          title="Завершение задания"
          description="Проверьте план."
          completeLabel="Создать задание"
          pendingLabel="Создание..."
          previewKey="movement-test"
          initialMovementRequired={false}
          onOpenChange={vi.fn()}
          onComplete={onComplete}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("checkbox", {
        name: "Перемещение на отгрузку",
      })
    )
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    expect(onComplete).toHaveBeenCalledWith(
      expect.objectContaining({
        completionMode: "AUTO",
        movementRequired: true,
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
        priority: 3,
        taskPlans: expect.arrayContaining([
          expect.objectContaining({
            kind: "MOVE_TO_REPAIR",
            routeQueueKind: "MOVEMENT",
          }),
          expect.objectContaining({
            kind: "MOVE_FROM_REPAIR",
            routeQueueKind: "MOVEMENT",
          }),
        ]),
      })
    )
    expect(screen.queryByText("Выберите приоритет задания")).toBeNull()
  })

  it("requires and submits a date for fixed-date logistics planning", async () => {
    capabilities.get.mockResolvedValue({
      warehouseId: "warehouse-1",
      movementToShipmentAvailable: true,
      movementQueueDefinitions: [
        { queueDefinitionId: "movement", workQueueId: "warehouse-movement" },
      ],
    })
    const user = userEvent.setup()
    const onComplete = vi.fn()
    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <RepairWorkCompletionDialog
          open
          accessToken="access-token"
          warehouseId="warehouse-1"
          lines={[line]}
          pending={false}
          error={null}
          title="Завершение задания"
          description="Проверьте план."
          completeLabel="Создать задание"
          pendingLabel="Создание..."
          previewKey="fixed-date-movement-test"
          initialMovementRequired={false}
          onOpenChange={vi.fn()}
          onComplete={onComplete}
        />
      </QueryClientProvider>
    )

    await user.click(
      await screen.findByRole("checkbox", {
        name: "Перемещение на отгрузку",
      })
    )
    await user.click(
      screen.getByRole("radio", { name: "Выбрать конкретную дату" })
    )

    const completeButton = screen.getByRole("button", {
      name: "Создать задание",
    }) as HTMLButtonElement
    expect(completeButton.disabled).toBe(true)

    fireEvent.change(
      screen.getByLabelText("Дата логистического задания"),
      { target: { value: "2026-08-12" } }
    )
    expect(completeButton.disabled).toBe(false)

    await user.click(completeButton)

    expect(onComplete).toHaveBeenCalledWith(
      expect.objectContaining({
        movementRequired: true,
        logisticsPlanningMode: "FIXED_DATE",
        logisticsScheduledDate: "2026-08-12",
        priority: 3,
      })
    )
  })

  it("restores persisted fixed-date logistics planning when reopened", async () => {
    capabilities.get.mockResolvedValue({
      warehouseId: "warehouse-1",
      movementToShipmentAvailable: true,
      movementQueueDefinitions: [
        { queueDefinitionId: "movement", workQueueId: "warehouse-movement" },
      ],
    })
    const user = userEvent.setup()
    const onComplete = vi.fn()
    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <RepairWorkCompletionDialog
          open
          accessToken="access-token"
          warehouseId="warehouse-1"
          lines={[line]}
          pending={false}
          error={null}
          title="Завершение задания"
          description="Проверьте план."
          completeLabel="Создать задание"
          pendingLabel="Создание..."
          previewKey="persisted-fixed-date-movement-test"
          initialMovementRequired
          initialLogisticsPlanningMode="FIXED_DATE"
          initialLogisticsScheduledDate="2026-08-12"
          onOpenChange={vi.fn()}
          onComplete={onComplete}
        />
      </QueryClientProvider>
    )

    expect(
      (
        await screen.findByRole("radio", {
          name: "Выбрать конкретную дату",
        })
      ).getAttribute("data-state")
    ).toBe("on")
    expect(
      (screen.getByLabelText("Дата логистического задания") as HTMLInputElement)
        .value
    ).toBe("2026-08-12")

    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    expect(onComplete).toHaveBeenCalledWith(
      expect.objectContaining({
        movementRequired: true,
        logisticsPlanningMode: "FIXED_DATE",
        logisticsScheduledDate: "2026-08-12",
      })
    )
  })

  it("does not render or submit movement when the warehouse has no capability", async () => {
    capabilities.get.mockResolvedValue({
      warehouseId: "warehouse-1",
      movementToShipmentAvailable: false,
      movementQueueDefinitions: [],
    })
    const user = userEvent.setup()
    const onComplete = vi.fn()
    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <RepairWorkCompletionDialog
          open
          accessToken="access-token"
          warehouseId="warehouse-1"
          lines={[line]}
          pending={false}
          error={null}
          title="Завершение задания"
          description="Проверьте план."
          completeLabel="Создать задание"
          pendingLabel="Создание..."
          previewKey="no-movement-capability"
          initialMovementRequired
          onOpenChange={vi.fn()}
          onComplete={onComplete}
        />
      </QueryClientProvider>
    )

    await screen.findByRole("button", { name: "Далее" })
    expect(
      screen.queryByRole("checkbox", { name: "Перемещение на отгрузку" })
    ).toBeNull()

    await user.click(screen.getByRole("button", { name: "Далее" }))
    await user.click(
      screen.getByRole("radio", { name: "Приоритет 1: Самый срочный" })
    )
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    expect(onComplete).toHaveBeenCalledWith(
      expect.objectContaining({
        movementRequired: false,
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
      })
    )
  })
})
