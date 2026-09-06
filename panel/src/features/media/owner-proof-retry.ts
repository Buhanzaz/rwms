import { ApiError } from "@/lib/api-client"

/**
 * Keep this in lockstep with the Android uploader. An owner projection is
 * asynchronous, so media reads and commands use one bounded retry window
 * instead of exposing a transient authorization failure to the user.
 */
export const OWNER_PROOF_MAX_RETRIES = 8
export const OWNER_PROOF_RETRY_INITIAL_DELAY_MS = 250
export const OWNER_PROOF_RETRY_MAX_DELAY_MS = 2_000

export function isRetryableOwnerProofError(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 409 ||
      error.status === 503 ||
      (error.status === 403 && error.code === "MEDIA_OWNER_PROOF_REQUIRED"))
  )
}

/**
 * `failureCount` is zero for the first failed operation, matching TanStack
 * Query's retry callback. Eight retries yield at most nine total attempts.
 */
export function shouldRetryOwnerProof(failureCount: number, error: unknown) {
  return (
    failureCount >= 0 &&
    failureCount < OWNER_PROOF_MAX_RETRIES &&
    isRetryableOwnerProofError(error)
  )
}

export function ownerProofRetryDelay(failureCount: number) {
  return Math.min(
    OWNER_PROOF_RETRY_INITIAL_DELAY_MS * 2 ** Math.max(0, failureCount),
    OWNER_PROOF_RETRY_MAX_DELAY_MS
  )
}

export async function retryOwnerProofOperation<T>(
  operation: () => Promise<T>,
  signal?: AbortSignal
): Promise<T> {
  for (let failureCount = 0; ; failureCount += 1) {
    signal?.throwIfAborted()
    try {
      return await operation()
    } catch (error) {
      signal?.throwIfAborted()
      if (!shouldRetryOwnerProof(failureCount, error)) throw error
      await new Promise<void>((resolve, reject) => {
        const abort = () => {
          clearTimeout(timer)
          reject(signal?.reason)
        }
        const timer = setTimeout(() => {
          signal?.removeEventListener("abort", abort)
          resolve()
        }, ownerProofRetryDelay(failureCount))
        signal?.addEventListener("abort", abort, { once: true })
      })
    }
  }
}
