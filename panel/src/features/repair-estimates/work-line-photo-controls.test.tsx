import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const media = vi.hoisted(() => ({
  useServiceOwnerMedia: vi.fn(),
  upload: vi.fn(),
}))

vi.mock("@/features/media/use-service-owner-media", () => ({
  useServiceOwnerMedia: media.useServiceOwnerMedia,
}))

import type {
  MediaAsset,
  ReadyMediaReference,
} from "@/features/media/media-service"
import { maintenanceEstimateMediaOwner } from "@/features/media/media-service"
import { WorkLinePhotoControls } from "@/features/repair-estimates/work-line-photo-controls"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const estimateId = "00000000-0000-4000-8000-000000000002"
const currentMediaId = "00000000-0000-4000-8000-000000000003"
const otherWorkMediaId = "00000000-0000-4000-8000-000000000004"
const freeMediaId = "00000000-0000-4000-8000-000000000005"

function asset(id: string, fileName: string): MediaAsset {
  return {
    id,
    folderId: "00000000-0000-4000-8000-000000000010",
    fileName,
    contentType: "image/jpeg",
    kind: "IMAGE",
    status: "READY",
    version: 1,
    generation: 1,
    rotationDegrees: 0,
    sortOrder: 0,
    sizeBytes: 100,
    createdAt: "2026-08-03T12:00:00Z",
    variants: [],
  }
}

function reference(mediaId: string): ReadyMediaReference {
  return { mediaId, generation: 1 }
}

beforeEach(() => {
  media.upload.mockReset()
  media.useServiceOwnerMedia.mockReturnValue({
    assets: [
      asset(currentMediaId, "current.jpg"),
      asset(otherWorkMediaId, "other-work.jpg"),
      asset(freeMediaId, "free.jpg"),
    ],
    photos: [
      { id: currentMediaId, url: "blob:current", createdAt: null },
      { id: freeMediaId, url: "blob:free", createdAt: null },
    ],
    readyReferences: [
      reference(currentMediaId),
      reference(otherWorkMediaId),
      reference(freeMediaId),
    ],
    logicalPhotoCount: 3,
    upload: media.upload,
    query: { isLoading: false },
    pending: false,
    error: null,
  })
})

afterEach(cleanup)

describe("WorkLinePhotoControls", () => {
  it("keeps the current work selection and hides photos assigned to another work", async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <WorkLinePhotoControls
        accessToken="media-token"
        owner={maintenanceEstimateMediaOwner(estimateId, warehouseId)}
        excludedMediaIds={new Set([otherWorkMediaId])}
        value={[reference(currentMediaId)]}
        onChange={onChange}
      />
    )

    await user.click(
      screen.getByRole("button", { name: "Выбрать из сделанных" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Выбрать фото работы",
    })

    expect(within(dialog).getByText("current.jpg")).toBeTruthy()
    expect(within(dialog).getByText("free.jpg")).toBeTruthy()
    expect(within(dialog).queryByText("other-work.jpg")).toBeNull()

    await user.click(within(dialog).getByText("free.jpg"))
    await user.click(within(dialog).getByRole("button", { name: "Выбрать" }))

    expect(onChange).toHaveBeenCalledWith([
      reference(currentMediaId),
      reference(freeMediaId),
    ])
  })

  it("uploads selected images without making a photo mandatory", async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    media.upload.mockResolvedValue([asset(freeMediaId, "new.jpg")])

    const { rerender } = render(
      <WorkLinePhotoControls
        accessToken="media-token"
        owner={maintenanceEstimateMediaOwner(estimateId, warehouseId)}
        excludedMediaIds={new Set()}
        value={[]}
        onChange={onChange}
      />
    )

    expect(screen.getByText(/Фото необязательны/)).toBeTruthy()
    await user.upload(
      screen.getByLabelText("Добавить фото работы"),
      new File(["photo"], "new.jpg", { type: "image/jpeg" })
    )

    await waitFor(() => expect(media.upload).toHaveBeenCalledTimes(1))
    rerender(
      <WorkLinePhotoControls
        accessToken="media-token"
        owner={maintenanceEstimateMediaOwner(estimateId, warehouseId)}
        excludedMediaIds={new Set()}
        value={[]}
        onChange={onChange}
      />
    )
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith([reference(freeMediaId)])
    )
  })

  it("manages only the selected work photos with preview, reuse and removal", async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const onOpenChange = vi.fn()

    render(
      <WorkLinePhotoControls
        accessToken="media-token"
        owner={maintenanceEstimateMediaOwner(estimateId, warehouseId)}
        excludedMediaIds={new Set([otherWorkMediaId])}
        value={[reference(currentMediaId)]}
        open
        onOpenChange={onOpenChange}
        onChange={onChange}
      />
    )

    const manager = screen.getByRole("dialog", {
      name: "Фотографии работы",
    })
    expect(
      within(manager).getByAltText("Предпросмотр current.jpg")
    ).toBeTruthy()
    expect(within(manager).queryByText("other-work.jpg")).toBeNull()

    expect(
      within(manager).queryByRole("button", { name: /Повернуть/ })
    ).toBeNull()

    await user.click(
      within(manager).getByRole("button", {
        name: "Удалить current.jpg из работы",
      })
    )
    expect(onChange).toHaveBeenCalledWith([])

    await user.click(
      within(manager).getByRole("button", { name: "Выбрать из сделанных" })
    )
    const selector = await screen.findByRole("dialog", {
      name: "Выбрать фото работы",
    })
    expect(within(selector).getByText("free.jpg")).toBeTruthy()
    expect(within(selector).queryByText("other-work.jpg")).toBeNull()
  })
})
