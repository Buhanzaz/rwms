import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { UserGlobalRole } from "@/features/auth/auth-model"
import { CabinStatusColorsSettings } from "./cabin-status-colors-settings"
import { CabinStatusPaletteSync } from "./cabin-status-palette-sync"
import {
  CABIN_STATUS_COLOR_STATUSES,
  cabinStatusColorsQueryKey,
  type CabinStatusColors,
} from "./cabin-status-colors-api"

const state = vi.hoisted(() => ({
  token: "token" as string | null,
  role: "WMS_ADMIN" as UserGlobalRole,
  id: "admin",
  toast: vi.fn(),
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: state.token,
    currentUser: {
      id: state.id,
      globalRole: state.role,
      principalType: "USER",
    },
  }),
}))
vi.mock("sonner", () => ({
  toast: { error: state.toast, success: vi.fn(), dismiss: vi.fn() },
}))

const fetchMock = vi.fn()
let palette: CabinStatusColors
let queryClient: QueryClient
function surface(editor = true) {
  return (
    <QueryClientProvider client={queryClient}>
      <CabinStatusPaletteSync />
      {editor ? <CabinStatusColorsSettings /> : null}
    </QueryClientProvider>
  )
}
beforeEach(() => {
  vi.resetAllMocks()
  state.token = "token"
  state.role = "WMS_ADMIN"
  state.id = "admin"
  palette = {
    version: 2,
    updatedAt: "2026-09-05T10:00:00Z",
    colors: Object.fromEntries(
      CABIN_STATUS_COLOR_STATUSES.map((status) => [status, "#16A34A"])
    ) as CabinStatusColors["colors"],
  }
  queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
  fetchMock.mockImplementation(async (_url, init: RequestInit) => {
    if (init.method === "PUT") {
      palette = {
        ...palette,
        ...JSON.parse(init.body as string),
        version: palette.version + 1,
      }
    }
    return Response.json(palette)
  })
  vi.stubGlobal("fetch", fetchMock)
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe("global cabin palette editor and consumers", () => {
  it("saves to the owner and updates live WMS colors only after the response", async () => {
    render(surface())
    const input = await screen.findByLabelText("RGB Свободна")
    await waitFor(() =>
      expect(
        document.documentElement.style.getPropertyValue("--cabin-status-free")
      ).toBe("#16A34A")
    )
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.change(input, { target: { value: "#AA1122" } })
    expect(
      document.documentElement.style.getPropertyValue("--cabin-status-free")
    ).toBe("#16A34A")
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Сохранить цвета" }))
    await waitFor(() =>
      expect(
        document.documentElement.style.getPropertyValue("--cabin-status-free")
      ).toBe("#AA1122")
    )
    const write = fetchMock.mock.calls.find((call) => call[1].method === "PUT")!
    expect(JSON.parse(write[1].body)).toMatchObject({
      expectedVersion: 2,
      colors: { FREE: "#AA1122" },
    })
    expect(
      screen.getByRole("button", { name: "Сохранить цвета" })
    ).toHaveProperty("disabled", true)
  })
  it("preserves a dirty draft when another window changes the global palette", async () => {
    render(surface())
    const input = await screen.findByLabelText("RGB Свободна")
    fireEvent.change(input, { target: { value: "#AA1122" } })
    await act(async () => {
      queryClient.setQueryData(cabinStatusColorsQueryKey("admin"), {
        ...palette,
        version: 3,
        colors: { ...palette.colors, FREE: "#334455" },
      })
    })
    expect(input).toHaveProperty("value", "#AA1122")
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      expect.stringContaining("изменена в другом окне")
    )
    expect(
      screen.getByRole("button", { name: "Сохранить цвета" })
    ).toHaveProperty("disabled", true)
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Загрузить актуальные цвета" }))
    expect(input).toHaveProperty("value", "#334455")
  })
  it("keeps rejected changes unsaved and reloads the winning version on 409", async () => {
    render(surface())
    const input = await screen.findByLabelText("RGB Свободна")
    fireEvent.change(input, { target: { value: "#AA1122" } })
    palette = {
      ...palette,
      version: 3,
      colors: { ...palette.colors, FREE: "#445566" },
    }
    fetchMock.mockImplementation(async (_url, init: RequestInit) =>
      init.method === "PUT"
        ? Response.json({ detail: "Конфликт версий" }, { status: 409 })
        : Response.json(palette)
    )
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Сохранить цвета" }))
    await screen.findByRole("button", { name: "Загрузить актуальные цвета" })
    expect(input).toHaveProperty("value", "#AA1122")
    expect(
      document.documentElement.style.getPropertyValue("--cabin-status-free")
    ).toBe("#445566")
  })
  it("prevents malformed colors and unprivileged writes", async () => {
    const rendered = render(surface())
    const input = await screen.findByLabelText("RGB Свободна")
    fireEvent.change(input, { target: { value: "red" } })
    expect(
      screen.getByRole("button", { name: "Сохранить цвета" })
    ).toHaveProperty("disabled", true)
    expect(screen.getByRole("alert").textContent).toContain("#RRGGBB")
    state.role = "VIEWER"
    rendered.rerender(surface())
    expect(screen.queryByRole("button", { name: "Сохранить цвета" })).toBeNull()
    expect(input).toHaveProperty("disabled", true)
    expect(fetchMock.mock.calls.some((call) => call[1].method === "PUT")).toBe(
      false
    )
  })
  it("removes session colors on logout and does not reuse another user's cache", async () => {
    const rendered = render(surface(false))
    await waitFor(() =>
      expect(
        document.documentElement.style.getPropertyValue("--cabin-status-free")
      ).toBe("#16A34A")
    )
    state.token = null
    rendered.rerender(surface(false))
    expect(
      document.documentElement.style.getPropertyValue("--cabin-status-free")
    ).toBe("")
    state.token = "another-token"
    state.id = "another-user"
    fetchMock.mockImplementation(() => new Promise(() => {}))
    rendered.rerender(surface(false))
    expect(
      document.documentElement.style.getPropertyValue("--cabin-status-free")
    ).toBe("")
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })
  it("reports failure without applying an invented saved palette", async () => {
    fetchMock.mockResolvedValue(
      Response.json({ detail: "Палитра недоступна" }, { status: 503 })
    )
    render(surface())
    await screen.findByRole("alert")
    expect(
      document.documentElement.style.getPropertyValue("--cabin-status-free")
    ).toBe("")
    expect(state.toast).toHaveBeenCalled()
  })
  it("refreshes an open WMS window from the server every thirty seconds", async () => {
    render(surface(false))
    await waitFor(() =>
      expect(
        document.documentElement.style.getPropertyValue("--cabin-status-free")
      ).toBe("#16A34A")
    )
    vi.useFakeTimers()
    palette = {
      ...palette,
      version: 3,
      colors: { ...palette.colors, FREE: "#112233" },
    }
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: cabinStatusColorsQueryKey("admin"),
      })
      await vi.advanceTimersByTimeAsync(30_001)
    })
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(3)
    expect(
      document.documentElement.style.getPropertyValue("--cabin-status-free")
    ).toBe("#112233")
  })
})
