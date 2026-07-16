import { describe, expect, it } from "vitest"

import {
  isTaskBoardSettingsConflict,
  taskBoardSettingsErrorMessage,
} from "@/features/settings/task-board/task-board-settings-errors"
import { ApiError } from "@/lib/api-client"

describe("task board settings errors", () => {
  it("recognizes HTTP and browser mock conflicts", () => {
    expect(
      isTaskBoardSettingsConflict(new ApiError("HTTP conflict", 409))
    ).toBe(true)
    const browserConflict = Object.assign(new Error("MOCK conflict"), {
      status: 409,
    })
    expect(isTaskBoardSettingsConflict(browserConflict)).toBe(true)
    expect(taskBoardSettingsErrorMessage(browserConflict, true)).toContain(
      "Данные обновлены с сервера"
    )
  })

  it("does not treat validation errors as optimistic conflicts", () => {
    expect(
      isTaskBoardSettingsConflict(
        Object.assign(new Error("Validation"), { status: 400 })
      )
    ).toBe(false)
  })
})
