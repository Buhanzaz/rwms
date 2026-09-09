import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowDown01Icon, ArrowUp01Icon } from "@hugeicons/core-free-icons"
import { useLocation, useNavigate } from "react-router-dom"

import { AnimatedThemeToggler } from "@/components/ui/animated-theme-toggler"
import { Button } from "@/components/ui/button"
import { resolveHeaderBreadcrumbs } from "@/components/site-header-breadcrumbs"
import { canShowWarehouseHtmlImport } from "@/components/site-header-html-import-access"
import { EquipmentItemCountBadge } from "@/features/equipment/equipment-item-count-badge"
import { TaskProblemReportsBell } from "@/features/task-board/task-problem-reports-bell"
import { useAuth } from "@/features/auth/use-auth"
import { HtmlImportHeaderAction } from "@/features/rental-items/html-import/html-import-workspace"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import {
  getRepairTask,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { useWarehouse } from "@/hooks/use-warehouse"

function getRentalItemId(pathname: string) {
  const match = /^\/warehouse\/([^/]+)$/.exec(pathname)

  return match?.[1] ?? null
}

export function SiteHeader() {
  const { pathname, search } = useLocation()
  const navigate = useNavigate()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse, warehouses } = useWarehouse()
  const searchParams = new URLSearchParams(search)
  const rentalItemId = getRentalItemId(pathname)
  const repairTaskId =
    pathname === "/acceptance" ? searchParams.get("acceptanceId") : null
  const rentalItemQuery = useQuery({
    queryKey: ["rental-item", rentalItemId],
    queryFn: () => getAssetRentalItem(accessToken, rentalItemId ?? ""),
    enabled: rentalItemId !== null && accessToken !== null,
  })
  const rentalItem = rentalItemQuery.data ?? null
  const repairTaskQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(
      selectedWarehouse?.id ?? "none",
      repairTaskId
    ),
    queryFn: () => getRepairTask(repairTaskId!, selectedWarehouse!.id),
    enabled: repairTaskId !== null && selectedWarehouse !== null,
  })
  const rentalItemWarehouse = rentalItem
    ? (warehouses.find(
        (warehouse) => warehouse.id === rentalItem.warehouseId
      ) ?? selectedWarehouse)
    : null
  const rentalItemBreadcrumb =
    rentalItem && rentalItemWarehouse
      ? {
          city: rentalItemWarehouse.city,
          number: rentalItem.number,
        }
      : null
  const breadcrumbs = resolveHeaderBreadcrumbs(
    pathname,
    search,
    rentalItemBreadcrumb,
    repairTaskQuery.data?.cabinNumber ?? null
  )
  const title = breadcrumbs[breadcrumbs.length - 1]?.title ?? "Панель WMS"
  const isWarehouseList = pathname === "/warehouse"
  const canImportWarehouseHtml = canShowWarehouseHtmlImport(
    pathname,
    currentUser,
    selectedWarehouse?.id ?? null
  )
  const warehouseToolbarCollapsed =
    new URLSearchParams(search).get("toolbar") === "collapsed"

  function toggleWarehouseToolbar() {
    const nextSearch = new URLSearchParams(search)

    if (warehouseToolbarCollapsed) {
      nextSearch.delete("toolbar")
    } else {
      nextSearch.set("toolbar", "collapsed")
    }

    const serializedSearch = nextSearch.toString()

    navigate(
      {
        pathname,
        search: serializedSearch ? `?${serializedSearch}` : "",
      },
      { replace: true }
    )
  }

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border px-4">
      <div className="min-w-0">
        <p className="truncate text-xs text-muted-foreground">Панель WMS</p>
        <div className="flex min-w-0 items-center gap-2">
          <p className="truncate text-sm font-medium">{title}</p>
          {pathname === "/equipment" ? <EquipmentItemCountBadge /> : null}
        </div>
      </div>

      <div className="ml-auto flex shrink-0 items-center gap-2">
        <TaskProblemReportsBell
          accessToken={accessToken}
          warehouseId={selectedWarehouse?.id ?? null}
          userId={currentUser?.id ?? null}
        />
        {canImportWarehouseHtml && selectedWarehouse ? (
          <HtmlImportHeaderAction
            warehouseId={selectedWarehouse.id}
            warehouseName={selectedWarehouse.name}
          />
        ) : null}

        {isWarehouseList ? (
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            className="md:hidden"
            aria-label={
              warehouseToolbarCollapsed
                ? "Развернуть параметры склада"
                : "Свернуть параметры склада"
            }
            aria-pressed={warehouseToolbarCollapsed}
            onClick={toggleWarehouseToolbar}
          >
            <HugeiconsIcon
              icon={warehouseToolbarCollapsed ? ArrowDown01Icon : ArrowUp01Icon}
              aria-hidden="true"
            />
          </Button>
        ) : null}
        <AnimatedThemeToggler />
      </div>
    </header>
  )
}
