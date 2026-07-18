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
import { EquipmentItemCountBadge } from "@/features/equipment/equipment-item-count-badge"
import { useAuth } from "@/features/auth/use-auth"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import {
  getRepairTask,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"

type HeaderBreadcrumb = {
  title: string
  to?: string
}

type RentalItemHeaderBreadcrumb = {
  city: string
  number: string
}

const routeTitles = [
  { path: "/settings/estimates-repairs", title: "Настройка смет и ремонтов" },
  { path: "/settings/task-board", title: "Настройка доски задач" },
  { path: "/warehouse", title: "Склад" },
  { path: "/equipment", title: "Доп. оборудование" },
  { path: "/inventory", title: "Инвентаризация" },
  { path: "/logistics/returns", title: "Возврат из аренды" },
  { path: "/logistics/shipments", title: "Отгрузка в аренду" },
  { path: "/logistics/transfers", title: "Перемещения" },
  { path: "/estimates", title: "Сметы" },
  { path: "/repairs", title: "Ремонты" },
  { path: "/task-board", title: "Доска задач" },
  { path: "/acceptance", title: "Приёмка и доработки" },
  { path: "/write-offs", title: "Списание" },
  { path: "/settings", title: "Настройки" },
  { path: "/kpi", title: "KPI" },
] as const

function resolveHeaderBreadcrumbs(
  pathname: string,
  search: string,
  rentalItemBreadcrumb: RentalItemHeaderBreadcrumb | null,
  repairCabinNumber: string | null
): HeaderBreadcrumb[] {
  const searchParams = new URLSearchParams(search)

  if (pathname.startsWith("/warehouse/")) {
    if (rentalItemBreadcrumb === null) {
      return [{ title: "Склад", to: "/warehouse" }]
    }

    return [
      { title: "Склад", to: "/warehouse" },
      { title: rentalItemBreadcrumb.city, to: "/warehouse" },
      { title: rentalItemBreadcrumb.number },
    ]
  }

  if (pathname === "/settings/estimates-repairs") {
    return [
      { title: "Настройки", to: "/settings" },
      { title: "Настройка смет и ремонтов" },
    ]
  }

  if (pathname === "/logistics/returns") {
    return [{ title: "Логистика" }, { title: "Возврат из аренды" }]
  }

  if (pathname === "/logistics/shipments") {
    return [{ title: "Логистика" }, { title: "Отгрузка в аренду" }]
  }

  if (pathname === "/logistics/transfers") {
    return [{ title: "Логистика" }, { title: "Перемещения" }]
  }

  if (pathname === "/settings/task-board") {
    return [
      { title: "Настройки", to: "/settings" },
      { title: "Настройка доски задач" },
    ]
  }

  const inventoryHistoryMatch = /^\/inventory\/history\/([^/]+)$/.exec(pathname)
  if (inventoryHistoryMatch) {
    return [
      { title: "Инвентаризация", to: "/inventory" },
      { title: "История", to: "/inventory/history" },
      { title: "Результат" },
    ]
  }

  if (pathname === "/inventory/history") {
    return [{ title: "Инвентаризация", to: "/inventory" }, { title: "История" }]
  }

  const inventoryFinishMatch = /^\/inventory\/([^/]+)\/finish$/.exec(pathname)
  if (inventoryFinishMatch) {
    return [
      { title: "Инвентаризация", to: "/inventory" },
      { title: "Сессия", to: `/inventory/${inventoryFinishMatch[1]}` },
      { title: "Сверка" },
    ]
  }

  if (/^\/inventory\/[^/]+$/.test(pathname)) {
    return [{ title: "Инвентаризация", to: "/inventory" }, { title: "Сессия" }]
  }

  if (pathname === "/acceptance" && searchParams.has("acceptanceId")) {
    return [
      { title: "Приёмка и доработки", to: "/acceptance" },
      { title: repairCabinNumber ?? "Бытовка" },
    ]
  }

  if (pathname === "/write-offs" && searchParams.has("writeOffId")) {
    return [
      { title: "Списание" },
      { title: "Склад", to: "/write-offs" },
      { title: repairCabinNumber ?? "Бытовка" },
    ]
  }

  if (pathname === "/write-offs") {
    return [{ title: "Списание", to: "/write-offs" }, { title: "Склад" }]
  }

  if (pathname === "/write-offs/equipment") {
    return [
      { title: "Списание", to: "/write-offs" },
      { title: "Доп. оборудование" },
    ]
  }

  if (pathname === "/estimates" && searchParams.has("estimateId")) {
    return [{ title: "Сметы", to: "/estimates" }, { title: "Смета" }]
  }

  if (pathname === "/estimates" && searchParams.get("create") === "1") {
    return [{ title: "Сметы", to: "/estimates" }, { title: "Новая смета" }]
  }

  if (pathname === "/repairs" && searchParams.has("repairId")) {
    return [{ title: "Ремонты", to: "/repairs" }, { title: "Задание" }]
  }

  if (pathname === "/repairs" && searchParams.get("create") === "1") {
    return [{ title: "Ремонты", to: "/repairs" }, { title: "Новое задание" }]
  }

  if (pathname === "/") {
    return [{ title: "Главная" }]
  }

  return [
    {
      title:
        routeTitles.find((route) => pathname === route.path)?.title ??
        "WMS Panel",
    },
  ]
}

function getRentalItemId(pathname: string) {
  const match = /^\/warehouse\/([^/]+)$/.exec(pathname)

  return match?.[1] ?? null
}

export function SiteHeader() {
  const { pathname, search } = useLocation()
  const navigate = useNavigate()
  const { accessToken } = useAuth()
  const { selectedWarehouse, warehouses } = useWarehouse()
  const searchParams = new URLSearchParams(search)
  const rentalItemId = getRentalItemId(pathname)
  const repairTaskId =
    pathname === "/acceptance"
      ? searchParams.get("acceptanceId")
      : pathname === "/write-offs"
        ? searchParams.get("writeOffId")
        : null
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
  const isNested = breadcrumbs.length > 1
  const useSlashSeparator = rentalItemId !== null
  const isWarehouseList = pathname === "/warehouse"
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
      <div className="flex min-w-0 flex-1 items-center gap-2 px-4 lg:px-6">
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
