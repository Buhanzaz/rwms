import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import type {
  TaskBoardEntryDto,
  TaskBoardSourceDto,
} from "@/features/task-board/model/task-board"
import {
  TaskBoardCard,
  TaskBoardCardPreview,
  type TaskBoardRepairComplexity,
} from "@/features/task-board/task-board-card"

const externalTaskId = "legacy-repair-reference"
const kpiPalette: KpiPalette = {
  version: 1,
  ranges: [
    { fromPercent: 0, toPercent: 35, color: "#DC2626" },
    { fromPercent: 35, toPercent: 70, color: "#EAB308" },
    { fromPercent: 70, toPercent: 100, color: "#16A34A" },
  ],
  overdueColor: "#7F1D1D",
}

const complexRepair: TaskBoardRepairComplexity = {
  type: "COMPLEX",
  name: "Тяжёлый ремонт",
  color: "#0E7490",
}

afterEach(cleanup)

function taskEntry(source: TaskBoardSourceDto | null): TaskBoardEntryDto {
  return {
    id: "entry-1",
    version: 1,
    warehouseId: "warehouse-1",
    queueKey: "repair",
    queueId: "queue-1",
    entryType: "REAL",
    routeIndex: 0,
    routeLength: 1,
    queuePosition: 0,
    taskId: "task-1",
    externalTaskId,
    source,
    taskVersion: 1,
    title: "Замена панели",
    unitNumber: "БЫТ-001",
    taskStatus: "ACTIVE",
    scheduledDate: "2026-07-18",
    priority: 3,
    pinned: false,
    status: "WAITING",
    taskText: null,
    plannedDurationMinutes: null,
    activeStartedAt: null,
    pausedAt: null,
    activeWorkSeconds: 0,
    timerSnapshot: null,
    assignments: [],
    detailsHref: null,
  }
}

function renderCard(
  source: TaskBoardSourceDto | null,
  {
    onDetails = vi.fn<(entry: TaskBoardEntryDto) => void>(),
    onEdit = vi.fn<(entry: TaskBoardEntryDto) => void>(),
    onTake = vi.fn<(entry: TaskBoardEntryDto) => void>(),
    onPin = vi.fn<(entry: TaskBoardEntryDto, pinned: boolean) => void>(),
    onShowFullRoute = vi.fn<(entry: TaskBoardEntryDto) => void>(),
    onToggleCollapsed = vi.fn<(entryId: string) => void>(),
    canEdit = true,
    mobile = false,
    collapsed = mobile,
    entryPatch = {},
    inDailyPlan = false,
    routeHighlighted = false,
    fullRouteSelected = false,
    palette = null,
    repairComplexity = null,
  }: {
    onDetails?: (entry: TaskBoardEntryDto) => void
    onEdit?: (entry: TaskBoardEntryDto) => void
    onTake?: (entry: TaskBoardEntryDto) => void
    onPin?: (entry: TaskBoardEntryDto, pinned: boolean) => void
    onShowFullRoute?: (entry: TaskBoardEntryDto) => void
    onToggleCollapsed?: (entryId: string) => void
    canEdit?: boolean
    mobile?: boolean
    collapsed?: boolean
    entryPatch?: Partial<TaskBoardEntryDto>
    inDailyPlan?: boolean
    routeHighlighted?: boolean
    fullRouteSelected?: boolean
    palette?: KpiPalette | null
    repairComplexity?: TaskBoardRepairComplexity | null
  } = {}
) {
  const entry = { ...taskEntry(source), ...entryPatch }
  render(
    <TaskBoardCard
      entry={entry}
      now={Date.parse("2026-07-18T10:00:00Z")}
      mobile={mobile}
      canEdit={canEdit}
      collapsed={collapsed}
      actionPending={false}
      inDailyPlan={inDailyPlan}
      routeHighlighted={routeHighlighted}
      fullRouteSelected={fullRouteSelected}
      onDetails={onDetails}
      onEdit={onEdit}
      onTake={onTake}
      onPause={vi.fn()}
      onResume={vi.fn()}
      onPin={onPin}
      onShowFullRoute={onShowFullRoute}
      onToggleCollapsed={onToggleCollapsed}
      palette={palette}
      repairComplexity={repairComplexity}
    />
  )
  return entry
}

