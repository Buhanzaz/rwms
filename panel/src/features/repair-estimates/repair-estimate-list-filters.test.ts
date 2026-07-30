import { describe, expect, it } from "vitest"

import type { RepairEstimateSummaryDto } from "@/features/repair-estimates/model/repair-estimate"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"

import {
  EMPTY_REPAIR_ESTIMATE_LIST_FILTERS,
  filterRepairEstimates,
  formatRepairEstimateAuthor,
  repairEstimateAuthorIds,
} from "./repair-estimate-list-filters"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const ACTOR_ID = "00000000-0000-4000-8000-000000000002"

function estimate(
  overrides: Partial<RepairEstimateSummaryDto> = {}
): RepairEstimateSummaryDto {
  return {
    id: "00000000-0000-4000-8000-000000000003",
    version: 1,
    status: "DRAFT",
    warehouseId: WAREHOUSE_ID,
    rentalItemId: "00000000-0000-4000-8000-000000000004",
    cabinNumber: "БЫТ-001",
    authorName: ACTOR_ID,
    sourceParty: "ООО Север",
    dispatchDate: null,
    totalAmount: "0.00",
    createdAt: "2026-07-20T10:00:00Z",
    updatedAt: "2026-07-20T10:00:00Z",
    ...overrides,
  }
}

const actor: DossierActorDisplay = {
  subjectId: ACTOR_ID,
  principalType: "USER",
  globalRole: "WAREHOUSE_MANAGER",
  username: "ivanov",
  firstName: "Иван",
  lastName: "Иванов",
  middleName: "Иванович",
  email: "ivanov@example.test",
}

describe("repair estimate list filters", () => {
  it("uses the employee name instead of the actor UUID", () => {
    const actors = new Map([[ACTOR_ID, actor]])

    expect(formatRepairEstimateAuthor(ACTOR_ID, actors)).toBe(
      "Иванов Иван Иванович"
    )
    expect(formatRepairEstimateAuthor(ACTOR_ID, new Map())).toBe("Не указано")
    expect(
      formatRepairEstimateAuthor(
        ACTOR_ID,
        new Map([
          [
            ACTOR_ID,
            {
              ...actor,
              firstName: " ",
              lastName: null,
              middleName: null,
            },
          ],
        ])
      )
    ).toBe("Не указано")
    expect(
      formatRepairEstimateAuthor("operator", new Map([[ACTOR_ID, actor]]))
    ).toBe("Не указано")
    expect(
      repairEstimateAuthorIds([
        estimate(),
        estimate({ authorName: "operator" }),
      ])
    ).toEqual([ACTOR_ID])
  })

  it("combines cabin search with source, author, status and creation date filters", () => {
    const matching = estimate({
      id: "00000000-0000-4000-8000-000000000005",
      status: "COMPLETED",
      cabinNumber: "БЫТ-049",
      sourceParty: "ООО Юг",
      createdAt: "2026-07-22T10:00:00Z",
    })
    const excluded = estimate({
      id: "00000000-0000-4000-8000-000000000006",
      cabinNumber: "БЫТ-050",
      sourceParty: "ООО Север",
      createdAt: "2026-07-19T10:00:00Z",
    })

    expect(
      filterRepairEstimates(
        [matching, excluded],
        "049",
        {
          ...EMPTY_REPAIR_ESTIMATE_LIST_FILTERS,
          states: ["COMPLETED"],
          sourceParties: ["ООО Юг"],
          authorIds: [ACTOR_ID],
          dateFrom: "2026-07-21",
          dateTo: "2026-07-22",
        },
        new Map([[ACTOR_ID, actor]])
      )
    ).toEqual([matching])
  })

  it("finds an estimate by source party with one typo", () => {
    const matching = estimate({
      id: "00000000-0000-4000-8000-000000000005",
      sourceParty: "ООО Север",
    })
    const excluded = estimate({
      id: "00000000-0000-4000-8000-000000000006",
      sourceParty: "ООО Восток",
    })

    expect(
      filterRepairEstimates(
        [matching, excluded],
        "севир",
        EMPTY_REPAIR_ESTIMATE_LIST_FILTERS,
        new Map([[ACTOR_ID, actor]])
      )
    ).toEqual([matching])
  })

  it("finds an estimate by several author name tokens with one typo", () => {
    const otherActorId = "00000000-0000-4000-8000-000000000007"
    const matching = estimate()
    const excluded = estimate({
      id: "00000000-0000-4000-8000-000000000006",
      authorName: otherActorId,
    })
    const actors = new Map([
      [ACTOR_ID, actor],
      [
        otherActorId,
        {
          ...actor,
          subjectId: otherActorId,
          username: "petrov",
          firstName: "Пётр",
          lastName: "Петров",
          middleName: null,
          email: "petrov@example.test",
        },
      ],
    ])

    expect(
      filterRepairEstimates(
        [matching, excluded],
        "иван иванав",
        EMPTY_REPAIR_ESTIMATE_LIST_FILTERS,
        actors
      )
    ).toEqual([matching])
  })
})
