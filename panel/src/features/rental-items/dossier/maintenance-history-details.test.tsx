import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { MaintenanceEstimate } from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { ApiError } from "@/lib/api-client"
import { DossierActivityRegister } from "./dossier-activity-register"
import { MaintenanceHistoryDetails } from "./maintenance-history-details"

const state = vi.hoisted(() => ({
  token: "token" as string | null,
  estimate: vi.fn(),
  repair: vi.fn(),
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: state.token, currentUser: { id: "viewer" } }),
}))
vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  () => ({ getMaintenanceEstimate: state.estimate })
)
vi.mock("@/features/repair-estimates/api/repair-estimates-api", () => ({
  REPAIR_ESTIMATES_QUERY_KEY: ["repair-estimates"],
}))
vi.mock("@/features/repair-tasks/api/repair-tasks-api", () => ({
  getRepairTask: state.repair,
  REPAIR_TASKS_QUERY_KEY: ["repair-tasks"],
}))
vi.mock("./actor/use-dossier-actor-displays", () => ({
  useDossierActorDisplays: () =>
    new Map(
      ["author", "acceptor"].map((subjectId) => [
        subjectId,
        {
          subjectId,
          principalType: "USER",
          globalRole: "WAREHOUSE_MANAGER",
          username: subjectId,
          firstName: subjectId === "author" ? "Иван" : "Анна",
          lastName: "Петрова",
          email: null,
        },
      ])
    ),
}))

function estimate(): MaintenanceEstimate {
  return {
    id: "source",
    warehouseId: "warehouse",
    rentalItemId: "cabin",
    version: 3,
    lifecycle: "COMPLETED",
    currentRevision: 2,
    repairId: null,
    mediaReferences: [],
    createdAt: "2026-01-01T10:00:00Z",
    completedAt: "2026-01-02T10:00:00Z",
    actor: { actorId: "author", actorType: "USER" },
    forceCapitalRepair: false,
    revisions: [1, 2].map((revision) => ({
      revision,
      dispatchDate: "2026-01-01",
      sourceParty: "ООО Клиент",
      total: "1250.50",
      reason: revision === 2 ? "Добавили замену двери" : null,
      recordedAt: `2026-01-0${revision}T10:00:00Z`,
      forceCapitalRepair: false,
      plan: [],
      lines: [
        {
          id: "line",
          catalogSnapshot: null,
          lineType: "WORK",
          description: "Заменить дверь",
          unit: "шт.",
          quantity: "1",
          unitPrice: "1250.50",
          lineTotal: "1250.50",
          normativeMinutes: 30,
          comment: "Повреждена при возврате",
          mediaReferences: [],
        },
      ],
    })),
  }
}

function repair(): RepairTaskDto {
  return {
    id: "source",
    warehouseId: "warehouse",
    rentalItemId: "cabin",
    cabinNumber: "55",
    version: 2,
    status: "COMPLETED",
    kind: "REWORK",
    origin: "ESTIMATE",
    acceptanceStatus: "ACCEPTED",
    actorId: "author",
    decisionActorId: "acceptor",
    sourceParty: "ООО Клиент",
    dispatchDate: "2026-01-01",
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: "original-repair",
    sourceRepairTaskVersion: null,
    taskBoardAvailable: true,
    movementToRepair: false,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    startedAt: "2026-01-01T10:00:00Z",
    completedAt: "2026-01-02T10:00:00Z",
    readyAt: "2026-01-02T10:00:00Z",
    createdAt: "2026-01-01T09:00:00Z",
    updatedAt: "2026-01-03T10:00:00Z",
    subtasks: [
      {
        id: "stage",
        kind: "REPAIR_WORK",
        status: "DONE",
        queueName: "Столярные работы",
        routeQueueKind: "REPAIR",
        sortOrder: 0,
        queuePosition: 0,
        groupComment: "Устранить замечание приёмки",
        materialLines: [],
        workLines: [
          {
            id: "line",
            sourceLineKey: "line",
            lineType: "WORK",
            description: "Поправить дверь",
            lineComment: "Зазор не соответствует",
            unit: "шт.",
            quantity: 1,
            unitPrice: "100.00",
            lineTotal: "100.00",
            catalogSnapshot: null,
            rework: {
              disposition: "REPEAT",
              sourceRepairId: "original-repair",
              sourceLineId: "old",
              lineageRootLineId: "old",
            },
          },
        ],
        plannedDurationMinutes: 30,
        startedAt: "2026-01-01T10:00:00Z",
        completedAt: "2026-01-02T10:00:00Z",
        activeStartedAt: null,
        activeWorkSeconds: 1800,
        workerGroup: { id: "crew", name: "Бригада № 2" },
        assignments: [
          {
            id: "assignment",
            worker: { id: "worker", name: "Сергей Иванов" },
            status: "DONE",
            assignedAt: "2026-01-01T09:30:00Z",
            startedAt: "2026-01-01T10:00:00Z",
            finishedAt: "2026-01-02T10:00:00Z",
            pausedAt: null,
            activeStartedAt: null,
          },
        ],
      },
    ],
  }
}

