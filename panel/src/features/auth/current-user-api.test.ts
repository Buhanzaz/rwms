import { describe, expect, it, vi } from "vitest"

import { getCurrentUser } from "@/features/auth/current-user-api"

describe("getCurrentUser", () => {
  it("loads the user through the gateway auth prefix", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          id: "user-1",
          username: "operator",
          principalType: "USER",
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    )

    await getCurrentUser("access-token")

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe("/auth/api/users/me")
    expect((init?.headers as Headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })
})
