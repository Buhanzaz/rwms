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
  title?: unknown
  code?: unknown
}

function getEnvelopeMessage(body: ErrorEnvelope) {
  const candidate = body.detail ?? body.message ?? body.error ?? body.title
  return typeof candidate === "string" && candidate.trim() ? candidate : null
}

function asErrorEnvelope(value: unknown): ErrorEnvelope | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null
  return value as ErrorEnvelope
}

async function readErrorDetails(response: Response) {
  const fallback = `Запрос завершился с ошибкой ${response.status}`
  const text = await response.text().catch(() => "")

  try {
    const body = asErrorEnvelope(JSON.parse(text))
    if (!body) return { message: fallback, code: null }
    return {
      message: getEnvelopeMessage(body) ?? fallback,
      code:
        typeof body.code === "string" && body.code.trim() ? body.code : null,
    }
  } catch {
    // A proxy or upstream can return malformed Problem Details. Keep the
    // response status authoritative and do not expose an arbitrary body.
    return { message: fallback, code: null }
  }
}

/**
 * Converts every non-success gateway response, including malformed Problem
 * Details, into the same status-bearing error used by normal panel requests.
 */
export async function apiErrorFromResponse(
  response: Response
): Promise<ApiError> {
  const error = await readErrorDetails(response)
  return new ApiError(error.message, response.status, error.code)
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
    throw await apiErrorFromResponse(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}
