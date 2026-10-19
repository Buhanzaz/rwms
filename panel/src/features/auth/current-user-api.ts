import { AUTHORITY } from "@/features/auth/auth-config"
import type { CurrentUser } from "@/features/auth/auth-model"
import { ApiError, bearerRequest } from "@/lib/api-client"

/** A brief gateway/network failure may retry this read, never an auth denial. */
export async function getCurrentUser(accessToken: string) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await bearerRequest<CurrentUser>(
        accessToken,
        `${AUTHORITY}/api/users/me`,
        {
          signal: AbortSignal.timeout(10_000),
        }
      )
    } catch (error) {
      if (
        attempt >= 1 ||
        !(error instanceof ApiError) ||
        ![0, 502, 503, 504].includes(error.status)
      )
        throw error
      await new Promise((resolve) => setTimeout(resolve, 400))
    }
  }
}
