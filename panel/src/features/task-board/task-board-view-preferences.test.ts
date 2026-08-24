import { beforeEach, describe, expect, it } from "vitest"

import {
  readTaskBoardViewPreferences,
  taskBoardViewPreferencesStorageKey,
  writeTaskBoardViewPreferences,
} from "@/features/task-board/task-board-view-preferences"

const warehouseId = "00000000-0000-4000-8000-000000000001"

describe("task-board view preferences", () => {
  beforeEach(() => window.localStorage.clear())

  it("round-trips warehouse-scoped non-authoritative layout state", () => {
    writeTaskBoardViewPreferences(warehouseId, {
      showFutureSubtasks: true,
      collapsedQueueKeys: ["repair", "electricity"],
      boardScrollLeft: 420,
      boardScrollTop: 15,
      queueScrollTops: { repair: 120, electricity: 340 },
    })

    expect(readTaskBoardViewPreferences(warehouseId)).toEqual({
      showFutureSubtasks: true,
      collapsedQueueKeys: ["repair", "electricity"],
      boardScrollLeft: 420,
      boardScrollTop: 15,
      queueScrollTops: { repair: 120, electricity: 340 },
    })
    expect(readTaskBoardViewPreferences("another-warehouse")).toEqual({
      showFutureSubtasks: false,
      collapsedQueueKeys: null,
      boardScrollLeft: 0,
      boardScrollTop: 0,
      queueScrollTops: {},
    })
  })

  it("ignores malformed values instead of treating browser state as domain state", () => {
    window.localStorage.setItem(
      taskBoardViewPreferencesStorageKey(warehouseId),
      JSON.stringify({
        version: 1,
        showFutureSubtasks: "yes",
        collapsedQueueKeys: ["repair", 4],
        boardScrollLeft: -1,
        boardScrollTop: "20",
        queueScrollTops: { repair: -8, electricity: 40, broken: "50" },
      })
    )

    expect(readTaskBoardViewPreferences(warehouseId)).toEqual({
      showFutureSubtasks: false,
      collapsedQueueKeys: null,
      boardScrollLeft: 0,
      boardScrollTop: 0,
      queueScrollTops: { electricity: 40 },
    })
  })
})
