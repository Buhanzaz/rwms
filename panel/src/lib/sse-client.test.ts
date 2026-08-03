import { afterEach, expect, it, vi } from "vitest"

import { startBearerEventStream, type SseEvent } from "@/lib/sse-client"

afterEach(() => vi.unstubAllGlobals())

it("uses a bearer header and parses a fragmented warehouse invalidation event", async () => {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(
        new TextEncoder().encode(
          'id: event-1\nevent: warehouse-invalidation\ndata: {"scope":"RES'
        )
      )
      controller.enqueue(new TextEncoder().encode('YNC"}\n\n'))
      controller.close()
    },
  })
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(stream, {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    })
  )
  vi.stubGlobal("fetch", fetchMock)

  const event = await new Promise<SseEvent>((resolve) => {
    let stop: () => void = () => undefined
    stop = startBearerEventStream({
      url: "https://panel.example.test/api/asset/v1/events?warehouseId=warehouse",
      accessToken: "access-token",
      onEvent: (received) => {
        stop()
        resolve(received)
      },
    })
  })

  expect(event).toEqual({
    id: "event-1",
    event: "warehouse-invalidation",
    data: '{"scope":"RESYNC"}',
  })
  const headers = new Headers(fetchMock.mock.calls[0]?.[1]?.headers)
  expect(headers.get("Authorization")).toBe("Bearer access-token")
  expect(headers.get("Accept")).toBe("text/event-stream")
})