function show(kind: "ESTIMATE" | "REPAIR", timeline = false) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 60_000 } },
  })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        {timeline ? (
          <DossierActivityRegister
            error={null}
            isLoading={false}
            hasNextPage={false}
            isFetchingNextPage={false}
            onLoadMore={vi.fn()}
            pages={[
              {
                cabinId: "cabin",
                nextCursor: null,
                visibility: "COMPLETE",
                activities: [
                  {
                    activityId: "activity",
                    cabinId: "cabin",
                    warehouseId: "warehouse",
                    activityCode: "ESTIMATE_COMPLETED",
                    occurredAt: "2026-01-02T10:00:00Z",
                    recordedAt: "2026-01-02T10:00:00Z",
                    actorRef: null,
                    sourceRef: {
                      producer: "maintenance-service",
                      aggregateType: "ESTIMATE",
                      aggregateId: "source",
                    },
                    media: [],
                    taskEvidencePhotos: [],
                  },
                ],
              },
            ]}
          />
        ) : (
          <MaintenanceHistoryDetails
            kind={kind}
            sourceId="source"
            cabinId="cabin"
            warehouseId="warehouse"
          />
        )}
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  state.token = "token"
  state.estimate.mockResolvedValue(estimate())
  state.repair.mockResolvedValue(repair())
})
afterEach(cleanup)

describe("MaintenanceHistoryDetails", () => {
  it("loads estimate facts only on expand and reuses the authorized cache on reopening", async () => {
    show("ESTIMATE", true)
    expect(state.estimate).not.toHaveBeenCalled()
    const user = userEvent.setup()
    const trigger = screen.getByRole("button", { name: /Смета завершена/ })
    await user.click(trigger)
    await screen.findByRole("region", { name: "Подробности сметы" })
    expect(state.estimate).toHaveBeenCalledWith("token", "warehouse", "source")
    expect(screen.getByText("ООО Клиент")).toBeTruthy()
    expect(screen.getByText("Руководитель склада — Петрова Иван")).toBeTruthy()
    expect(screen.getByText(/Добавили замену двери/)).toBeTruthy()
    expect(screen.getByText("Повреждена при возврате")).toBeTruthy()
    expect(screen.getByText(/Итого по смете: 1\s250,50 ₽/)).toBeTruthy()
    await user.click(trigger)
    await user.click(trigger)
    await screen.findByRole("region", { name: "Подробности сметы" })
    expect(state.estimate).toHaveBeenCalledTimes(1)
  })

  it("shows repair stages, comments, workers, rework ancestry and actual decision actor", async () => {
    show("REPAIR")
    const region = await screen.findByRole("region", {
      name: "Подробности ремонта",
    })
    expect(within(region).getByText("Принят")).toBeTruthy()
    expect(
      within(region).getByText("Руководитель склада — Петрова Анна")
    ).toBeTruthy()
    expect(within(region).getByText("Устранить замечание приёмки")).toBeTruthy()
    expect(within(region).getByText(/Поправить дверь.*Переделать/)).toBeTruthy()
    expect(within(region).getByText(/Сергей Иванов/)).toBeTruthy()
    expect(
      within(region)
        .getByRole("link", { name: "Исходный ремонт" })
        .getAttribute("href")
    ).toBe("/repairs?repairId=original-repair")
  })

  it("retains timeline events when owner read fails and provides an explicit retry", async () => {
    state.estimate.mockRejectedValue(new ApiError("Нет доступа к смете", 403))
    show("ESTIMATE", true)
    const user = userEvent.setup()
    await user.click(screen.getByRole("button", { name: /Смета завершена/ }))
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "Нет доступа к смете"
    )
    expect(screen.getByText("Ход операции")).toBeTruthy()
    state.estimate.mockResolvedValue(estimate())
    await user.click(
      screen.getByRole("button", { name: "Повторить загрузку подробностей" })
    )
    await screen.findByText("ООО Клиент")
  })

  it("does not expose a different cabin returned by the service", async () => {
    state.estimate.mockResolvedValue({
      ...estimate(),
      rentalItemId: "other-cabin",
    })
    show("ESTIMATE")
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "Сервис вернул смету, не соответствующую операции бытовки."
    )
    expect(screen.queryByText("ООО Клиент")).toBeNull()
  })

  it("distinguishes unavailable assignment data from an empty confirmed execution history", async () => {
    state.repair.mockResolvedValue({ ...repair(), taskBoardAvailable: false })
    show("REPAIR")
    expect(await screen.findByRole("status")).toHaveProperty(
      "textContent",
      "Доска заданий недоступна: сведения об исполнителях и времени могут быть неполными."
    )
  })

  it("does not request details without authentication", () => {
    state.token = null
    show("ESTIMATE")
    expect(screen.getByRole("alert").textContent).toContain(
      "требуется авторизация"
    )
    expect(state.estimate).not.toHaveBeenCalled()
  })
})
