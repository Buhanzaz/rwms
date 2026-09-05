import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type {
  LogisticsDocumentHistory,
  LogisticsHistoryEvent,
  LogisticsHistoryReference,
} from "@/features/logistics/api/document-history-api"
import { LogisticsHistoryDetails } from "./logistics-history-details"

const state = vi.hoisted(() => ({
  token: "token" as string | null,
  userId: "viewer",
  history: vi.fn(),
  equipment: vi.fn(),
  actors: vi.fn(),
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: state.token,
    currentUser: { id: state.userId },
  }),
}))
vi.mock("@/features/logistics/api/document-history-api", () => ({
  getLogisticsDocumentHistory: state.history,
}))
vi.mock("@/api/equipment-api", () => ({ getEquipmentItems: state.equipment }))
vi.mock("./actor/use-dossier-actor-displays", () => ({
  useDossierActorDisplays: state.actors,
}))
const reference: LogisticsHistoryReference = {
  documentId: "document",
  warehouseId: "warehouse",
  documentType: "RETURN",
}
function event(
  eventType: string,
  version: number,
  subjectId: string
): LogisticsHistoryEvent {
  return {
    eventId: String(version),
    eventType: `logistics.return.${eventType}.v1`,
    aggregateVersion: version,
    occurredAt: "2026-09-05T10:00:00Z",
    recordedAt: "2026-09-05T10:00:00Z",
    baseline: false,
    recordedActor: { subjectId, principalType: "USER" },
    state: eventType === "accepted" ? "ACCEPTED" : "ACCEPTING",
    resultCode: null,
  }
}
function history(): LogisticsDocumentHistory {
  return {
    ...reference,
    documentVersion: 8,
    nextAfterVersion: null,
    lines: [
      {
        lineId: "line",
        assetId: "cabin",
        contentsBeforeOperation: {
          contents: [{ equipmentId: "chair", quantity: 2 }],
        },
        contentsAfterRegistration: {
          contents: [{ equipmentId: "chair", quantity: 2 }],
        },
        returnAcceptance: {
          equipmentConfirmed: true,
          additionalEquipment: [{ equipmentId: "table", quantity: 1 }],
        },
        inventoryShipmentFurniture: null,
      },
    ],
    events: [
      event("created", 0, "creator"),
      event("acceptance-started", 6, "acceptor"),
      event("accepted", 8, "creator"),
    ],
  }
}
function show(ref = reference) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 60_000 } },
  })
  const content = () => (
    <QueryClientProvider client={client}>
      <LogisticsHistoryDetails
        reference={ref}
        documentVersion={8}
        cabinId="cabin"
      />
    </QueryClientProvider>
  )
  const result = render(content())
  return { ...result, client, refresh: () => result.rerender(content()) }
}
beforeEach(() => {
  vi.resetAllMocks()
  state.token = "token"
  state.userId = "viewer"
  state.history.mockResolvedValue(history())
  state.equipment.mockResolvedValue([
    { id: "chair", name: "Стул" },
    { id: "table", name: "Стол" },
  ])
  state.actors.mockImplementation(
    (ids: string[]) =>
      new Map(
        ids.map((subjectId) => [
          subjectId,
          {
            subjectId,
            principalType: "USER",
            globalRole: "WAREHOUSE_MANAGER",
            username: subjectId,
            firstName: subjectId === "acceptor" ? "Анна" : "Иван",
            lastName: "Петрова",
            email: null,
          },
        ])
      )
  )
})
afterEach(cleanup)

