import { useMemo, useState, type ReactNode } from "react"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowRight01Icon,
  CanvasIcon,
  HammerIcon,
  PackageIcon,
  Route01Icon,
  Sofa01Icon,
  TaskDone01Icon,
  ToolsIcon,
  WorkIcon,
} from "@hugeicons/core-free-icons"

import { getRepairEstimateCatalogCanvasSettings } from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api"
import { getRepairEstimateFurnitureCatalogSettings } from "@/features/settings/estimates-repairs/api/repair-estimate-furniture-catalog-settings-api"
import { getRepairEstimateMaterialCatalogSettings } from "@/features/settings/estimates-repairs/api/repair-estimate-material-catalog-settings-api"
import { getRepairEstimateWorkCatalogSettings } from "@/features/settings/estimates-repairs/api/repair-estimate-work-catalog-settings-api"
import { getRepairSettingsActions } from "@/features/settings/estimates-repairs/api/repair-settings-api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"
import type {
  EstimateCatalogSettingsActionDto,
  RepairSettingsActionDto,
} from "@/features/settings/estimates-repairs/model/estimate-repair-settings"

type SettingsAction =
  | (EstimateCatalogSettingsActionDto & { group: "estimate" })
  | (RepairSettingsActionDto & { group: "repair" })

type SettingsActionButtonProps = {
  action: SettingsAction
  active: boolean
  onClick: (action: SettingsAction) => void
}

function getEstimateActionIcon(id: EstimateCatalogSettingsActionDto["id"]) {
  switch (id) {
    case "repair-estimate-catalog-canvas":
      return CanvasIcon
    case "repair-estimate-catalog-works":
      return WorkIcon
    case "repair-estimate-catalog-materials":
      return PackageIcon
    case "repair-estimate-catalog-furniture":
      return Sofa01Icon
  }
}

function getRepairActionIcon(id: RepairSettingsActionDto["id"]) {
  switch (id) {
    case "repair-stage-settings":
      return ToolsIcon
    case "repair-route-settings":
      return Route01Icon
    case "repair-acceptance-settings":
      return TaskDone01Icon
    case "repair-rework-settings":
      return HammerIcon
  }
}

function SettingsActionButton({
  action,
  active,
  onClick,
}: SettingsActionButtonProps) {
  const Icon =
    action.group === "estimate"
      ? getEstimateActionIcon(action.id)
      : getRepairActionIcon(action.id)

  return (
    <Button
      type="button"
      variant={active ? "secondary" : "outline"}
      className="h-auto min-h-14 w-full justify-between gap-3 px-3 py-3 text-left"
      onClick={() => onClick(action)}
    >
      <span className="flex min-w-0 items-center gap-3">
        <HugeiconsIcon icon={Icon} data-icon="inline-start" />
        <span className="min-w-0 truncate">{action.title}</span>
      </span>
      <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-end" />
    </Button>
  )
}

function SectionSkeleton() {
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {Array.from({ length: 4 }).map((_, index) => (
        <Skeleton key={index} className="h-14 rounded-md" />
      ))}
    </div>
  )
}

function SettingsSection({
  title,
  count,
  children,
}: {
  title: string
  count: number
  children: ReactNode
}) {
  return (
    <section className="flex min-h-0 flex-col overflow-hidden rounded-lg border bg-card">
      <div className="flex shrink-0 items-center justify-between gap-3 border-b px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <h2 className="truncate text-lg font-semibold">{title}</h2>
          <Badge variant="secondary">{count} шт.</Badge>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-4">{children}</div>
    </section>
  )
}

function SelectedActionPanel({ action }: { action: SettingsAction | null }) {
  if (action === null) {
    return null
  }

  return (
    <div className="rounded-lg border bg-muted/20 p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{action.title}</span>
        {action.group === "repair" ? (
          <Badge variant="secondary">Тест</Badge>
        ) : (
          <Badge variant="secondary">{action.legacyViewId}</Badge>
        )}
      </div>
    </div>
  )
}

export function EstimatesRepairsSettingsPage() {
  const [selectedAction, setSelectedAction] = useState<SettingsAction | null>(
    null
  )

  const catalogCanvasQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-canvas"],
    queryFn: getRepairEstimateCatalogCanvasSettings,
  })

  const workCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-works"],
    queryFn: getRepairEstimateWorkCatalogSettings,
  })

  const materialCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-materials"],
    queryFn: getRepairEstimateMaterialCatalogSettings,
  })

  const furnitureCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-furniture"],
    queryFn: getRepairEstimateFurnitureCatalogSettings,
  })

  const repairSettingsQuery = useQuery({
    queryKey: ["repair-settings", "actions"],
    queryFn: getRepairSettingsActions,
  })

  const estimateActions = useMemo(() => {
    return [
      catalogCanvasQuery.data,
      workCatalogQuery.data,
      materialCatalogQuery.data,
      furnitureCatalogQuery.data,
    ]
      .filter((action): action is EstimateCatalogSettingsActionDto =>
        Boolean(action)
      )
      .sort((left, right) => left.order - right.order)
      .map((action) => ({ ...action, group: "estimate" as const }))
  }, [
    catalogCanvasQuery.data,
    furnitureCatalogQuery.data,
    materialCatalogQuery.data,
    workCatalogQuery.data,
  ])

  const repairActions = useMemo(() => {
    return (repairSettingsQuery.data ?? [])
      .slice()
      .sort((left, right) => left.order - right.order)
      .map((action) => ({ ...action, group: "repair" as const }))
  }, [repairSettingsQuery.data])

  const estimateLoading =
    catalogCanvasQuery.isLoading ||
    workCatalogQuery.isLoading ||
    materialCatalogQuery.isLoading ||
    furnitureCatalogQuery.isLoading

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <SelectedActionPanel action={selectedAction} />

      <div
        className={cn(
          "grid min-h-0 flex-1 grid-rows-2 gap-4",
          selectedAction !== null && "min-h-[calc(100%-4rem)]"
        )}
      >
        <SettingsSection title="Настройка смет" count={estimateActions.length}>
          {estimateLoading ? (
            <SectionSkeleton />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {estimateActions.map((action) => (
                <SettingsActionButton
                  key={action.id}
                  action={action}
                  active={selectedAction?.id === action.id}
                  onClick={setSelectedAction}
                />
              ))}
            </div>
          )}
        </SettingsSection>

        <SettingsSection title="Настройка ремонтов" count={repairActions.length}>
          {repairSettingsQuery.isLoading ? (
            <SectionSkeleton />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {repairActions.map((action) => (
                <SettingsActionButton
                  key={action.id}
                  action={action}
                  active={selectedAction?.id === action.id}
                  onClick={setSelectedAction}
                />
              ))}
            </div>
          )}
        </SettingsSection>
      </div>
    </div>
  )
}
