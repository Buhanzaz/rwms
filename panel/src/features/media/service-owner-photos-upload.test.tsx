import { useState } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  MediaAsset,
  ServiceMediaOwner,
} from "@/features/media/media-service"

const client = vi.hoisted(() => ({
  listOwnerMedia: vi.fn(),
  createVariantObjectUrl: vi.fn(),
  uploadFile: vi.fn(),
  deleteAsset: vi.fn(),
}))
vi.mock("@/features/media/media-service", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/media/media-service")>()),
  createHttpMediaClient: () => client,
}))
vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({ photos }: { photos: Array<{ id: string }> }) => (
    <div>
      {photos.map((photo) => (
        <span key={photo.id}>{photo.id}</span>
      ))}
    </div>
  ),
}))

import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

const owner: ServiceMediaOwner = {
  ownerType: "MAINTENANCE_ESTIMATE",
  ownerId: "11111111-1111-4111-8111-111111111111",
  warehouseId: "22222222-2222-4222-8222-222222222222",
  context: "ESTIMATE",
}
let serverAssets: MediaAsset[]
const queries: QueryClient[] = []

function asset(index: number): MediaAsset {
  return {
    id: `33333333-3333-4333-8333-${String(index).padStart(12, "0")}`,
    folderId: "44444444-4444-4444-8444-444444444444",
    fileName: `${index}.jpg`,
    contentType: "image/jpeg",
    kind: "IMAGE",
    status: "READY",
    version: 2,
    generation: 1,
    rotationDegrees: 0,
    sortOrder: index,
    sizeBytes: 1,
    createdAt: "2026-09-06T10:00:00Z",
    variants: [
      {
        kind: "MEDIUM",
        contentType: "image/webp",
        contentPath: `/api/media/${index}`,
        width: 10,
        height: 10,
      },
    ],
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function renderGallery(element: React.ReactNode) {
  const query = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  })
  queries.push(query)
  return render(
    <QueryClientProvider client={query}>{element}</QueryClientProvider>
  )
}