describe("logistics history details", () => {
  it("shows saved quantities and the acceptance command actor, without crediting automatic completion to the creator", async () => {
    show()
    await screen.findByText(/Приёмку подтвердил:.*Анна/)
    expect(screen.getByText(/Автор документа:.*Иван/)).toBeTruthy()
    const automatic = screen
      .getByText("Приёмка завершена системой")
      .closest("li")!
    expect(automatic.textContent).not.toContain("Иван")
    const before = within(
      screen.getByRole("region", { name: "Состав до регистрации возврата" })
    )
    expect(await before.findByText("Стул")).toBeTruthy()
    expect(before.getByText("2 шт.")).toBeTruthy()
    expect(
      within(
        screen.getByRole("region", {
          name: "Дополнительно принятое оборудование",
        })
      ).getByText("1 шт.")
    ).toBeTruthy()
    expect(screen.getByText(/не означает завершение всех этапов/)).toBeTruthy()
  })
  it("distinguishes missing from saved empty evidence and labels inventory shipment contents separately", async () => {
    const payload = history()
    payload.documentType = "SHIPMENT"
    payload.events = []
    payload.lines[0] = {
      ...payload.lines[0],
      contentsBeforeOperation: null,
      returnAcceptance: null,
      inventoryShipmentFurniture: [],
    }
    state.history.mockResolvedValue(payload)
    show({ ...reference, documentType: "SHIPMENT" })
    expect(await screen.findByText("Состав не зафиксирован.")).toBeTruthy()
    expect(screen.getByText("Зафиксирован пустой состав.")).toBeTruthy()
    expect(
      screen.getByText(/не окончательная отгрузочная накладная/)
    ).toBeTruthy()
    expect(screen.queryByText("Подтверждение комплектации")).toBeNull()
  })
  it("loads later events only on request and detects a document-version change before combining pages", async () => {
    const first = history()
    first.events = [first.events[0]]
    first.nextAfterVersion = 0
    state.history
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce({ ...history(), documentVersion: 9 })
    const user = userEvent.setup()
    show()
    await screen.findByText(/Показана часть журнала: 1/)
    expect(state.history).toHaveBeenCalledTimes(1)
    await user.click(
      screen.getByRole("button", { name: "Загрузить следующие события" })
    )
    expect((await screen.findByRole("alert")).textContent).toContain(
      "Документ изменился"
    )
    expect(screen.queryByText(/Приёмку подтвердил/)).toBeNull()
    expect(state.history).toHaveBeenLastCalledWith(
      "token",
      reference,
      0,
      expect.any(AbortSignal)
    )
    state.history.mockResolvedValue({ ...history(), documentVersion: 9 })
    await user.click(screen.getByRole("button", { name: "Обновить историю" }))
    expect(await screen.findByText(/Приёмку подтвердил/)).toBeTruthy()
    expect(state.history).toHaveBeenLastCalledWith(
      "token",
      reference,
      -1,
      expect.any(AbortSignal)
    )
  })
  it("keeps earlier events and retries a failed next page without silently truncating the journal", async () => {
    const first = history()
    first.events = [first.events[0]]
    first.nextAfterVersion = 0
    state.history
      .mockResolvedValueOnce(first)
      .mockRejectedValueOnce(new Error("Журнал временно недоступен"))
      .mockResolvedValueOnce({
        ...history(),
        events: history().events.slice(1),
      })
    const user = userEvent.setup()
    show()
    await user.click(
      await screen.findByRole("button", { name: "Загрузить следующие события" })
    )
    expect((await screen.findByRole("alert")).textContent).toContain(
      "Журнал временно недоступен"
    )
    expect(screen.getByText(/Автор документа:.*Иван/)).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Повторить загрузку истории" })
    )
    expect(await screen.findByText(/Приёмку подтвердил:.*Анна/)).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Загрузить следующие события" })
    ).toBeNull()
  })
  it("does not invent names, completness or operation times for missing evidence and baseline facts", async () => {
    const payload = history()
    payload.lines[0].returnAcceptance = null
    payload.events = [
      { ...event("created", 0, "creator"), baseline: true, occurredAt: null },
      {
        ...event("acceptance-started", 1, "service"),
        recordedActor: { subjectId: "service", principalType: "SERVICE" },
      },
    ]
    state.history.mockResolvedValue(payload)
    state.equipment.mockRejectedValue(new Error("Справочник недоступен"))
    show()
    expect(await screen.findByText("Приёмку подтвердил: Сервис")).toBeTruthy()
    expect(
      screen.getByText(/Подтверждение комплектности не зафиксировано/)
    ).toBeTruthy()
    expect(screen.getByText(/Время операции не зафиксировано/)).toBeTruthy()
    expect(
      await screen.findByText(
        /Сохранённые количества показаны по идентификаторам/
      )
    ).toBeTruthy()
    expect(state.actors.mock.calls.every(([ids]) => ids.length === 0)).toBe(
      true
    )
  })
  it("fails explicitly for missing cabin evidence and does not show another cabin", async () => {
    state.history.mockResolvedValue({
      ...history(),
      lines: [{ ...history().lines[0], assetId: "another" }],
    })
    show()
    expect((await screen.findByRole("alert")).textContent).toContain(
      "отсутствует выбранная бытовка"
    )
    expect(screen.queryByText("2 шт.")).toBeNull()
  })
  it("hides cached history on logout and loads a separate query for another user", async () => {
    const view = show()
    await screen.findByText(/Приёмку подтвердил/)
    state.token = null
    view.refresh()
    expect(screen.getByRole("alert").textContent).toContain(
      "требуется авторизация"
    )
    expect(screen.queryByText(/Приёмку подтвердил/)).toBeNull()
    state.token = "another-token"
    state.userId = "another-viewer"
    state.history.mockRejectedValue(new Error("Нет доступа к документу"))
    view.refresh()
    expect((await screen.findByRole("alert")).textContent).toContain(
      "Нет доступа к документу"
    )
    await waitFor(() => expect(state.history).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/Приёмку подтвердил/)).toBeNull()
  })
})
