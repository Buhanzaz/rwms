import { userFacingApiMessage } from "@/lib/user-facing-error"

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly diagnosticMessage: string

  constructor(message: string, status: number, code: string | null = null) {
    super(userFacingApiMessage({ detail: message, status, code }))
    this.name = "ApiError"
    this.status = status
    this.code = code
    this.diagnosticMessage = message
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
  const fallback = `HTTP response status ${response.status}`
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

/** Converts transport and parsing failures into a safe panel API error. */
export function apiErrorFromRequestFailure(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause
  const aborted = errorLikeProperty(cause, "name") === "AbortError"
  return new ApiError(
    diagnosticMessage(cause),
    0,
    aborted ? "REQUEST_ABORTED" : "NETWORK_ERROR"
  )
}

/** Converts an invalid success payload into a safe panel API error. */
export function invalidApiResponseError(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause
  return new ApiError(diagnosticMessage(cause), 502, "INVALID_API_RESPONSE")
}

async function bearerResponse(
  accessToken: string,
  input: string | URL,
  init: RequestInit = {}
) {
  if (!accessToken.trim()) {
    throw new Error("Не получен токен доступа.")
  }

  const headers = new Headers(init.headers)
  headers.set("Authorization", `Bearer ${accessToken}`)
  headers.set("Accept", "application/json")

  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json")
  }

  let response: Response
  try {
    response = await fetch(input, { ...init, headers })
  } catch (error) {
    throw apiErrorFromRequestFailure(error)
  }

  if (!response.ok) {
    throw await apiErrorFromResponse(response)
  }

  return response
}

async function successBody<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T
  }

  try {
    return (await response.json()) as T
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

/** Returns a parsed bearer response together with its contract metadata headers. */
export async function bearerRequestWithResponse<T>(
  accessToken: string,
  input: string | URL,
  init: RequestInit = {}
) {
  const response = await bearerResponse(accessToken, input, init)
  return { data: await successBody<T>(response), response }
}

export async function bearerRequest<T>(
  accessToken: string,
  input: string | URL,
  init: RequestInit = {}
): Promise<T> {
  return (await bearerRequestWithResponse<T>(accessToken, input, init)).data
}

function diagnosticMessage(cause: unknown): string {
  const message = errorLikeProperty(cause, "message")
  if (message) return message
  return typeof cause === "string" && cause.trim()
    ? cause
    : "Unknown transport failure"
}

function errorLikeProperty(
  cause: unknown,
  property: "message" | "name"
): string | null {
  if (
    (typeof cause !== "object" && typeof cause !== "function") ||
    cause === null
  ) {
    return null
  }
  try {
    const value = Reflect.get(cause, property)
    return typeof value === "string" && value.trim() ? value : null
  } catch {
    return null
  }
}
