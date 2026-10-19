import { describe, expect, it, vi } from "vitest"

import { runMediaUploadQueue } from "@/features/media/media-upload-queue"

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe("runMediaUploadQueue", () => {
  it("bounds uploads at four and preserves input order", async () => {
    const gates = Array.from({ length: 6 }, () => deferred<string>())
    const active = new Set<number>()
    let peakActive = 0
    const upload = vi.fn(async (_job: string, index: number) => {
      active.add(index)
      peakActive = Math.max(peakActive, active.size)
      const result = await gates[index]!.promise
      active.delete(index)
      return result
    })

    const result = runMediaUploadQueue(["a", "b", "c", "d", "e", "f"], upload)
    await vi.waitFor(() => expect(upload).toHaveBeenCalledTimes(4))
    expect(upload.mock.calls.map(([job]) => job)).toEqual(["a", "b", "c", "d"])

    gates[3]!.resolve("result-d")
    await vi.waitFor(() => expect(upload).toHaveBeenCalledTimes(5))
    gates[1]!.resolve("result-b")
    await vi.waitFor(() => expect(upload).toHaveBeenCalledTimes(6))
    gates[5]!.resolve("result-f")
    gates[4]!.resolve("result-e")
    gates[2]!.resolve("result-c")
    gates[0]!.resolve("result-a")

    await expect(result).resolves.toEqual([
      "result-a",
      "result-b",
      "result-c",
      "result-d",
      "result-e",
      "result-f",
    ])
    expect(peakActive).toBe(4)
  })

  it("waits for started jobs and does not start queued jobs after a failure", async () => {
    const gates = Array.from({ length: 6 }, () => deferred<string>())
    const upload = vi.fn(
      async (_job: string, index: number) => gates[index]!.promise
    )
    const result = runMediaUploadQueue(["a", "b", "c", "d", "e", "f"], upload)
    let settled = false
    void result.catch(() => {
      settled = true
    })
    await vi.waitFor(() => expect(upload).toHaveBeenCalledTimes(4))

    const failure = new Error("upload failed")
    gates[0]!.reject(failure)
    await Promise.resolve()
    expect(settled).toBe(false)
    expect(upload).toHaveBeenCalledTimes(4)

    gates[1]!.reject(new Error("later failure"))
    gates[2]!.resolve("result-c")
    gates[3]!.resolve("result-d")
    await expect(result).rejects.toBe(failure)
    expect(upload).toHaveBeenCalledTimes(4)
  })

  it("passes the original job object without replacing stable command keys", async () => {
    const job = {
      commandKeys: {
        createSession: "create-key",
        uploadAndFinalize: "finalize-key",
      },
    }
    const upload = vi.fn(async (received: typeof job) => received.commandKeys)

    await expect(runMediaUploadQueue([job], upload)).resolves.toEqual([
      job.commandKeys,
    ])
    expect(upload.mock.calls[0]?.[0]).toBe(job)
  })
})
