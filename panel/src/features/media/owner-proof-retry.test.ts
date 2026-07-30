import { afterEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import {
  OWNER_PROOF_MAX_RETRIES,
  isRetryableOwnerProofError,
  ownerProofRetryDelay,
  retryOwnerProofOperation,
  shouldRetryOwnerProof,
} from "@/features/media/owner-proof-retry"

afterEach(() => {
  vi.useRealTimers()
})

describe("owner-proof media retry policy", () => {
  it("retries only eventual-consistency failures", () => {
    expect(
      isRetryableOwnerProofError(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
    ).toBe(true)
    expect(isRetryableOwnerProofError(new ApiError("Conflict", 409))).toBe(true)
    expect(isRetryableOwnerProofError(new ApiError("Unavailable", 503))).toBe(
      true
    )

    expect(isRetryableOwnerProofError(new ApiError("Unauthorized", 401))).toBe(
      false
    )
    expect(isRetryableOwnerProofError(new ApiError("Forbidden", 403))).toBe(
      false
    )
    expect(
      isRetryableOwnerProofError(
        new ApiError("Wrong forbidden code", 403, "MEDIA_FORBIDDEN")
      )
    ).toBe(false)
  })

  it("caps retries and backs off through the proof worker window", () => {
    const proofPending = new ApiError(
      "Owner proof is catching up",
      403,
      "MEDIA_OWNER_PROOF_REQUIRED"
    )

    expect(
      Array.from({ length: OWNER_PROOF_MAX_RETRIES }, (_, failureCount) =>
        shouldRetryOwnerProof(failureCount, proofPending)
      )
    ).toEqual([true, true, true, true, true, true, true, true])
    expect(shouldRetryOwnerProof(OWNER_PROOF_MAX_RETRIES, proofPending)).toBe(
      false
    )
    expect(shouldRetryOwnerProof(0, new ApiError("Forbidden", 403))).toBe(false)
    expect(
      Array.from({ length: OWNER_PROOF_MAX_RETRIES }, (_, failureCount) =>
        ownerProofRetryDelay(failureCount)
      )
    ).toEqual([250, 500, 1_000, 2_000, 2_000, 2_000, 2_000, 2_000])
  })

  it("retries owner-proof failures through the shared operation policy", async () => {
    vi.useFakeTimers()
    const operation = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce("ready")

    const result = retryOwnerProofOperation(operation)

    await vi.advanceTimersByTimeAsync(250)

    await expect(result).resolves.toBe("ready")
    expect(operation).toHaveBeenCalledTimes(2)
  })

  it("does not retry nonretryable media errors", async () => {
    const error = new ApiError("Media access denied", 403, "MEDIA_FORBIDDEN")
    const operation = vi.fn<() => Promise<string>>().mockRejectedValue(error)

    await expect(retryOwnerProofOperation(operation)).rejects.toBe(error)

    expect(operation).toHaveBeenCalledOnce()
  })
})
