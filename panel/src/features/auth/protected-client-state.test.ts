import { QueryClient } from "@tanstack/react-query"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  acquireMediaPreview,
  clearMediaPreviewCache,
  getCachedMediaPreviewUrl,
} from "@/features/media/media-preview-cache"
import { ProtectedClientState } from "@/features/auth/protected-client-state"

afterEach(() => {
  clearMediaPreviewCache()
})

describe("ProtectedClientState", () => {
  it("fences staleTime-infinite and late A work from principal B while preserving bootstrap state", async () => {
    const bootstrapClient = new QueryClient()
    bootstrapClient.setQueryData(["public-client-presentation", "offer"], {
      title: "Public offer",
    })
    const protectedClients: QueryClient[] = []
    const state = new ProtectedClientState(() => {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
      })
      protectedClients.push(client)
      return client
    })
    const principalA = await state.activate({
      subjectId: "principal-a",
      grantRevision: "grant-a",
    })
    await principalA.queryClient.fetchQuery({
      queryKey: ["rental-items"],
      queryFn: async () => ["A only"],
      staleTime: Infinity,
    })
    principalA.queryClient.getMutationCache().build(principalA.queryClient, {
      mutationFn: async () => undefined,
    })
    let resolveLateA!: (value: string) => void
    const lateA = principalA.queryClient.fetchQuery({
      queryKey: ["rental-items", "late"],
      queryFn: () =>
        new Promise<string>((resolve) => {
          resolveLateA = resolve
        }),
    })
    void lateA.catch(() => undefined)
    const cancelA = vi.spyOn(principalA.queryClient, "cancelQueries")

    await state.deactivate()
    const principalB = await state.activate({
      subjectId: "principal-b",
      grantRevision: "grant-b",
    })
    resolveLateA("late A response")
    await Promise.resolve()

    expect(cancelA).toHaveBeenCalledOnce()
    expect(principalA.queryClient.getQueryCache().getAll()).toHaveLength(0)
    expect(principalA.queryClient.getMutationCache().getAll()).toHaveLength(0)
    expect(
      principalB.queryClient.getQueryData(["rental-items"])
    ).toBeUndefined()
    expect(
      principalB.queryClient.getQueryData(["rental-items", "late"])
    ).toBeUndefined()
    expect(
      bootstrapClient.getQueryData(["public-client-presentation", "offer"])
    ).toEqual({ title: "Public offer" })
    expect(protectedClients).toHaveLength(2)
  })

  it("replaces a same-subject client when its verified grant revision changes", async () => {
    const state = new ProtectedClientState()
    const principalA = await state.activate({
      subjectId: "principal-a",
      grantRevision: "warehouse-view",
    })
    principalA.queryClient.setQueryData(["equipment-items"], ["A only"])
    const disposePreview = vi.fn()
    const preview = await acquireMediaPreview(
      "warehouse-a:cabin-a:media-a",
      async () => ({
        url: "blob:principal-a",
        contentType: "image/webp",
        size: 42,
        dispose: disposePreview,
      })
    )
    const sameRevision = await state.activate({
      subjectId: "principal-a",
      grantRevision: "warehouse-view",
    })
    const upgradedGrant = await state.activate({
      subjectId: "principal-a",
      grantRevision: "warehouse-manage",
    })

    expect(sameRevision).toBe(principalA)
    expect(upgradedGrant).not.toBe(principalA)
    expect(
      upgradedGrant.queryClient.getQueryData(["equipment-items"])
    ).toBeUndefined()
    expect(disposePreview).toHaveBeenCalledOnce()
    expect(
      getCachedMediaPreviewUrl("warehouse-a:cabin-a:media-a")
    ).toBeUndefined()
    preview.release()
  })
})
