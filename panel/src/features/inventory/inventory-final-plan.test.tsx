import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import { InventoryFinalPlanEditor } from "@/features/inventory/inventory-final-plan"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"
import type { InventoryFinalPlan } from "@/features/inventory/model/inventory-service"

const FIRST_FINDING_ID = "00000000-0000-4000-8000-000000000301"
const SECOND_FINDING_ID = "00000000-0000-4000-8000-000000000302"
const EXISTING_ESTIMATE_ID = "00000000-0000-4000-8000-000000000303"
const EXISTING_REPAIR_ID = "00000000-0000-4000-8000-000000000304"

const plan: InventoryFinalPlan = {
  inventoryId: "00000000-0000-4000-8000-000000000300",
  sessionRevision: 11,
  finalPlanVersion: 4,
  finalPlanSha256: "a".repeat(64),
  planningSettingsRevision: 2,
  state: "DRAFT",
  movementScheduleMode: "MANUAL",
  repairScheduleMode: "MANUAL",
  entries: [
    {
      findingId: FIRST_FINDING_ID,
      findingRevision: 7,
      planFingerprintSha256: "b".repeat(64),
      targetKind: "REPAIR",
      hasWork: true,
      order: 0,
      priority: 3,
      movementToRepair: true,
      movementScheduledDate: "2026-08-12",
      repairScheduledDate: "2026-08-13",
      collisionCandidates: [
        {
          targetKind: "ESTIMATE",
          targetId: EXISTING_ESTIMATE_ID,
          estimateId: EXISTING_ESTIMATE_ID,
          repairId: null,
          version: 5,
          state: "DRAFT",
          started: false,
          active: true,
          priority: 4,
          sourceParty: "Возврат из аренды",
          planFingerprintSha256: "c".repeat(64),
          planSummary: {
            workLineCount: 2,
            materialLineCount: 1,
            grandTotalMinor: 12500,
          },
          forceCapitalRepair: true,
        },
      ],
      reconciliationDecision: null,
      forceCapitalRepair: true,
      dispositionKind: "LOCAL",
      dispositionDetails: { formerRental: null },
    },
    {
      findingId: SECOND_FINDING_ID,
      findingRevision: 3,
      planFingerprintSha256: null,
      targetKind: null,
      hasWork: false,
      order: 1,
      priority: null,
      movementToRepair: false,
      movementScheduledDate: null,
      repairScheduledDate: null,
      collisionCandidates: [],
      reconciliationDecision: null,
      forceCapitalRepair: false,
      dispositionKind: "LOCAL",
      dispositionDetails: { formerRental: null },
    },
  ],
}

const findings = [
  {
    id: FIRST_FINDING_ID,
    cabinNumber: "БЫТ-001",
    canonicalNumber: "БЫТ-001",
    currentSnapshot: { status: "AFTER_RENT" },
  },
  {
    id: SECOND_FINDING_ID,
    cabinNumber: "БЫТ-002",
    canonicalNumber: "БЫТ-002",
    currentSnapshot: { status: "FREE" },
  },
] as InventoryFindingDto[]

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterAll(() => {
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      delete (HTMLElement.prototype as unknown as Record<string, unknown>)[name]
    }
  }
})

afterEach(() => cleanup())

