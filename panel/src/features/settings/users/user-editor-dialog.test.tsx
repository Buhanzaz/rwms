import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { UserEditorDialog } from "@/features/settings/users/user-editor-dialog"
import type { AdminUser } from "@/features/settings/users/model/users"

const user: AdminUser = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 1,
  username: "rental.manager",
  firstName: "Пётр",
  lastName: "Петров",
  email: "petrov@example.ru",
  timeZoneId: "Europe/Moscow",
  active: true,
  globalRole: "RENTAL_MANAGER",
  mobileAppAccess: false,
  rentalAccess: false,
  warehouseAccesses: [],
}

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("UserEditorDialog rental access", () => {
  it("submits the explicitly selected rental and chat entitlement", async () => {
    const onSubmit = vi.fn(async () => undefined)
    const interaction = userEvent.setup()

    render(
      <UserEditorDialog
        open
        user={user}
        warehouses={[]}
        pending={false}
        serverError={null}
        deactivationBlockedReason={null}
        allowedRoles={["SYSTEM_ADMIN", "WMS_ADMIN", "RENTAL_MANAGER"]}
        onOpenChange={vi.fn()}
        onSubmit={onSubmit}
      />
    )

    await interaction.click(
      screen.getByRole("checkbox", { name: "Доступ к аренде и чату" })
    )
    await interaction.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith({
        profile: expect.objectContaining({ rentalAccess: true }),
        accesses: [],
      })
    })
  })
})
