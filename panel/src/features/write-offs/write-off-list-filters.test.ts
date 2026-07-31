import { describe, expect, it } from "vitest"

import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"

import {
  buildWriteOffFilterOptions,
  EMPTY_WRITE_OFF_LIST_FILTERS,
  filterWriteOffTasks,
  formatWriteOffAuthor,
  WRITE_OFF_AUTHOR_UNAVAILABLE,
  writeOffActorIds,
} from "./write-off-list-filters"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const AUTHOR_ID = "22222222-2222-4222-8222-222222222222"

function task(
  id: string,
  overrides: Partial<RepairTaskDto> = {}
): RepairTaskDto {
  return {
    id,
    version: 1,
    status: "COMPLETED",
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    acceptanceStatus: "WRITTEN_OFF",
    startedAt: null,
    completedAt: "2026-07-20T10:00:00Z",
    warehouseId: WAREHOUSE_ID,
    rentalItemId: `rental-${id}`,
    cabinNumber: `БЫТ-${id}`,
    actorId: "task-author",
    sourceParty: null,
    dispatchDate: null,
    subtasks: [],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    writtenOffAt: "2026-07-20T12:00:00Z",
    decisionActorId: AUTHOR_ID,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-20T12:00:00Z",
    ...overrides,
  }
}

const actor: DossierActorDisplay = {
  subjectId: AUTHOR_ID,
  principalType: "USER",
  globalRole: "WAREHOUSE_MANAGER",
  username: "ivanov",
  firstName: "Иван",
  lastName: "Иванов",
  email: "ivanov@example.test",
}

describe("write-off list filtering", () => {
  it("uses a resolved surname and name instead of a raw author identifier", () => {
    const actorsById = new Map([[AUTHOR_ID, actor]])

    expect(formatWriteOffAuthor(AUTHOR_ID, actorsById)).toBe("Иванов Иван")
    expect(formatWriteOffAuthor("missing", actorsById)).toBe(
      WRITE_OFF_AUTHOR_UNAVAILABLE
    )
    expect(
      writeOffActorIds([
        task("001"),
        task("002", { decisionActorId: "legacy" }),
      ])
    ).toEqual([AUTHOR_ID])
  })

  it("filters by the visible grid dimensions", () => {
    const first = task("001")
    const second = task("002", {
      origin: "INVENTORY",
      kind: "REWORK",
      writtenOffAt: "2026-07-22T12:00:00Z",
      decisionActorId: null,
    })
    const actorsById = new Map([[AUTHOR_ID, actor]])

    expect(
      buildWriteOffFilterOptions([first, second], actorsById).authors
    ).toContainEqual({ value: AUTHOR_ID, label: "Иванов Иван" })
    expect(
      filterWriteOffTasks(
        [first, second],
        "Иванов",
        EMPTY_WRITE_OFF_LIST_FILTERS,
        actorsById
      )
    ).toEqual([first])
    expect(
      filterWriteOffTasks(
        [first, second],
        "",
        {
          ...EMPTY_WRITE_OFF_LIST_FILTERS,
          authors: [AUTHOR_ID],
          states: ["WRITTEN_OFF"],
          dateFrom: "2026-07-20",
          dateTo: "2026-07-20",
        },
        actorsById
      )
    ).toEqual([first])
  })
})
