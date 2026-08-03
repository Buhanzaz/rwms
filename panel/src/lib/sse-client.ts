export type SseEvent = Readonly<{
  id: string | null
  event: string
  data: string
}>

type EventStreamOptions = Readonly<{
  url: string | URL
  accessToken: string
  onEvent: (event: SseEvent) => void
  onReconnect?: () => void
}>

const INITIAL_RETRY_MS = 1_000
const MAX_RETRY_MS = 30_000

/**
 * Starts an authenticated SSE stream and returns an idempotent stop function.
 * Native EventSource cannot send the panel's bearer header, so this small
 * fetch-based client keeps the token out of URLs and reconnects safely.
 */
export function startBearerEventStream(options: EventStreamOptions) {
  if (!options.accessToken.trim()) return () => undefined

  const controller = new AbortController()
  let stopped = false
  let lastEventId = ""
  let retryMs = INITIAL_RETRY_MS

  const wait = async (duration: number) => {
    await new Promise<void>((resolve) => {
      const timer = window.setTimeout(resolve, duration)
      controller.signal.addEventListener(
        "abort",
        () => {
          window.clearTimeout(timer)
          resolve()
        },
        { once: true }
      )
    })
  }

  const run = async () => {
    while (!stopped && !controller.signal.aborted) {
      let connectionReceivedEvent = false
      try {
        const headers = new Headers({
          Authorization: `Bearer ${options.accessToken}`,
          Accept: "text/event-stream",
          "Cache-Control": "no-cache",
        })
        if (lastEventId) headers.set("Last-Event-ID", lastEventId)
        const response = await fetch(options.url, {
          method: "GET",
          headers,
          cache: "no-store",
          signal: controller.signal,
        })

        if (response.status === 401 || response.status === 403) return
        if (!response.ok) {
          throw new Error(`SSE request failed with status ${response.status}`)
        }
        if (!response.body) throw new Error("SSE response has no body")

        retryMs = INITIAL_RETRY_MS
        await consumeStream(response.body, controller.signal, (event) => {
          connectionReceivedEvent = true
          if (event.id) lastEventId = event.id
          options.onEvent(event)
        })
      } catch (error) {
        if (controller.signal.aborted || stopped) return
        // Abort/network errors are expected during a gateway restart. The next
        // connection starts with a scoped RESYNC request from the caller.
        void error
      }

      if (controller.signal.aborted || stopped) return
      if (connectionReceivedEvent) options.onReconnect?.()
      await wait(retryMs)
      retryMs = Math.min(MAX_RETRY_MS, retryMs * 2)
    }
  }

  void run()
  return () => {
    if (stopped) return
    stopped = true
    controller.abort()
  }
}

async function consumeStream(
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal,
  onEvent: (event: SseEvent) => void
) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""

  try {
    while (!signal.aborted) {
      const chunk = await reader.read()
      if (chunk.done) break
      buffer += decoder.decode(chunk.value, { stream: true })
      buffer = dispatchFrames(buffer, onEvent)
    }
    buffer += decoder.decode()
    if (buffer.trim()) dispatchFrame(buffer, onEvent)
  } finally {
    reader.releaseLock()
  }
}

function dispatchFrames(buffer: string, onEvent: (event: SseEvent) => void) {
  const normalized = buffer.replaceAll("\r\n", "\n").replaceAll("\r", "\n")
  const frames = normalized.split("\n\n")
  const remainder = frames.pop() ?? ""
  frames.forEach((frame) => dispatchFrame(frame, onEvent))
  return remainder
}

function dispatchFrame(frame: string, onEvent: (event: SseEvent) => void) {
  let id: string | null = null
  let event = "message"
  const data: string[] = []
  for (const line of frame.split("\n")) {
    if (!line || line.startsWith(":")) continue
    const separator = line.indexOf(":")
    const field = separator < 0 ? line : line.slice(0, separator)
    const value =
      separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "")
    if (field === "id") id = value
    else if (field === "event") event = value || "message"
    else if (field === "data") data.push(value)
  }
  if (data.length > 0) onEvent({ id, event, data: data.join("\n") })
}
