import type { CSSProperties } from "react"
import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import type { TaskRequirementState } from "./api/task-requirements-api"

export function requirementStyle(
  state: TaskRequirementState | undefined,
  palette?: KpiPalette | null
): CSSProperties | undefined {
  const color =
    state === "MISSING"
      ? (palette?.problemColor ?? "#FF3B30")
      : state === "COMPLETED" || state === "RESTORED"
        ? (palette?.completedColor ?? "#238636")
        : undefined
  return color
    ? { borderColor: color, backgroundColor: `${color}26` }
    : undefined
}

export function requirementStateLabel(state: TaskRequirementState | undefined) {
  switch (state) {
    case "MISSING":
      return "Не выполнено / нет материала"
    case "COMPLETED":
      return "Выполнено"
    case "RESTORED":
      return "Восстановлено"
    default:
      return null
  }
}
