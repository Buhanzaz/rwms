import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { QueueOrderSettings } from "@/features/settings/task-board/queue-order-settings"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"

const queue: WorkQueueDto = {
  id: "00000000-0000-4000-8000-000000000002",
  version: 1,
  warehouseId: "00000000-0000-4000-8000-000000000001",
  definitionId: "00000000-0000-4000-8000-000000000010",
  definitionVersion: 1,
  name: "Ремонт",
  description: null,
  type: "REPAIR",
  purpose: "GENERAL",
  sortOrder: 0,
  active: true,
  hidden: false,
  collapsed: false,
  holdingPeriodMinutes: null,
  notificationThreshold: null,
  notifyWhenThresholdReached: false,
  resultPhotoMinCount: 1,
  bindings: [],
}

afterEach(cleanup)

describe("QueueOrderSettings", () => {
  it("renders selected warehouse ordering controls without a card", () => {
    const { container } = render(
      <QueueOrderSettings queues={[queue]} pending={false} onSave={vi.fn()} />
    )

    expect(
      screen.getByRole("heading", { name: "Порядок очередей" })
    ).toBeTruthy()
    expect(screen.getByText("Ремонт")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Сохранить порядок" })
    ).toBeTruthy()
    expect(container.querySelector('[data-slot="card"]')).toBeNull()
  })

  it("saves holding queues after the editable queue order", () => {
    const onSave = vi.fn(async () => undefined)
    const holding: WorkQueueDto = {
      ...queue,
      id: "00000000-0000-4000-8000-000000000003",
      definitionId: "00000000-0000-4000-8000-000000000011",
      name: "Ожидание",
      type: "HOLDING",
      sortOrder: 10,
    }
    render(
      <QueueOrderSettings
        queues={[queue, holding]}
        pending={false}
        onSave={onSave}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Сохранить порядок" }))

    expect(onSave).toHaveBeenCalledWith([queue, holding])
  })
})
