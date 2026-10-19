import { afterEach, describe, expect, it, vi } from "vitest"
import { getCurrentUser } from "./current-user-api"

afterEach(() => vi.unstubAllGlobals())

describe("current user profile recovery", () => {
  it("retries a temporary gateway failure once with the same bearer", async () => {
    const profile = { id: "user-id", principalType: "USER" }
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 502 }))
      .mockResolvedValueOnce(Response.json(profile))
    vi.stubGlobal("fetch", fetch)

    await expect(getCurrentUser("access-token")).resolves.toEqual(profile)
    expect(fetch).toHaveBeenCalledTimes(2)
    for (const [, init] of fetch.mock.calls) {
      expect(init.headers.get("Authorization")).toBe("Bearer access-token")
    }
  })

  it("does not retry an authorization denial", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal("fetch", fetch)

    await expect(getCurrentUser("access-token")).rejects.toMatchObject({
      status: 401,
    })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it("stops after two unsuccessful reads instead of hiding the outage", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError("Failed to fetch"))
    vi.stubGlobal("fetch", fetch)

    await expect(getCurrentUser("access-token")).rejects.toMatchObject({
      status: 0,
    })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
