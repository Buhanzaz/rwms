import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => false }))

import {
  ServiceMediaManagerDialog,
  type ServiceMediaManagerItem,
} from "@/features/media/service-media-manager-dialog"

const item: ServiceMediaManagerItem = {
  id: "photo-1",
  fileName: "photo.jpg",
  kind: "IMAGE",
  previewUrl: "blob:photo",
  rotationDegrees: 0,
  statusLabel: "Не загружено",
  pending: false,
}

afterEach(cleanup)

describe("ServiceMediaManagerDialog", () => {
  it("requires an explicit cover before confirming a new owner upload", async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()

    function Harness() {
      const [coverMediaId, setCoverMediaId] = useState<string | null>(null)
      return (
        <ServiceMediaManagerDialog
          open
          items={[item]}
          requireCover
          coverMediaId={coverMediaId}
          onOpenChange={vi.fn()}
          onAddFiles={vi.fn()}
          onRemove={vi.fn()}
          onSelectCover={(selected) => setCoverMediaId(selected.id)}
          onConfirm={onConfirm}
        />
      )
    }

    render(<Harness />)

    const done = screen.getByRole("button", { name: "Готово" })
    expect(screen.queryByRole("button", { name: /Повернуть/ })).toBeNull()
    expect((done as HTMLButtonElement).disabled).toBe(true)
    await user.click(screen.getByRole("button", { name: "Выбрать титульным" }))
    expect((done as HTMLButtonElement).disabled).toBe(false)

    await user.click(done)
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it("accepts supported images and videos and ignores unrelated files", async () => {
    const user = userEvent.setup({ applyAccept: false })
    const onAddFiles = vi.fn()

    render(
      <ServiceMediaManagerDialog
        open
        items={[]}
        onOpenChange={vi.fn()}
        onAddFiles={onAddFiles}
        onRemove={vi.fn()}
      />
    )

    const input = screen.getByLabelText("Выбрать медиафайлы")
    const jpeg = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" })
    const png = new File(["png"], "photo.png", { type: "image/png" })
    const webp = new File(["webp"], "photo.webp", { type: "image/webp" })
    const mp4 = new File(["mp4"], "video.mp4", { type: "video/mp4" })
    const webm = new File(["webm"], "video.webm", { type: "video/webm" })
    const unsupported = new File(["gif"], "animation.gif", {
      type: "image/gif",
    })
    await user.upload(input, [jpeg, png, webp, mp4, webm, unsupported])

    expect(onAddFiles).toHaveBeenCalledWith([jpeg, png, webp, mp4, webm])
    expect((input as HTMLInputElement).accept).toBe(
      "image/jpeg,image/png,image/webp,video/mp4,video/webm"
    )
  })

  it("previews video with controls and never offers it as a cabin cover", () => {
    const video: ServiceMediaManagerItem = {
      id: "video-1",
      fileName: "inspection.mp4",
      kind: "VIDEO",
      previewUrl: "blob:video",
      statusLabel: "Готово",
      pending: false,
    }

    render(
      <ServiceMediaManagerDialog
        open
        items={[video]}
        requireCover
        coverMediaId="video-1"
        onOpenChange={vi.fn()}
        onAddFiles={vi.fn()}
        onRemove={vi.fn()}
        onSelectCover={vi.fn()}
      />
    )

    const preview = screen.getByLabelText("Предпросмотр видео inspection.mp4")
    expect(preview.tagName).toBe("VIDEO")
    expect((preview as HTMLVideoElement).controls).toBe(true)
    expect((preview as HTMLVideoElement).preload).toBe("metadata")
    expect(
      screen.queryByRole("button", { name: "Выбрать титульным" })
    ).toBeNull()
    expect(
      (screen.getByRole("button", { name: "Готово" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)
    expect(
      screen.getByText("Добавьте и выберите титульную фотографию.")
    ).toBeTruthy()
  })

  it("shows upload progress under the original and withholds media actions", () => {
    render(
      <ServiceMediaManagerDialog
        open
        items={[
          {
            ...item,
            pending: true,
            statusLabel: "Загрузка",
            uploadProgress: 37,
          },
        ]}
        onOpenChange={vi.fn()}
        onAddFiles={vi.fn()}
        onRemove={vi.fn()}
        onSelectCover={vi.fn()}
      />
    )

    expect(
      screen.getByAltText("Предпросмотр photo.jpg").getAttribute("src")
    ).toBe("blob:photo")
    const progress = screen.getByRole("progressbar", {
      name: "Загрузка photo.jpg",
    })
    expect(progress.getAttribute("aria-valuenow")).toBe("37")
    expect(
      screen.queryByRole("button", { name: "Удалить photo.jpg" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выбрать титульным" })
    ).toBeNull()
  })
})
