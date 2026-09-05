import { PlanningSettingsPage } from "@/features/settings/planner/planning-settings-page"
import { KpiPaletteSettingsPage } from "@/features/settings/kpi/kpi-palette-settings-page"
import { KpiSettingsPage } from "@/features/settings/kpi/kpi-settings-page"
import { TaskBoardSettingsPage } from "@/features/settings/task-board/task-board-settings-page"

export function AdminKpiSettingsPage() {
  return <KpiPaletteSettingsPage />
}

export function AdminClassesSettingsPage() {
  return <TaskBoardSettingsPage section="classes" />
}

export function AdminWorkScheduleSettingsPage() {
  return <KpiSettingsPage presentation="admin" />
}

export function AdminLogisticsSettingsPage() {
  return <PlanningSettingsPage />
}
