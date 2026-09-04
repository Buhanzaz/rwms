import { Queue01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Card, CardContent } from "@/components/ui/card"
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
  return (
    <Card size="sm">
      <CardContent className="flex min-h-52 flex-col items-center justify-center gap-3 text-center">
        <span className="grid size-10 place-items-center rounded-lg bg-primary/10 text-primary">
          <HugeiconsIcon icon={Queue01Icon} aria-hidden="true" />
        </span>
        <div className="space-y-1">
          <p className="font-medium">Отдельных настроек логистики нет</p>
          <p className="max-w-xl text-sm text-muted-foreground">
            Водители, транспорт, прицепы и ремонтные места находятся в
            настройках объекта. Связи классов и календарь — в общих настройках.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
