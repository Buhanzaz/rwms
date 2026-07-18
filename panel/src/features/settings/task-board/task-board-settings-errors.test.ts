import { describe, expect, it } from "vitest"

import {
  isTaskBoardSettingsConflict,
  taskBoardSettingsErrorMessage,
} from "@/features/settings/task-board/task-board-settings-errors"
import { ApiError } from "@/lib/api-client"

describe("task board settings errors", () => {
  it("recognizes HTTP and structurally compatible conflicts", () => {
    expect(
      isTaskBoardSettingsConflict(new ApiError("HTTP conflict", 409))
    ).toBe(true)
    const structuralConflict = Object.assign(new Error("Concurrent change"), {
      status: 409,
    })
    expect(isTaskBoardSettingsConflict(structuralConflict)).toBe(true)
    expect(taskBoardSettingsErrorMessage(structuralConflict, true)).toContain(
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