function selectFiles(count: number) {
  fireEvent.click(screen.getByRole("button", { name: "Добавить" }))
  fireEvent.change(screen.getByLabelText("Выбрать медиафайлы"), {
    target: {
      files: Array.from(
        { length: count },
        (_, index) => new File(["x"], `${index}.jpg`, { type: "image/jpeg" })
      ),
    },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  serverAssets = []
  client.listOwnerMedia.mockImplementation(async () => ({
    items: [...serverAssets],
    next: null,
  }))
  client.createVariantObjectUrl.mockImplementation(
    async (_token, _owner, variant) => ({
      url: `blob:${variant.contentPath}`,
      dispose: vi.fn(),
    })
  )
})

afterEach(() => {
  cleanup()
  queries.splice(0).forEach((query) => query.clear())
  vi.restoreAllMocks()
})

describe("ServiceOwnerPhotos upload recovery", () => {
  it("retains in-flight successes, refetches on failure and retries only failed or unstarted files", async () => {
    const gates = Array.from({ length: 4 }, () =>
      deferred<{ asset: MediaAsset }>()
    )
    client.uploadFile.mockImplementation(
      (_token, _owner, file: File) =>
        gates[Number(file.name.split(".")[0])]!.promise
    )
    const references = vi.fn()
    const ready = vi.fn()
    const revoke = vi.spyOn(URL, "revokeObjectURL")
    vi.spyOn(URL, "createObjectURL").mockImplementation(
      (file) => `blob:local-${(file as File).name}`
    )
    renderGallery(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        authoritativeReadyReferences={[]}
        onReadyReferencesChange={references}
        onReadyStateChange={ready}
      />
    )
    await waitFor(() => expect(ready).toHaveBeenLastCalledWith(true))
    selectFiles(5)
    await waitFor(() => expect(client.uploadFile).toHaveBeenCalledTimes(4))
    const failedCommand = client.uploadFile.mock.calls[0]!.slice(0, 6)
    await act(async () => {
      gates[0]!.reject(new Error("Connection lost"))
    })
    expect(client.uploadFile).toHaveBeenCalledTimes(4)
    expect(ready).toHaveBeenLastCalledWith(false)
    serverAssets = [asset(1), asset(2), asset(3), asset(99)]
    await act(async () => {
      for (const index of [1, 2, 3])
        gates[index]!.resolve({ asset: asset(index) })
    })
    await waitFor(() =>
      expect(references).toHaveBeenLastCalledWith(
        [1, 2, 3].map((index) => ({ mediaId: asset(index).id, generation: 1 }))
      )
    )
    expect(client.listOwnerMedia.mock.calls.length).toBeGreaterThan(1)
    expect(screen.queryByText(asset(99).id)).toBeNull()
    expect(screen.getAllByText("Ошибка загрузки")).toHaveLength(2)
    expect(
      screen.getByRole("button", { name: "Повторить загрузку 0.jpg" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Повторить загрузку 4.jpg" })
    ).toBeTruthy()

    client.uploadFile.mockImplementation(async (_token, _owner, file: File) => {
      const uploaded = asset(Number(file.name.split(".")[0]))
      serverAssets.push(uploaded)
      return { asset: uploaded }
    })
    fireEvent.click(
      screen.getByRole("button", { name: "Повторить загрузку 0.jpg" })
    )
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: "Повторить загрузку 0.jpg" })
      ).toBeNull()
    )
    await waitFor(() =>
      expect(
        (
          screen.getByRole("button", {
            name: "Повторить загрузку 4.jpg",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(false)
    )
    expect(client.uploadFile.mock.calls[4]!.slice(0, 6)).toEqual(failedCommand)
    expect(ready).toHaveBeenLastCalledWith(false)
    fireEvent.click(
      screen.getByRole("button", { name: "Повторить загрузку 4.jpg" })
    )
    await waitFor(() => expect(ready).toHaveBeenLastCalledWith(true))
    expect(
      client.uploadFile.mock.calls.map((call) => (call[2] as File).name)
    ).toEqual(["0.jpg", "1.jpg", "2.jpg", "3.jpg", "0.jpg", "4.jpg"])
    expect(client.uploadFile.mock.calls[5]![3]).toBe(4)
    expect(client.uploadFile.mock.calls[5]![4]).toBe(failedCommand[4])
    expect(references.mock.lastCall![0]).toHaveLength(5)
    await waitFor(() =>
      expect(
        revoke.mock.calls.filter(([url]) =>
          String(url).startsWith("blob:local-")
        )
      ).toHaveLength(5)
    )
    expect(screen.queryByText("Ошибка загрузки")).toBeNull()
  })

  it("allows discarding the failed local file while keeping the successful selection ready", async () => {
    const references = vi.fn()
    const ready = vi.fn()
    client.uploadFile.mockImplementation(async (_token, _owner, file: File) => {
      if (file.name === "0.jpg") throw new Error("Connection lost")
      serverAssets.push(asset(1))
      return { asset: asset(1) }
    })
    renderGallery(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        authoritativeReadyReferences={[]}
        onReadyReferencesChange={references}
        onReadyStateChange={ready}
      />
    )
    await waitFor(() => expect(ready).toHaveBeenLastCalledWith(true))
    selectFiles(2)
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Удалить 0.jpg" })).toBeTruthy()
    )
    fireEvent.click(screen.getByRole("button", { name: "Удалить 0.jpg" }))
    await waitFor(() => expect(ready).toHaveBeenLastCalledWith(true))
    expect(references).toHaveBeenLastCalledWith([
      { mediaId: asset(1).id, generation: 1 },
    ])
    expect(client.uploadFile).toHaveBeenCalledTimes(2)
    expect(client.deleteAsset).not.toHaveBeenCalled()
  })

  it("keeps a newly created owner's partial batch mounted and preserves cover and command keys on retry", async () => {
    const gates = [
      deferred<{ asset: MediaAsset }>(),
      deferred<{ asset: MediaAsset }>(),
    ]
    client.uploadFile.mockImplementation(
      (_token, _owner, file: File) =>
        gates[Number(file.name.split(".")[0])]!.promise
    )
    const ensure = vi.fn()
    const references = vi.fn()
    const ready = vi.fn()
    const cover = vi.fn()
    function Harness() {
      const [currentOwner, setOwner] = useState<ServiceMediaOwner | null>(null)
      const [coverId, setCoverId] = useState<string | null>(null)
      return (
        <ServiceOwnerPhotos
          accessToken="token"
          owner={currentOwner}
          shrinkToContainer
          ensureOwner={async () => {
            ensure()
            setOwner(owner)
            return owner
          }}
          readOnly={false}
          requireCover
          coverMediaId={coverId}
          authoritativeReadyReferences={[]}
          onReadyReferencesChange={references}
          onReadyStateChange={ready}
          onCoverMediaIdChange={(id) => {
            cover(id)
            setCoverId(id)
          }}
        />
      )
    }
    renderGallery(<Harness />)
    expect(screen.getByText("Нет медиа").classList.contains("xl:min-h-0")).toBe(
      true
    )
    selectFiles(2)
    fireEvent.click(
      screen.getAllByRole("button", { name: "Выбрать титульным" })[1]!
    )
    fireEvent.click(screen.getByRole("button", { name: "Готово" }))
    await waitFor(() => expect(client.uploadFile).toHaveBeenCalledTimes(2))
    const failedCommand = client.uploadFile.mock.calls[0]!.slice(0, 6)
    expect(screen.getByRole("dialog")).toBeTruthy()
    serverAssets = [asset(1)]
    await act(async () => {
      gates[0]!.reject(new Error("Connection lost"))
      gates[1]!.resolve({ asset: asset(1) })
    })
    await waitFor(() => expect(screen.getByText("Загружено")).toBeTruthy())
    expect(screen.getByText("Connection lost")).toBeTruthy()
    expect(ready).toHaveBeenLastCalledWith(false)
    expect(cover).not.toHaveBeenCalled()
    client.uploadFile.mockImplementation(async () => {
      serverAssets.push(asset(0))
      return { asset: asset(0) }
    })
    fireEvent.click(screen.getByRole("button", { name: "Готово" }))
    await waitFor(() => expect(ready).toHaveBeenLastCalledWith(true))
    expect(ensure).toHaveBeenCalledOnce()
    expect(client.uploadFile).toHaveBeenCalledTimes(3)
    expect(client.uploadFile.mock.calls[2]!.slice(0, 6)).toEqual(failedCommand)
    expect(cover).toHaveBeenCalledExactlyOnceWith(asset(1).id)
    expect(references.mock.lastCall![0]).toEqual(
      expect.arrayContaining(
        [0, 1].map((index) => ({ mediaId: asset(index).id, generation: 1 }))
      )
    )
    expect(references.mock.lastCall![0]).toHaveLength(2)
    expect(screen.queryByRole("dialog")).toBeNull()
  })
})