function CollapsibleCard() {
  const [collapsed, setCollapsed] = useState(false)
  const entry = taskEntry({
    type: "MAINTENANCE_REPAIR",
    sourceId: "repair-1",
  })

  return (
    <TaskBoardCard
      entry={entry}
      now={Date.parse("2026-07-18T10:00:00Z")}
      mobile={false}
      canEdit
      collapsed={collapsed}
      actionPending={false}
      inDailyPlan={false}
      routeHighlighted={false}
      fullRouteSelected={false}
      onDetails={vi.fn()}
      onEdit={vi.fn()}
      onTake={vi.fn()}
      onPause={vi.fn()}
      onResume={vi.fn()}
      onPin={vi.fn()}
      onShowFullRoute={vi.fn()}
      onToggleCollapsed={() => setCollapsed((current) => !current)}
      repairComplexity={complexRepair}
    />
  )
}

describe("TaskBoardCard source details", () => {
  it("shows only the requested operational details in the expanded card", () => {
    const entry = renderCard({
      type: "MAINTENANCE_REPAIR",
      sourceId: "repair-1",
    })

    expect(screen.getByText(entry.unitNumber ?? "")).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: `Закрепить этап ${entry.unitNumber}`,
      })
    ).toBeTruthy()
    expect(screen.getByText("Ожидает")).toBeTruthy()
    expect(screen.getByText("Этап 1 из 1")).toBeTruthy()
    expect(screen.getByText("Приоритет 3")).toBeTruthy()
    expect(screen.getByText("Группа")).toBeTruthy()
    expect(screen.getByText("Работники")).toBeTruthy()
    expect(screen.getByText("Время")).toBeTruthy()
    expect(screen.queryByText("Дата")).toBeNull()
    expect(screen.queryByText("Текущий этап")).toBeNull()
    expect(screen.queryByText("Будущий этап")).toBeNull()
    expect(screen.queryByText("Позиция")).toBeNull()
  })

  it("does not expose the maintenance source code as the task description", () => {
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      {
        entryPatch: {
          title: "MAINTENANCE_REPAIR",
          taskText: "Замена панели",
        },
      }
    )

    expect(screen.getByText("Замена панели")).toBeTruthy()
    expect(screen.queryByText("MAINTENANCE_REPAIR")).toBeNull()
  })

  it("shows repair works but omits the materials list", () => {
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      {
        entryPatch: {
          taskText:
            "Ремонт крыши гидроизоляционной лентой. Материалы: Гидроизоляционная лента — 1 м.п.",
        },
      }
    )

    expect(
      screen.getByText("Ремонт крыши гидроизоляционной лентой.")
    ).toBeTruthy()
    expect(screen.queryByText(/Материалы:/)).toBeNull()
    expect(screen.queryByText(/Гидроизоляционная лента/)).toBeNull()
  })

  it("keeps the compact card limited to its status badges and full-width actions", () => {
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      { mobile: true }
    )

    const edit = screen.getByRole("button", { name: "Редактировать" })
    const details = screen.getByRole("button", { name: "Детали" })

    expect(screen.getByText("Ожидает")).toBeTruthy()
    expect(screen.getByText("Этап 1 из 1")).toBeTruthy()
    expect(screen.getByText("Приоритет 3")).toBeTruthy()
    expect(screen.queryByText("Замена панели")).toBeNull()
    expect(screen.queryByText("Группа")).toBeNull()
    expect(screen.queryByText("Работники")).toBeNull()
    expect(screen.queryByText("Время")).toBeNull()
    expect(screen.queryByText("Дата")).toBeNull()
    expect(edit.getAttribute("class")).toContain("w-full")
    expect(details.getAttribute("class")).toContain("w-full")
  })

  it("toggles the complete card with a short click on the drag handle", async () => {
    render(<CollapsibleCard />)

    const user = userEvent.setup()
    const collapse = screen.getByRole("button", {
      name: "Свернуть этап БЫТ-001",
    })

    expect(screen.getByText("Группа")).toBeTruthy()
    expect(screen.getByText("Замена панели")).toBeTruthy()
    expect(screen.getByText("Тяжёлый ремонт")).toBeTruthy()

    await user.click(collapse)

    expect(screen.getByRole("button", { name: "Развернуть этап БЫТ-001" }))
    expect(screen.queryByText("Группа")).toBeNull()
    expect(screen.queryByText("Работники")).toBeNull()
    expect(screen.queryByText("Время")).toBeNull()
    expect(screen.queryByText("Замена панели")).toBeNull()
    expect(screen.getByText("Ожидает")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Редактировать" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Детали" })).toBeTruthy()
    expect(screen.getByText("Тяжёлый ремонт")).toBeTruthy()

    await user.click(
      screen.getByRole("button", { name: "Развернуть этап БЫТ-001" })
    )

    expect(screen.getByText("Группа")).toBeTruthy()
    expect(screen.getByText("Замена панели")).toBeTruthy()
    expect(screen.getByText("Тяжёлый ремонт")).toBeTruthy()
  })

  it("uses the service-issued complexity name and color on an ordinary repair card", () => {
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      { repairComplexity: complexRepair }
    )

    const badge = screen.getByText("Тяжёлый ремонт")
    expect(badge.getAttribute("data-repair-complexity-type")).toBe("COMPLEX")
    expect(badge.style.backgroundColor).toBe("rgb(14, 116, 144)")
    expect(badge.style.borderColor).toBe("rgb(14, 116, 144)")
  })

  it("does not render a capital repair badge in the ordinary task board", () => {
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      {
        repairComplexity: {
          type: "CAPITAL",
          name: "Капитальный ремонт",
          color: "#7C3AED",
        },
      }
    )

    expect(screen.queryByText("Капитальный ремонт")).toBeNull()
  })

  it("keeps the pin next to the drag handle and shows the full repair text", () => {
    const entry = renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      {
        entryPatch: {
          taskText:
            "Замена панели, обработка стыков, окраска и проверка герметичности.",
        },
      }
    )

    const pin = screen.getByRole("button", {
      name: `Закрепить этап ${entry.unitNumber}`,
    })
    const dragHandle = screen.getByRole("button", {
      name: `Свернуть этап ${entry.unitNumber}`,
    })
    const description = screen.getByText(
      "Замена панели, обработка стыков, окраска и проверка герметичности."
    )

    expect(pin.closest('[data-slot="task-board-card-actions"]')).toBe(
      dragHandle.closest('[data-slot="task-board-card-actions"]')
    )
    expect(description.className).not.toContain("line-clamp")
  })

  it("uses the same collapsed or expanded contents for the drag preview", () => {
    const entry = taskEntry({
      type: "MAINTENANCE_REPAIR",
      sourceId: "repair-1",
    })
    const { rerender } = render(
      <TaskBoardCardPreview
        entry={entry}
        now={Date.parse("2026-07-18T10:00:00Z")}
        mobile={false}
        canEdit
        collapsed
        repairComplexity={complexRepair}
      />
    )

    expect(screen.queryByText("Группа")).toBeNull()
    expect(screen.queryByText("Замена панели")).toBeNull()
    expect(screen.getByText("Тяжёлый ремонт")).toBeTruthy()

    rerender(
      <TaskBoardCardPreview
        entry={entry}
        now={Date.parse("2026-07-18T10:00:00Z")}
        mobile={false}
        canEdit
        collapsed={false}
        repairComplexity={complexRepair}
      />
    )

    expect(screen.getByText("Группа")).toBeTruthy()
    expect(screen.getByText("Замена панели")).toBeTruthy()
    expect(screen.getByText("Тяжёлый ремонт")).toBeTruthy()
    expect(screen.queryByText("Дата")).toBeNull()
  })

  it("shows repair details without exposing the raw external task ID", async () => {
    const onDetails = vi.fn()
    const entry = renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair id/with space",
      },
      { onDetails }
    )

    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Детали" }))

    expect(onDetails).toHaveBeenCalledWith(entry)
    expect(screen.queryByText(externalTaskId)).toBeNull()
  })

  it("does not render details for an entry without a supported source", () => {
    renderCard(null)

    expect(screen.queryByRole("button", { name: "Детали" })).toBeNull()
  })

  it("offers repair editing only for an editable waiting maintenance stage", async () => {
    const onEdit = vi.fn()
    const entry = renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      { onEdit }
    )

    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Редактировать" }))

    expect(onEdit).toHaveBeenCalledWith(entry)
  })

  it("lets an editor take every server-admitted waiting card", async () => {
    const onTake = vi.fn()
    const entry = renderCard(null, { onTake })

    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Взять в работу" }))

    expect(onTake).toHaveBeenCalledWith(entry)
  })

  it("opens and marks the complete route from a real card", async () => {
    const onShowFullRoute = vi.fn()
    const entry = renderCard(null, {
      onShowFullRoute,
      inDailyPlan: true,
      routeHighlighted: true,
      fullRouteSelected: true,
      entryPatch: { routeLength: 4 },
    })

    const routeButton = screen.getByRole("button", { name: "Полный путь" })
    expect(routeButton.getAttribute("aria-pressed")).toBe("true")
    expect(screen.getByText("План на день")).toBeTruthy()
    expect(
      document.querySelector<HTMLElement>('[data-slot="card"]')?.dataset
        .routeHighlighted
    ).toBe("true")

    await userEvent.setup().click(routeButton)

    expect(onShowFullRoute).toHaveBeenCalledWith(entry)
  })

  it("shows a future shadow but keeps every mutating action unavailable", () => {
    const onPin = vi.fn()
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      {
        onPin,
        entryPatch: {
          entryType: "SHADOW",
          routeIndex: 2,
          routeLength: 4,
        },
      }
    )

    expect(screen.getByText("После предыдущего этапа")).toBeTruthy()
    expect(screen.getByText("Этап 3 из 4")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Полный путь" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Взять в работу" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Редактировать" })).toBeNull()
    const pin = screen.getByRole("button", {
      name: "Закрепить этап БЫТ-001",
    })
    expect((pin as HTMLButtonElement).disabled).toBe(true)
    expect(onPin).not.toHaveBeenCalled()
  })

  it("hides repair editing without EDIT access", () => {
    renderCard(
      {
        type: "MAINTENANCE_REPAIR",
        sourceId: "repair-1",
      },
      { canEdit: false }
    )

    expect(screen.queryByRole("button", { name: "Редактировать" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Взять в работу" })).toBeNull()
    expect(screen.getByRole("button", { name: "Детали" })).toBeTruthy()
  })
})

describe("TaskBoardCard KPI timer presentation", () => {
  it("tints the whole card with the configured segment and renders the server timer", () => {
    renderCard(null, {
      palette: kpiPalette,
      entryPatch: {
        status: "IN_PROGRESS",
        plannedDurationMinutes: 20,
        timerSnapshot: {
          countedActiveSeconds: 600,
          remainingSeconds: 600,
          remainingPercent: 50,
          timerState: "BREAK",
          nextTransitionAt: "2026-07-18T10:15:00Z",
          serverTime: "2026-07-18T09:55:00Z",
        },
      },
    })

    const card = document.querySelector<HTMLElement>('[data-slot="card"]')!
    expect(card.dataset.kpiColor).toBe("#EAB308")
    expect(card.style.borderColor).toBe("rgb(234, 179, 8)")
    expect(card.style.backgroundColor).toBe("rgba(234, 179, 8, 0.12)")
    expect(screen.getByText("Осталось 50%")).toBeTruthy()
    expect(screen.getByText("Перерыв · 10:00")).toBeTruthy()
    expect(screen.getByText("10:00")).toBeTruthy()
  })

  it("uses the separate overdue color instead of the zero-percent segment", () => {
    renderCard(null, {
      palette: kpiPalette,
      entryPatch: {
        status: "IN_PROGRESS",
        plannedDurationMinutes: 20,
        timerSnapshot: {
          countedActiveSeconds: 1_230,
          remainingSeconds: -30,
          remainingPercent: -2.5,
          timerState: "WORKING",
          nextTransitionAt: null,
          serverTime: "2026-07-18T10:00:00Z",
        },
      },
    })

    const card = document.querySelector<HTMLElement>('[data-slot="card"]')!
    expect(card.dataset.kpiColor).toBe("#7F1D1D")
    expect(screen.getByText("Просрочено на 00:30")).toBeTruthy()
  })

  it("keeps the existing neutral card when no active palette is available", () => {
    renderCard(null, {
      entryPatch: {
        status: "IN_PROGRESS",
        timerSnapshot: {
          countedActiveSeconds: 300,
          remainingSeconds: 900,
          remainingPercent: 75,
          timerState: "WORKING",
          nextTransitionAt: null,
          serverTime: "2026-07-18T10:00:00Z",
        },
      },
    })

    const card = document.querySelector<HTMLElement>('[data-slot="card"]')!
    expect(card.dataset.kpiColor).toBeUndefined()
    expect(card.style.borderColor).toBe("")
    expect(card.className).toContain("border-primary")
  })
})
