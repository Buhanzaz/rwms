import { describe, expect, it } from "vitest"

import {
  buildAcceptanceFilterOptions,
  createEmptyAcceptanceFilters,
  filterAcceptanceTasks,
} from "@/features/acceptance/acceptance-filtering"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

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
    acceptanceStatus: "PENDING",
    startedAt: null,
    completedAt: "2026-07-20T08:00:00Z",
    warehouseId: "warehouse-1",
    rentalItemId: `rental-${id}`,
    cabinNumber: `БЫТ-${id}`,
    actorId: `author-${id}`,
    sourceParty: `Источник ${id}`,
    dispatchDate: null,
    subtasks: [],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    readyAt: "2026-07-20T08:00:00Z",
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-20T08:00:00Z",
    ...overrides,
  }
}

describe("acceptance filtering", () => {
  it("builds local options from the pending acceptance list", () => {
    const tasks = [
      task("001", { sourceParty: "Клиент", actorId: "worker-1" }),
      task("002", {
        kind: "REWORK",
        origin: "INVENTORY",
        sourceParty: "Склад",
        actorId: "worker-2",
      }),
    ]

    const authorLabels = new Map([
      ["worker-1", "Иван Иванов"],
      ["worker-2", "Пётр Петров"],
    ])

    expect(
      buildAcceptanceFilterOptions(
        tasks,
        (actorId) => authorLabels.get(actorId) ?? "Автор недоступен"
      )
    ).toEqual({
      sources: [
        { value: "INVENTORY:REWORK", label: "Доработка" },
        { value: "DIRECT_REPAIR:REPAIR", label: "Прямой ремонт" },
      ],
      sourceParties: [
        { value: "Клиент", label: "Клиент" },
        { value: "Склад", label: "Склад" },
      ],
      authors: [
        { value: "worker-1", label: "Иван Иванов" },
        { value: "worker-2", label: "Пётр Петров" },
      ],
    })
  })

  it("combines text, source, party, author, status, and ready-date filters locally", () => {
    const target = task("007", {
      kind: "REWORK",
      origin: "INVENTORY",
      cabinNumber: "БЫТ-007",
      sourceParty: "Клиент А",
      actorId: "worker-7",
      acceptanceStatus: "IN_REWORK",
      readyAt: "2026-07-21T10:00:00Z",
    })
    const other = task("008", {
      kind: "REWORK",
      origin: "INVENTORY",
      sourceParty: "Клиент А",
      actorId: "worker-7",
      acceptanceStatus: "IN_REWORK",
      readyAt: "2026-07-23T10:00:00Z",
    })

    const result = filterAcceptanceTasks([target, other], "доработка", {
      ...createEmptyAcceptanceFilters(),
      sources: ["INVENTORY:REWORK"],
      sourceParties: ["Клиент А"],
      authors: ["worker-7"],
      statuses: ["IN_REWORK"],
      readyAtFrom: "2026-07-21",
      readyAtTo: "2026-07-21",
    })

    expect(result).toEqual([target])
  })
})
