import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowDown01Icon, ArrowUp01Icon } from "@hugeicons/core-free-icons"
import { Link, useLocation, useNavigate } from "react-router-dom"

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { SidebarTrigger } from "@/components/ui/sidebar"
import {
  resolveHeaderBreadcrumbs,
  type HeaderBreadcrumb,
} from "@/components/site-header-breadcrumbs"
import { canShowWarehouseHtmlImport } from "@/components/site-header-html-import-access"
import { EquipmentItemCountBadge } from "@/features/equipment/equipment-item-count-badge"
import { useAuth } from "@/features/auth/use-auth"
import { getOrder, ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import { HtmlImportHeaderAction } from "@/features/rental-items/html-import/html-import-workspace"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import {
  getRepairTask,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"

function getRentalItemId(pathname: string) {
  const match = /^\/warehouse\/([^/]+)$/.exec(pathname)

  return match?.[1] ?? null
}

function getOrderId(pathname: string) {
  const match = /^\/orders\/([0-9a-f-]{36})$/i.exec(pathname)

  return match?.[1] ?? null
}

export function SiteHeader() {
  const { pathname, search } = useLocation()
  const navigate = useNavigate()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse, warehouses } = useWarehouse()
  const searchParams = new URLSearchParams(search)
  const rentalItemId = getRentalItemId(pathname)
  const orderId = getOrderId(pathname)
  const repairTaskId =
    pathname === "/acceptance" ? searchParams.get("acceptanceId") : null
  const rentalItemQuery = useQuery({
    queryKey: ["rental-item", rentalItemId],
    queryFn: () => getAssetRentalItem(accessToken, rentalItemId ?? ""),
    enabled: rentalItemId !== null && accessToken !== null,
  })
  const orderQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "detail",
      currentUser?.id ?? "anonymous",
      orderId,
    ],
    queryFn: () => getOrder(accessToken!, orderId!),
    enabled: Boolean(accessToken && currentUser && orderId),
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
    repairTaskQuery.data?.cabinNumber ?? null,
    orderQuery.data?.number ?? null
  )
  const isNested = breadcrumbs.length > 1
  const useSlashSeparator = rentalItemId !== null
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
    <header className="flex h-(--header-height) shrink-0 items-center border-b">
      <div className="flex min-w-0 flex-1 items-center gap-2 px-4 lg:px-3">
        <SidebarTrigger
          className="shrink-0 hover:bg-muted hover:text-foreground active:bg-muted"
          aria-label="Открыть или свернуть меню"
        />
        <Separator
          orientation="vertical"
          className="h-4 data-vertical:self-center"
        />

        <Breadcrumb className="min-w-0 flex-1">
          <BreadcrumbList className="min-w-0 flex-nowrap gap-2 text-base">
            {breadcrumbs.map((breadcrumb, index) => {
              const isCurrent = index === breadcrumbs.length - 1

              return (
                <FragmentBreadcrumb
                  key={`${breadcrumb.title}-${index}`}
                  breadcrumb={breadcrumb}
                  isCurrent={isCurrent}
                  hideOnNarrow={isNested && !isCurrent}
                  showSeparator={!isCurrent}
                  useSlashSeparator={useSlashSeparator}
                  showEquipmentItemCount={
                    pathname === "/equipment" && isCurrent
                  }
                />
              )
            })}
          </BreadcrumbList>
        </Breadcrumb>

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
            className="shrink-0 md:hidden"
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
      </div>
    </header>
  )
}

function FragmentBreadcrumb({
  breadcrumb,
  isCurrent,
  hideOnNarrow,
  showSeparator,
  useSlashSeparator,
  showEquipmentItemCount,
}: {
  breadcrumb: HeaderBreadcrumb
  isCurrent: boolean
  hideOnNarrow: boolean
  showSeparator: boolean
  useSlashSeparator: boolean
  showEquipmentItemCount: boolean
}) {
  return (
    <>
      <BreadcrumbItem
        className={cn("min-w-0", hideOnNarrow && "max-[420px]:hidden")}
      >
        {isCurrent ? (
          <>
            <BreadcrumbPage className="truncate text-base font-medium">
              {breadcrumb.title}
            </BreadcrumbPage>
            {showEquipmentItemCount ? <EquipmentItemCountBadge /> : null}
          </>
        ) : breadcrumb.to ? (
          <BreadcrumbLink asChild className="truncate text-base">
            <Link to={breadcrumb.to}>{breadcrumb.title}</Link>
          </BreadcrumbLink>
        ) : (
          <span className="truncate text-base">{breadcrumb.title}</span>
        )}
      </BreadcrumbItem>

      {showSeparator ? (
        <BreadcrumbSeparator className="max-[420px]:hidden">
          {useSlashSeparator ? "/" : null}
        </BreadcrumbSeparator>
      ) : null}
    </>
  )
}
