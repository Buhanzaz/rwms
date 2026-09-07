import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { ApiError } from "@/lib/api-client"
import { MapSettingsCard } from "./map-settings-card"
import { mapSettingsApi, type AdminMapSettings } from "./map-settings-api"

const auth = vi.hoisted(() => ({ token: "admin-token" as string | null }))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: auth.token, currentUser: { id: "admin" } }),
}))

const initial: AdminMapSettings = {
  version: 1,
  provider: "STANDARD",
  yandex_api_key_configured: false,
}
const postMessage = vi.fn()
beforeEach(() => {
  auth.token = "admin-token"
  postMessage.mockReset()
  vi.stubGlobal(
    "BroadcastChannel",
    class {
      postMessage = postMessage
      close() {}
    }
  )
  vi.spyOn(mapSettingsApi, "get").mockResolvedValue(initial)
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function renderCard() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MapSettingsCard />
    </QueryClientProvider>
  )
  return userEvent.setup()
}

describe("map provider settings", () => {
  it("requires a key, saves Yandex and notifies logistics only after a successful save", async () => {
    const save = vi.spyOn(mapSettingsApi, "save").mockResolvedValue({
      version: 2,
      provider: "YANDEX",
      yandex_api_key_configured: true,
    })
    const user = renderCard()
    await user.click(await screen.findByRole("radio", { name: "Яндекс Карты" }))
    expect(
      screen.getByRole("button", { name: "Сохранить карту" })
    ).toHaveProperty("disabled", true)
    await user.type(
      screen.getByLabelText("API-ключ Яндекс Карт"),
      "  test-browser-key  "
    )
    await user.click(screen.getByRole("button", { name: "Сохранить карту" }))
    await waitFor(() =>
      expect(save).toHaveBeenCalledWith("admin-token", {
        expected_version: 1,
        provider: "YANDEX",
        yandex_api_key: "test-browser-key",
      })
    )
    expect(postMessage).toHaveBeenCalledWith({ type: "changed", version: 2 })
    expect(screen.getByLabelText("API-ключ Яндекс Карт")).toHaveProperty(
      "value",
      ""
    )
    expect(screen.getByText(/Ключ сохранён\. Пустое поле/)).toBeTruthy()
  })

  it("keeps a saved key when switching back to the standard map", async () => {
    vi.mocked(mapSettingsApi.get).mockResolvedValue({
      version: 5,
      provider: "YANDEX",
      yandex_api_key_configured: true,
    })
    const save = vi.spyOn(mapSettingsApi, "save").mockResolvedValue({
      version: 6,
      provider: "STANDARD",
      yandex_api_key_configured: true,
    })
    const user = renderCard()
    await user.click(await screen.findByRole("radio", { name: "Стандартная" }))
    await user.click(screen.getByRole("button", { name: "Сохранить карту" }))
    expect(save).toHaveBeenCalledWith("admin-token", {
      expected_version: 5,
      provider: "STANDARD",
      yandex_api_key: null,
    })
  })

  it("keeps input and requires a reload after a version conflict without notifying logistics", async () => {
    vi.spyOn(mapSettingsApi, "save").mockRejectedValue(
      new ApiError("conflict", 409, "MAP_SETTINGS_VERSION_CONFLICT")
    )
    const user = renderCard()
    await user.click(await screen.findByRole("radio", { name: "Яндекс Карты" }))
    await user.type(screen.getByLabelText("API-ключ Яндекс Карт"), "new-key")
    await user.click(screen.getByRole("button", { name: "Сохранить карту" }))
    expect(
      await screen.findByText(/Настройки изменил другой администратор/)
    ).toBeTruthy()
    expect(screen.getByLabelText("API-ключ Яндекс Карт")).toHaveProperty(
      "value",
      "new-key"
    )
    expect(
      screen.getByRole("button", { name: "Сохранить карту" })
    ).toHaveProperty("disabled", true)
    expect(postMessage).not.toHaveBeenCalled()
    vi.mocked(mapSettingsApi.get).mockResolvedValue({ ...initial, version: 2 })
    await user.click(
      screen.getByRole("button", {
        name: "Загрузить актуальные настройки карты",
      })
    )
    await waitFor(() =>
      expect(screen.getByLabelText("API-ключ Яндекс Карт")).toHaveProperty(
        "value",
        ""
      )
    )
  })

  it("shows failed reads without inventing a default and does not request without authentication", async () => {
    vi.mocked(mapSettingsApi.get).mockRejectedValue(
      new Error("Сервис недоступен")
    )
    renderCard()
    expect(await screen.findByText("Настройки карты недоступны")).toBeTruthy()
    expect(screen.queryByRole("radio", { name: "Стандартная" })).toBeNull()
    cleanup()
    vi.mocked(mapSettingsApi.get).mockClear()
    auth.token = null
    renderCard()
    expect(mapSettingsApi.get).not.toHaveBeenCalled()
  })
})
