import { afterEach, describe, expect, it, vi } from "vitest"

import { ApiError, bearerRequest } from "@/lib/api-client"

afterEach(() => vi.unstubAllGlobals())

describe("bearerRequest", () => {
  it.each([
    [401, "AUTHENTICATION_REQUIRED"],
    [403, "WAREHOUSE_ACCESS_FORBIDDEN"],
    [409, "ENTRY_VERSION_CONFLICT"],
  ])(
    "preserves the authoritative HTTP %s Problem Details status",
    async (status, code) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              type: "about:blank",
              title: "RWMS request failed",
              status,
              detail: `Ошибка ${status}`,
              code,
            }),
            {
              status,
              headers: { "Content-Type": "application/problem+json" },
            }
          )
        )
      )

      let failure: unknown
      try {
        await bearerRequest("access-token", "/api/example")
      } catch (error) {
        failure = error
      }

      expect(failure).toBeInstanceOf(ApiError)
      expect(failure).toMatchObject({
        status,
        code,
        message: `Ошибка ${status}`,
      })
    }
  )

  it("keeps a malformed Problem Details response typed and safe", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("<html>gateway error</html>", {
          status: 502,
          headers: { "Content-Type": "application/problem+json" },
        })
      )
    )

    let failure: unknown
    try {
      await bearerRequest("access-token", "/api/example")
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).toMatchObject({
      status: 502,
      code: null,
      message: "Запрос завершился с ошибкой 502",
    })
  })
})
