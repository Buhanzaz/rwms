import { beforeEach, describe, expect, it } from "vitest"

import {
  canonicalClientUuid,
  resetMaintenanceRuntimeForTests,
  withIdempotencyKey,
} from "@/features/maintenance/maintenance-runtime"

describe("maintenance technical identifiers", () => {
  beforeEach(() => {
    resetMaintenanceRuntimeForTests()
  })

  it("reuses a pending command after failure and reload, then rotates after success", async () => {
    const command = {
      warehouseId: "warehouse-opaque-id",
      reason: "secret-comment-must-not-be-persisted",
      nested: { expectedVersion: 7, enabled: true },
    }

    let failedAttemptKey = ""
    await expect(
      withIdempotencyKey("maintenance.repair.accept", command, (key) => {
        failedAttemptKey = key
        return Promise.reject(new TypeError("network unavailable"))
      })
    ).rejects.toThrow("network unavailable")

    resetMaintenanceRuntimeForTests(false)

    let replayKey = ""
    await withIdempotencyKey(
      "maintenance.repair.accept",
      {
        nested: { enabled: true, expectedVersion: 7 },
        reason: "secret-comment-must-not-be-persisted",
        warehouseId: "warehouse-opaque-id",
      },
      (key) => {
        replayKey = key
        return Promise.resolve("confirmed")
      }
    )
    expect(replayKey).toBe(failedAttemptKey)

    let nextIntentKey = ""
    await withIdempotencyKey("maintenance.repair.accept", command, (key) => {
      nextIntentKey = key
      return Promise.resolve("confirmed")
    })
    expect(nextIntentKey).not.toBe(failedAttemptKey)
  })

  it("keeps client UUID stable across a page reload", async () => {
    const clientId = await canonicalClientUuid("legacy-client-secret-value")
    resetMaintenanceRuntimeForTests(false)
    await expect(
      canonicalClientUuid("legacy-client-secret-value")
    ).resolves.toBe(clientId)
  })

  it("persists only opaque lookup keys and UUID values", async () => {
    await expect(
      withIdempotencyKey(
        "maintenance.estimate.amend",
        { reason: "secret-comment-must-not-be-persisted" },
        () => Promise.reject(new TypeError("offline"))
      )
    ).rejects.toThrow("offline")
    await canonicalClientUuid("legacy-client-secret-value")

    const persisted = Array.from(
      { length: window.sessionStorage.length },
      (_, index) => {
        const key = window.sessionStorage.key(index) ?? ""
        return `${key}=${window.sessionStorage.getItem(key) ?? ""}`
      }
    ).join("\n")

    expect(persisted).not.toContain("secret-comment-must-not-be-persisted")
    expect(persisted).not.toContain("legacy-client-secret-value")
    expect(persisted).toMatch(/rwms:maintenance-technical-id:v1:/)
    expect(persisted).toContain(
      "client:1fbf911e56a0a012ef5d0923d7ff27ba2ae216b7209555f4a6fdde45a08666df"
    )
    expect(persisted).toContain(
      "command:e2d7f8f0d09b0d76b7148be087dd50acc722753adc9b6324ae19d13c6541fcec"
    )
  })
})
