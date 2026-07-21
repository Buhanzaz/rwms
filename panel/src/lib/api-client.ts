export class ApiError extends Error {
  readonly status: number
  readonly code: string | null

  constructor(message: string, status: number, code: string | null = null) {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.code = code
  }
}

type ErrorEnvelope = {
  message?: unknown
  detail?: unknown
  error?: unknown
  code?: unknown
}

function getEnvelopeMessage(body: ErrorEnvelope) {
  const candidate = body.message ?? body.detail ?? body.error
  return typeof candidate === "string" && candidate.trim() ? candidate : null
}

async function readErrorDetails(response: Response) {
  const fallback = `Запрос завершился с ошибкой ${response.status}`
  const contentType = response.headers.get("content-type") ?? ""

  if (!contentType.includes("json")) {
    const text = (await response.text()).trim()
    if (text.startsWith("{")) {
      try {
        const parsed = JSON.parse(text) as ErrorEnvelope
        return {
          message: getEnvelopeMessage(parsed) ?? text,
          code: typeof parsed.code === "string" ? parsed.code : null,
        }
      } catch {
        // Preserve a non-JSON upstream error body verbatim.
      }
    }
    return { message: text || fallback, code: null }
  }

  const body = (await response.json()) as ErrorEnvelope
  return {
    message: getEnvelopeMessage(body) ?? fallback,
    code: typeof body.code === "string" && body.code.trim() ? body.code : null,
  }
}

export async function bearerRequest<T>(
  accessToken: string,
  input: string | URL,
  init: RequestInit = {}
): Promise<T> {
  if (!accessToken.trim()) {
    throw new Error("Не получен токен доступа.")
  }

  const headers = new Headers(init.headers)
  headers.set("Authorization", `Bearer ${accessToken}`)
  headers.set("Accept", "application/json")

  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json")
  }

  const response = await fetch(input, { ...init, headers })

  if (!response.ok) {
    const error = await readErrorDetails(response)
    throw new ApiError(error.message, response.status, error.code)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}
