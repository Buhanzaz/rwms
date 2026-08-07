import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { describe, expect, it, vi } from "vitest"

vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => false }))

import {
  ServiceMediaManagerDialog,
  type ServiceMediaManagerItem,
} from "@/features/media/service-media-manager-dialog"

const item: ServiceMediaManagerItem = {
  id: "photo-1",
  fileName: "photo.jpg",
  previewUrl: "blob:photo",
  rotationDegrees: 0,
  statusLabel: "Не загружено",
  pending: false,
}

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
    await user.click(
      screen.getByRole("button", { name: "Выбрать титульным" })
    )
    expect((done as HTMLButtonElement).disabled).toBe(false)

    await user.click(done)
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })
})
