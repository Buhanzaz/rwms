import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ScheduleSettings } from "@/features/settings/task-board/schedule-settings"
import {
  SPB_WAREHOUSE_ID,
  taskBoardMockClient,
} from "@/features/task-board/mock"

let consoleError: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  taskBoardMockClient.resetForTests()
  consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined)
})

afterEach(() => {
  cleanup()
  taskBoardMockClient.resetForTests()
  const duplicateKeyWarnings = consoleError.mock.calls.filter(
    (call: unknown[]) =>
      call.some((value: unknown) => String(value).includes("same key"))
  )
  consoleError.mockRestore()
  vi.restoreAllMocks()
  expect(duplicateKeyWarnings).toEqual([])
})

async function renderSettings() {
  const groups = await taskBoardMockClient.listGroups(SPB_WAREHOUSE_ID)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <ScheduleSettings warehouseId={SPB_WAREHOUSE_ID} groups={groups} />
    </QueryClientProvider>
  )
  await screen.findByText("График бригады")
}

describe("ScheduleSettings", () => {
  function conflict(message = "Данные изменены") {
    return Object.assign(new Error(message), { status: 409 })
  }

  it("copies one edited day into another day", async () => {
    await renderSettings()

    fireEvent.change(document.querySelector("#shift-start-1")!, {
      target: { value: "08:00" },
    })
    fireEvent.click(screen.getAllByRole("button", { name: "Копировать" })[0])
    fireEvent.click(await screen.findByLabelText("Вторник"))
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "Копировать",
      })
    )

    expect(
      (document.querySelector("#shift-start-2") as HTMLInputElement).value
    ).toBe("08:00")
    expect(
      document.querySelector("#day-template-2")?.getAttribute("data-state")
    ).toBe("unchecked")
  }, 15_000)

  it("updates the simulation speed through the mock client", async () => {
    await renderSettings()

    fireEvent.click(screen.getByRole("radio", { name: "×300" }))

    await waitFor(async () => {
      const clock = await taskBoardMockClient.getSimulationClock()
      expect(clock.speed).toBe(300)
      expect(clock.mode).toBe("SIMULATION")
    })
  })

  it("blocks saving an invalid IANA timezone", async () => {
    await renderSettings()

    fireEvent.change(screen.getByLabelText("Часовой пояс"), {
      target: { value: "Mars/Phobos" },
    })

    expect(
      await screen.findByText(
        "Укажите часовой пояс IANA, например Europe/Moscow."
      )
    ).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: "Сохранить график",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
  })

  it("discards a stale schedule draft and reloads after save conflict", async () => {
    await renderSettings()
    vi.spyOn(taskBoardMockClient, "saveSchedule").mockRejectedValueOnce(
      conflict()
    )

    fireEvent.change(screen.getByLabelText("Часовой пояс"), {
      target: { value: "Europe/Samara" },
    })
    fireEvent.click(screen.getByRole("button", { name: "Сохранить график" }))

    const notice = await screen.findByRole("alert")
    expect(notice.textContent).toContain("Локальный черновик отброшен")
    await waitFor(() => {
      expect(
        (screen.getByLabelText("Часовой пояс") as HTMLInputElement).value
      ).toBe("Europe/Moscow")
    })
    expect(screen.queryByText("Не сохранено")).toBeNull()
  }, 15_000)

  it("closes copy dialog and reloads schedules after copy conflict", async () => {
    await renderSettings()
    vi.spyOn(taskBoardMockClient, "copySchedule").mockRejectedValueOnce(
      conflict()
    )

    fireEvent.click(screen.getByRole("button", { name: "Копировать бригаде" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getAllByRole("checkbox")[0])
    fireEvent.click(within(dialog).getByRole("button", { name: "Копировать" }))

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Копирование отменено"
    )
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("reloads demonstration clock after optimistic conflict", async () => {
    await renderSettings()
    const before = await taskBoardMockClient.getSimulationClock()
    vi.spyOn(
      taskBoardMockClient,
      "updateSimulationClock"
    ).mockRejectedValueOnce(conflict())

    fireEvent.click(screen.getByRole("radio", { name: "×300" }))

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Демонстрационные часы изменены"
    )
    const clock = await taskBoardMockClient.getSimulationClock()
    expect(clock.speed).toBe(before.speed)
    expect(clock.version).toBe(before.version)
  })
})
