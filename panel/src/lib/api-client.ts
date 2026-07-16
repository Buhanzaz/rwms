import { DEV_AUTH_BYPASS_TOKEN } from "@/features/auth/auth-config"

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = "ApiError"
    this.status = status
  }
}

type ErrorEnvelope = {
  message?: unknown
  detail?: unknown
  error?: unknown
}

function getEnvelopeMessage(body: ErrorEnvelope) {
  const candidate = body.message ?? body.detail ?? body.error
  return typeof candidate === "string" && candidate.trim() ? candidate : null
}

async function readErrorMessage(response: Response) {
  const fallback = `Запрос завершился с ошибкой ${response.status}`
  const contentType = response.headers.get("content-type") ?? ""

  if (!contentType.includes("json")) {
    const text = (await response.text()).trim()
    if (text.startsWith("{")) {
      try {
        const parsed = JSON.parse(text) as ErrorEnvelope
        return getEnvelopeMessage(parsed) ?? text
      } catch {
        // Preserve a non-JSON upstream error body verbatim.
      }
    }
    return text || fallback
  }

  const body = (await response.json()) as ErrorEnvelope
  return getEnvelopeMessage(body) ?? fallback
}

export async function bearerRequest<T>(
  accessToken: string,
  input: string | URL,
  init: RequestInit = {}
): Promise<T> {
  const headers = new Headers(init.headers)
  if (accessToken === DEV_AUTH_BYPASS_TOKEN) {
    headers.delete("Authorization")
  } else {
    headers.set("Authorization", `Bearer ${accessToken}`)
  }
  headers.set("Accept", "application/json")

  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json")
  }

  const response = await fetch(input, { ...init, headers })

  if (!response.ok) {
    throw new ApiError(await readErrorMessage(response), response.status)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}
