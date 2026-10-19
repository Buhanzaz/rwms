import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"
import { Badge } from "./ui/badge"
import { RepairTaskStatusBadge } from "@/features/repair-tasks/repair-task-status-badge"
import { repairSubtaskStatusVariant } from "@/features/repair-tasks/repair-task-status-labels"
import { logisticsStatusVariant } from "@/features/logistics/logistics-status-variant"
import { OrderStatusBadge } from "@/features/orders/components/order-status-badge"
import { AcceptanceStatusBadge } from "@/features/acceptance/acceptance-presentation"

afterEach(cleanup)

describe("semantic status presentation", () => {
  it.each(["success", "warning", "progress", "info"] as const)(
    "uses theme-aware semantic %s colors without the blue action background",
    (variant) => {
      render(<Badge variant={variant}>Статус</Badge>)
      const badge = screen.getByText("Статус")
      expect(badge.className).toContain(`bg-status-${variant}`)
      expect(badge.className).toContain("text-foreground")
      expect(badge.className).not.toContain("bg-primary")
    }
  )
  it("distinguishes completion, queued work, ongoing repair and movement", () => {
    render(
      <>
        <RepairTaskStatusBadge status="COMPLETED" />
        <RepairTaskStatusBadge status="QUEUED" />
        <RepairTaskStatusBadge status="IN_PROGRESS" />
        <RepairTaskStatusBadge status="QUEUED" awaitingMovement />
      </>
    )
    expect(screen.getByText("Завершено").dataset.variant).toBe("success")
    expect(screen.getByText("В очереди").dataset.variant).toBe("warning")
    expect(screen.getByText("В работе").dataset.variant).toBe("progress")
    expect(screen.getByText("Ожидает перемещения").dataset.variant).toBe("info")
    expect(repairSubtaskStatusVariant.DONE).toBe("success")
    expect(repairSubtaskStatusVariant.PAUSED).toBe("warning")
  })
  it("does not present cancelled documents or an estimate request as completed work", () => {
    expect(logisticsStatusVariant("CANCELLED")).toBe("muted")
    expect(logisticsStatusVariant("ESTIMATE_REQUESTED")).toBe("progress")
    expect(logisticsStatusVariant("ACCEPTED")).toBe("success")
    expect(logisticsStatusVariant("SHIPPED")).toBe("success")
    expect(logisticsStatusVariant("IN_TRANSIT")).toBe("info")
    expect(logisticsStatusVariant("INSPECTION_REQUIRED")).toBe("warning")
    expect(logisticsStatusVariant("CONFLICT")).toBe("destructive")
    expect(logisticsStatusVariant("RECONCILIATION_REQUIRED")).toBe(
      "destructive"
    )
  })
  it("keeps successful orders green, cancellations neutral and rework orange", () => {
    render(
      <>
        <OrderStatusBadge status="FULFILLED" />
        <OrderStatusBadge status="CANCELLED" />
        <AcceptanceStatusBadge status="IN_REWORK" />
      </>
    )
    expect(screen.getByText("Исполнен").dataset.variant).toBe("success")
    expect(screen.getByText("Отменён").dataset.variant).toBe("muted")
    expect(screen.getByText("На доработке").className).toContain(
      "bg-status-progress"
    )
  })
})
