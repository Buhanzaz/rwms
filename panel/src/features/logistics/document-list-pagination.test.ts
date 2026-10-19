import { afterEach, describe, expect, it, vi } from "vitest"

import { listAllLogisticsDocumentPages } from "@/features/logistics/document-list-pagination"

function pageResponse(
  content: unknown[],
  page: number,
  totalElements: number,
  totalPages: number,
  hasNext: boolean
) {
  return new Response(JSON.stringify(content), {
    headers: {
      "Content-Type": "application/json",
      "X-RWMS-Page": String(page),
      "X-RWMS-Page-Size": "100",
      "X-RWMS-Total-Elements": String(totalElements),
      "X-RWMS-Total-Pages": String(totalPages),
      "X-RWMS-Has-Next": String(hasNext),
    },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("listAllLogisticsDocumentPages", () => {
  it("loads every server page for one exact-day result", async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => index)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(pageResponse(firstPage, 0, 101, 2, true))
      .mockResolvedValueOnce(pageResponse([100], 1, 101, 2, false))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listAllLogisticsDocumentPages(
        "access-token",
        "/api/logistics/v1/returns?warehouseId=warehouse&scheduledDate=2026-09-02",
        (value) => value as number[]
      )
    ).resolves.toEqual([...firstPage, 100])

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(new URL(fetchMock.mock.calls[0][0]).searchParams.get("page")).toBe(
      "0"
    )
    expect(new URL(fetchMock.mock.calls[1][0]).searchParams.get("page")).toBe(
      "1"
    )
    for (const call of fetchMock.mock.calls) {
      expect(new URL(call[0]).searchParams.get("size")).toBe("100")
    }
  })

  it("fails explicitly when pagination metadata is missing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("[]", {
          headers: { "Content-Type": "application/json" },
        })
      )
    )

    await expect(
      listAllLogisticsDocumentPages(
        "access-token",
        "/api/logistics/v1/returns?warehouseId=warehouse&scheduledDate=2026-09-02",
        (value) => value as unknown[]
      )
    ).rejects.toMatchObject({ status: 502, code: "INVALID_API_RESPONSE" })
  })
})
