import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { mapSettingsApi, notifyMapSettingsChanged } from "./map-settings-api"

const fetchMock = vi.fn()
const settings = {
  version: 4,
  provider: "STANDARD",
  yandex_api_key_configured: true,
}
beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock)
  fetchMock.mockReset()
})
afterEach(() => vi.unstubAllGlobals())

describe("global administrative map settings", () => {
  it("reads through the isolated same-origin gateway with authorization and no cache", async () => {
    fetchMock.mockResolvedValue(Response.json(settings))
    expect(await mapSettingsApi.get("admin-token")).toEqual(settings)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(
      `${window.location.origin}/api/logistics-planner/v1/admin/map-settings`
    )
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer admin-token"
    )
    expect(init.cache).toBe("no-store")
    await expect(mapSettingsApi.get("")).rejects.toThrow("токен")
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("writes the exact observed version and replacement key", async () => {
    fetchMock.mockResolvedValue(
      Response.json({ ...settings, version: 5, provider: "YANDEX" })
    )
    const input = {
      expected_version: 4,
      provider: "YANDEX" as const,
      yandex_api_key: "test-browser-key",
    }
    await mapSettingsApi.save("admin-token", input)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe("PUT")
    expect(JSON.parse(init.body as string)).toEqual(input)
  })

  it.each([
    null,
    {},
    { ...settings, version: 0 },
    { ...settings, version: 1.5 },
    { ...settings, provider: "UNKNOWN" },
    { ...settings, yandex_api_key_configured: "yes" },
    { ...settings, provider: "YANDEX", yandex_api_key_configured: false },
  ])("rejects an incomplete configuration", async (payload) => {
    fetchMock.mockResolvedValue(Response.json(payload))
    await expect(mapSettingsApi.get("admin-token")).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
  })

  it("retains the stale version conflict from the owner", async () => {
    fetchMock.mockResolvedValue(
      Response.json({ code: "MAP_SETTINGS_VERSION_CONFLICT" }, { status: 409 })
    )
    await expect(
      mapSettingsApi.save("admin-token", {
        expected_version: 3,
        provider: "STANDARD",
        yandex_api_key: null,
      })
    ).rejects.toMatchObject({
      status: 409,
      code: "MAP_SETTINGS_VERSION_CONFLICT",
    })
  })

  it("broadcasts only invalidation metadata and tolerates unavailable channels", () => {
    const postMessage = vi.fn(),
      close = vi.fn()
    vi.stubGlobal(
      "BroadcastChannel",
      class {
        postMessage = postMessage
        close = close
      }
    )
    notifyMapSettingsChanged(5)
    expect(postMessage).toHaveBeenCalledWith({ type: "changed", version: 5 })
    expect(close).toHaveBeenCalledOnce()
    vi.stubGlobal(
      "BroadcastChannel",
      class {
        constructor() {
          throw new Error("unavailable")
        }
      }
    )
    expect(() => notifyMapSettingsChanged(6)).not.toThrow()
  })
})
