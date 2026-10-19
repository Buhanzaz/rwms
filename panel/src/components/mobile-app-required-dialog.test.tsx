import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import {
  MANAGER_MOBILE_APP_DOWNLOAD_PATH,
  MobileAppRequiredDialog,
} from "@/components/mobile-app-required-dialog"

describe("MobileAppRequiredDialog", () => {
  it("links to the published manager APK and can be dismissed", () => {
    const onOpenChange = vi.fn()

    render(
      <MobileAppRequiredDialog
        open
        onOpenChange={onOpenChange}
        operation="Создание сметы"
      />
    )

    expect(
      screen.getByRole("dialog", {
        name: "Создание сметы доступно в мобильном приложении",
      })
    ).toBeTruthy()
    expect(screen.getByRole("alert").textContent).toContain(
      "Используйте приложение RWMS Manager"
    )
    expect(
      screen
        .getByRole("link", { name: "Скачать приложение" })
        .getAttribute("href")
    ).toBe(MANAGER_MOBILE_APP_DOWNLOAD_PATH)

    fireEvent.click(screen.getByRole("button", { name: "Понятно" }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
