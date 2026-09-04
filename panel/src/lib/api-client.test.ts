import { afterEach, describe, expect, it, vi } from "vitest"

import { ApiError, bearerRequest } from "@/lib/api-client"

afterEach(() => vi.unstubAllGlobals())

describe("bearerRequest", () => {
  it.each([
    [
      401,
      "AUTHENTICATION_REQUIRED",
      "Сеанс завершён. Войдите в систему и повторите действие.",
    ],
    [
      403,
      "WAREHOUSE_ACCESS_FORBIDDEN",
      "У вас нет доступа к выбранному складу. Выберите доступный склад или обратитесь к администратору.",
    ],
    [
      409,
      "ENTRY_VERSION_CONFLICT",
      "Данные уже изменились. Обновите страницу и повторите действие.",
    ],
  ])(
    "preserves HTTP %s and domain code while rendering safe copy",
    async (status, code, expectedMessage) => {
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
        message: expectedMessage,
        diagnosticMessage: `Ошибка ${status}`,
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
      message: "Сервис временно недоступен. Повторите попытку позже.",
    })
  })

  it.each([
    [
      400,
      { detail: "RMS Logistics Service returned HTTP 400" },
      "Не удалось выполнить действие. Проверьте введённые данные и повторите попытку.",
    ],
    [
      500,
      { detail: "java.lang.IllegalStateException: planner failed" },
      "Сервис временно недоступен. Повторите попытку позже.",
    ],
    [
      400,
      { detail: '{"exception":"SQL error","trace":"secret"}' },
      "Не удалось выполнить действие. Проверьте введённые данные и повторите попытку.",
    ],
  ])(
    "does not expose technical Problem Details for HTTP %s",
    async (status, body, expectedMessage) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(JSON.stringify(body), {
            status,
            headers: { "Content-Type": "application/problem+json" },
          })
        )
      )

      await expect(
        bearerRequest("access-token", "/api/example")
      ).rejects.toMatchObject({ status, message: expectedMessage })
    }
  )

  it("turns a network failure into safe Russian copy", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch"))
    )

    await expect(
      bearerRequest("access-token", "/api/example")
    ).rejects.toMatchObject({
      status: 0,
      code: "NETWORK_ERROR",
      message:
        "Не удалось связаться с сервером. Проверьте подключение и повторите попытку.",
      diagnosticMessage: "Failed to fetch",
    })
  })

  it("keeps an actionable Russian domain detail", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            detail: "Временное окно уже занято. Выберите другое время.",
            code: "DELIVERY_WINDOW_CONFLICT",
          }),
          {
            status: 409,
            headers: { "Content-Type": "application/problem+json" },
          }
        )
      )
    )

    await expect(
      bearerRequest("access-token", "/api/example")
    ).rejects.toMatchObject({
      status: 409,
      code: "DELIVERY_WINDOW_CONFLICT",
      message: "Временное окно уже занято. Выберите другое время.",
    })
  })
})
