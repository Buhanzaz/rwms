import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  getCabinStatusColors,
  saveCabinStatusColors,
  CABIN_STATUS_COLOR_STATUSES,
  type CabinStatusColors,
} from "./cabin-status-colors-api"

const fetchMock = vi.fn()
const palette: CabinStatusColors = {
  version: 3,
  updatedAt: "2026-09-05T10:00:00Z",
  colors: Object.fromEntries(
    CABIN_STATUS_COLOR_STATUSES.map((status) => [status, "#16A34A"])
  ) as CabinStatusColors["colors"],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal("fetch", fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe("cabin status colors boundary", () => {
  it("reads one global same-origin palette with bearer authorization and no warehouse scope", async () => {
    fetchMock.mockResolvedValue(Response.json(palette))
    expect(await getCabinStatusColors("token")).toEqual(palette)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).origin).toBe(window.location.origin)
    expect(new URL(url).pathname).toBe(
      "/api/asset/v1/cabin-settings/status-colors"
    )
    expect(new URL(url).search).toBe("")
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
  })
  it("saves the full map with expectedVersion", async () => {
    fetchMock.mockResolvedValue(Response.json({ ...palette, version: 4 }))
    expect(
      (await saveCabinStatusColors("token", 3, palette.colors)).version
    ).toBe(4)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe("PUT")
    expect(JSON.parse(init.body as string)).toEqual({
      expectedVersion: 3,
      colors: palette.colors,
    })
  })
  it.each([
    { ...palette, colors: { FREE: "#FFFFFF" } },
    { ...palette, colors: { ...palette.colors, FREE: "url(https://invalid)" } },
    { ...palette, version: -1 },
    { ...palette, colors: { ...palette.colors, UNKNOWN: "#FFFFFF" } },
  ])(
    "rejects a malformed or incomplete successful response",
    async (payload) => {
      fetchMock.mockResolvedValue(Response.json(payload))
      await expect(getCabinStatusColors("token")).rejects.toMatchObject({
        code: "INVALID_API_RESPONSE",
      })
    }
  )
  it("preserves a 409 instead of treating it as a saved palette", async () => {
    fetchMock.mockResolvedValue(
      Response.json(
        { detail: "Изменено другим администратором", code: "ASSET_CONFLICT" },
        { status: 409 }
      )
    )
    await expect(
      saveCabinStatusColors("token", 3, palette.colors)
    ).rejects.toMatchObject({ status: 409, code: "ASSET_CONFLICT" })
  })
  it("does not request the service without a token", async () => {
    await expect(getCabinStatusColors("")).rejects.toThrow("токен")
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