describe("inventory final plan", () => {
  it("renders a preserved departed inspection as history with no operational controls", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    const preservedEntry: InventoryFinalPlan["entries"][number] = {
      ...plan.entries[0],
      planFingerprintSha256: "b".repeat(64),
      targetKind: null,
      hasWork: false,
      priority: null,
      movementToRepair: false,
      movementScheduledDate: null,
      repairScheduledDate: null,
      collisionCandidates: [],
      reconciliationDecision: null,
      forceCapitalRepair: false,
      dispositionKind: "PRESERVE",
      dispositionDetails: {},
    }
    render(
      <InventoryFinalPlanEditor
        plan={{ ...plan, entries: [preservedEntry] }}
        findings={[
          {
            ...findings[0],
            currentSnapshot: { status: "RENTED" },
            preserveOperationalState: true,
          } as InventoryFindingDto,
        ]}
        pending={false}
        error={null}
        onDirtyChange={vi.fn()}
        onOpenFinding={vi.fn()}
        onOpenCandidate={vi.fn()}
        onSave={onSave}
      />
    )

    const card = screen.getByLabelText("Позиция 1, бытовка БЫТ-001")
    expect(
      within(card).getByText(
        "Осмотр сохранится в истории; текущее состояние бытовки и прежние работы не изменяются."
      )
    ).toBeTruthy()
    expect(within(card).getByText("Без изменения состояния")).toBeTruthy()
    expect(within(card).getByText("Аренда")).toBeTruthy()
    expect(
      within(card).queryByText("Из инвентаризации: новый ремонт")
    ).toBeNull()
    expect(within(card).queryByRole("combobox")).toBeNull()
    expect(within(card).queryByRole("checkbox")).toBeNull()
    expect(
      (
        within(card).getByRole("button", {
          name: "Поднять бытовку БЫТ-001",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)

    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        entries: [
          expect.objectContaining({
            findingId: FIRST_FINDING_ID,
            priority: null,
            movementToRepair: false,
            movementScheduledDate: null,
            repairScheduledDate: null,
            reconciliationDecision: null,
          }),
        ],
      })
    )
  })

  it("shows the full list and submits ordering, dates and an explicit replacement", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    const onDirtyChange = vi.fn()
    render(
      <InventoryFinalPlanEditor
        plan={plan}
        findings={findings}
        pending={false}
        error={null}
        onDirtyChange={onDirtyChange}
        onOpenFinding={vi.fn()}
        onOpenCandidate={vi.fn()}
        onSave={onSave}
      />
    )

    expect(screen.getByText("Из инвентаризации: новый ремонт")).toBeTruthy()
    expect(
      screen.getByText(
        "Осмотр без замечаний — запись остаётся только в истории инвентаризации."
      )
    ).toBeTruthy()

    const firstCard = screen.getByLabelText("Позиция 1, бытовка БЫТ-001")
    const forceCapitalRepair = within(firstCard).getByRole("checkbox", {
      name: "Направить на капитальный ремонт",
    })
    expect(forceCapitalRepair.getAttribute("aria-checked")).toBe("true")
    expect(forceCapitalRepair.hasAttribute("disabled")).toBe(true)
    expect(within(firstCard).getByText("Капитальный ремонт")).toBeTruthy()
    await user.click(
      within(firstCard).getByRole("combobox", { name: "Существующая запись" })
    )
    await user.click(
      screen.getByRole("option", { name: /Смета · DRAFT · версия 5/ })
    )
    await user.click(within(firstCard).getByRole("radio", { name: "Заменить" }))
    await user.click(
      within(firstCard).getByRole("button", {
        name: "Опустить бытовку БЫТ-001",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )

    expect(onDirtyChange).toHaveBeenCalledWith(true)
    expect(onSave).toHaveBeenCalledWith({
      expectedSessionRevision: 11,
      expectedFinalPlanVersion: 4,
      movementScheduleMode: "MANUAL",
      repairScheduleMode: "MANUAL",
      entries: [
        {
          findingId: SECOND_FINDING_ID,
          expectedFindingRevision: 3,
          order: 0,
          priority: null,
          movementToRepair: false,
          movementScheduledDate: null,
          repairScheduledDate: null,
          reconciliationDecision: null,
        },
        {
          findingId: FIRST_FINDING_ID,
          expectedFindingRevision: 7,
          order: 1,
          priority: 3,
          movementToRepair: true,
          movementScheduledDate: "2026-08-12",
          repairScheduledDate: "2026-08-13",
          reconciliationDecision: {
            strategy: "REPLACE",
            selectedTargetKind: "ESTIMATE",
            selectedTargetId: EXISTING_ESTIMATE_ID,
          },
        },
      ],
    })
  })

  it("requires an automatic remainder merge when the existing work is already started", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    render(
      <InventoryFinalPlanEditor
        plan={{
          ...plan,
          entries: [
            {
              ...plan.entries[0],
              collisionCandidates: [
                {
                  ...plan.entries[0].collisionCandidates[0],
                  targetKind: "REPAIR",
                  targetId: EXISTING_REPAIR_ID,
                  estimateId: null,
                  repairId: EXISTING_REPAIR_ID,
                  state: "IN_PROGRESS",
                  started: true,
                },
              ],
            },
          ],
        }}
        findings={findings}
        pending={false}
        error={null}
        onDirtyChange={vi.fn()}
        onOpenFinding={vi.fn()}
        onOpenCandidate={vi.fn()}
        onSave={onSave}
      />
    )

    const firstCard = screen.getByLabelText("Позиция 1, бытовка БЫТ-001")
    await user.click(
      within(firstCard).getByRole("combobox", { name: "Существующая запись" })
    )
    await user.click(
      screen.getByRole("option", {
        name: /Ремонт · IN_PROGRESS · версия 5/,
      })
    )

    expect(
      (
        within(firstCard).getByRole("radio", {
          name: "Заменить",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByText(/Начатую работу нельзя заменить/)).toBeTruthy()

    await user.click(
      within(firstCard).getByRole("radio", { name: "Слить остаток" })
    )
    expect(screen.getByText("Текущая работа сохранится")).toBeTruthy()
    expect(
      screen.queryByRole("checkbox", {
        name: /Я открыл старую запись и вручную включил нужные работы/,
      })
    ).toBeNull()
    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        entries: [
          expect.objectContaining({
            findingId: FIRST_FINDING_ID,
            reconciliationDecision: {
              strategy: "MERGE",
              selectedTargetKind: "REPAIR",
              selectedTargetId: EXISTING_REPAIR_ID,
            },
          }),
        ],
      })
    )
  })

  it("requires proof that a manual merge already contains the old work", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    const onOpenCandidate = vi.fn()
    render(
      <InventoryFinalPlanEditor
        plan={plan}
        findings={findings}
        pending={false}
        error={null}
        onDirtyChange={vi.fn()}
        onOpenFinding={vi.fn()}
        onOpenCandidate={onOpenCandidate}
        onSave={onSave}
      />
    )

    const firstCard = screen.getByLabelText("Позиция 1, бытовка БЫТ-001")
    await user.click(
      within(firstCard).getByRole("button", { name: "Открыть смету" })
    )
    expect(onOpenCandidate).toHaveBeenCalledWith(
      plan.entries[0].collisionCandidates[0]
    )

    await user.click(
      within(firstCard).getByRole("combobox", { name: "Существующая запись" })
    )
    await user.click(
      screen.getByRole("option", { name: /Смета · DRAFT · версия 5/ })
    )
    await user.click(
      within(firstCard).getByRole("radio", { name: "Слить вручную" })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )

    expect(onSave).not.toHaveBeenCalled()
    expect(
      screen.getByText(/Подтвердите, что итоговый состав вручную объединён/)
    ).toBeTruthy()

    await user.click(
      within(firstCard).getByRole("checkbox", {
        name: /Я открыл старую запись и вручную включил нужные работы/,
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        entries: expect.arrayContaining([
          expect.objectContaining({
            findingId: FIRST_FINDING_ID,
            reconciliationDecision: {
              strategy: "MERGE",
              selectedTargetKind: "ESTIMATE",
              selectedTargetId: EXISTING_ESTIMATE_ID,
            },
          }),
        ]),
      })
    )
  })

  it("blocks an ambiguous choice between multiple active maintenance targets", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    render(
      <InventoryFinalPlanEditor
        plan={{
          ...plan,
          entries: [
            {
              ...plan.entries[0],
              collisionCandidates: [
                plan.entries[0].collisionCandidates[0],
                {
                  ...plan.entries[0].collisionCandidates[0],
                  targetKind: "REPAIR",
                  targetId: EXISTING_REPAIR_ID,
                  estimateId: null,
                  repairId: EXISTING_REPAIR_ID,
                  state: "QUEUED",
                },
              ],
            },
          ],
        }}
        findings={findings}
        pending={false}
        error={null}
        onDirtyChange={vi.fn()}
        onOpenFinding={vi.fn()}
        onOpenCandidate={vi.fn()}
        onSave={onSave}
      />
    )

    expect(
      screen.getByText(/Найдено несколько активных смет или ремонтов/)
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Сохранить итоговый план" })
    )
    expect(onSave).not.toHaveBeenCalled()
  })
})
