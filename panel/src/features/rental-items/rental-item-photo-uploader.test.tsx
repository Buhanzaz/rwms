import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { RentalItemPhotoUploader } from "@/features/rental-items/rental-item-photo-uploader"

const { preparePhoto, toastError } = vi.hoisted(() => ({
  preparePhoto: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("@/features/rental-items/api/rental-items-api", () => ({
  prepareRentalItemPhotoUpload: preparePhoto,
  rotateRentalItemCreationPhoto: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { error: toastError },
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RentalItemPhotoUploader", () => {
  it("shows an accessible error and keeps the current photos when preparation fails", async () => {
    preparePhoto.mockRejectedValueOnce(new Error("Файл повреждён"))
    const onChange = vi.fn()
    const { container } = render(
      <RentalItemPhotoUploader photos={[]} onChange={onChange} />
    )
    const input =
      container.querySelector<HTMLInputElement>('input[type="file"]')!

    fireEvent.change(input, {
      target: {
        files: [new File(["broken"], "broken.jpg", { type: "image/jpeg" })],
      },
    })

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Файл повреждён"
    )
    expect(toastError).toHaveBeenCalledWith("Файл повреждён")
    expect(onChange).not.toHaveBeenCalled()
  })
})
