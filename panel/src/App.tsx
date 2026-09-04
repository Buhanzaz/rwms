import { useEffect, useRef, type CSSProperties } from "react"
import { Navigate, Route, Routes } from "react-router-dom"
import { RentalItemDetailPage } from "@/features/rental-items/rental-item-detail-page"
import { AppSidebar } from "@/components/app-sidebar"
import { SiteHeader } from "@/components/site-header"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { RentalItemsPage } from "@/features/rental-items/rental-items-page"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useWarehouseRealtime } from "@/hooks/use-warehouse-realtime"
import { useAuth } from "@/features/auth/use-auth"
import { EquipmentPage } from "@/features/equipment/equipment-page"
import { HomePage } from "@/features/home/home-page"
import { KpiPage } from "@/features/kpi/kpi-page"
import { ClaimsPage } from "@/features/claims/claims-page"
import { RepairEstimatesPage } from "@/features/repair-estimates/repair-estimates-page"
import { RepairsPage } from "@/features/repairs/repairs-page"
import { TaskBoardPage } from "@/features/task-board/task-board-page"
import { AcceptancePage } from "@/features/acceptance/acceptance-page"
import { WriteOffsPage } from "@/features/write-offs/write-offs-page"
import { EquipmentWriteOffsPage } from "@/features/write-offs/equipment-write-offs-page"
import { LogisticsReturnsPage } from "@/features/logistics/logistics-returns-page"
import { LogisticsShipmentsPage } from "@/features/logistics/logistics-shipments-page"
import { WarehouseTransfersPage } from "@/features/logistics/warehouse-transfers/warehouse-transfers-page"
import {
  BookingCatalogPage,
  BookingContinuePage,
  BookingSelectionProvider,
} from "@/features/booking"
import {
  InventoryEntryPage,
  InventoryFinishPage,
  InventoryHistoryDetailPage,
  InventoryHistoryPage,
  InventorySessionPage,
} from "@/features/inventory/inventory-pages"
import { Card, CardDescription, CardHeader } from "@/components/ui/card"
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"

type PageConfig = {
  path: string
  title: string
  description: string
}

const pages: PageConfig[] = [
  {
    path: "/logistics/returns",
    title: "Возврат из аренды",
    description: "Регистрация возвращённых бытовок и осмотр после аренды.",
  },
  {
    path: "/logistics/shipments",
    title: "Отгрузка в аренду",
    description: "Отгрузки бытовок арендаторам.",
  },
  {
    path: "/logistics/transfers",
    title: "Перемещения",
    description: "Перемещения бытовок и наполнения между складами.",
  },
  {
    path: "/kpi",
    title: "KPI",
    description: "Показатели эффективности склада, ремонтов и рабочих групп.",
  },
  {
    path: "/warehouse",
    title: "Склад",
    description:
      "Реестр бытовок, контейнеров, модулей и другого основного имущества.",
  },
  {
    path: "/equipment",
    title: "Доп. оборудование",
    description:
      "Учёт кроватей, столов, стульев, конвекторов, кондиционеров и другого оборудования.",
  },
  {
    path: "/inventory",
    title: "Инвентаризация",
    description:
      "Проверка фактического наличия имущества и фиксация расхождений.",
  },
  {
    path: "/estimates",
    title: "Сметы",
    description: "Создание, просмотр и история смет по работам и материалам.",
  },
  {
    path: "/repairs",
    title: "Ремонты",
    description:
      "Ремонтный цикл после аренды, текущие ремонты и капитальные ремонты.",
  },
  {
    path: "/task-board",
    title: "Доска задач",
    description:
      "Очереди ремонтных задач для рабочих, электриков, СЭС и других исполнителей.",
  },
  {
    path: "/acceptance",
    title: "Приёмка и доработки",
    description: "Приёмка выполненных работ и отправка задач на переделку.",
  },
  {
    path: "/write-offs",
    title: "Списания",
    description: "Решения по списанию бытовок и дополнительного оборудования.",
  },
  {
    path: "/write-offs/equipment",
    title: "Утраты",
    description: "Решения по утрате бытовок и дополнительного оборудования.",
  },
]

const backgroundVideoUrl = "/admin/background.webm"

function EmptyPage({ title }: { title: string }) {
  return (
    <Card className="h-full" size="sm">
      <CardHeader>
        <CardDescription>Раздел «{title}» пока пустой.</CardDescription>
      </CardHeader>
    </Card>
  )
}

function AppLayout() {
  const { isLoading, error, selectedWarehouse } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const backgroundVideoRef = useRef<HTMLVideoElement>(null)

  // Keep the warehouse projections and media stream alive while the user
  // moves between routes. The cache can then be patched in the background and
  // the warehouse page paints immediately when it is opened again.
  useWarehouseRealtime({
    accessToken,
    warehouseId: selectedWarehouse?.id,
    userId: currentUser?.id,
  })

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return

    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)")
    const stopForReducedMotion = () => {
      const video = backgroundVideoRef.current
      if (reducedMotion.matches && video && !video.paused) {
        video.pause()
      }
    }
    stopForReducedMotion()
    reducedMotion.addEventListener("change", stopForReducedMotion)
    return () =>
      reducedMotion.removeEventListener("change", stopForReducedMotion)
  }, [])

  if (isLoading) {
    return (
      <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6 text-sm text-muted-foreground">
        Загрузка складов...
      </main>
    )
  }

  if (error !== null) {
    return (
      <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6 text-sm text-destructive">
        {error}
      </main>
    )
  }

  return (
    <div className="relative isolate h-svh overflow-hidden bg-background">
      <a
        href="#panel-content"
        className="sr-only z-50 rounded-md bg-background px-3 py-2 text-sm font-medium shadow-lg focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus-visible:ring-2 focus-visible:ring-ring"
      >
        К основному содержимому
      </a>
      <video
        ref={backgroundVideoRef}
        aria-hidden="true"
        tabIndex={-1}
        autoPlay
        loop
        muted
        playsInline
        preload="metadata"
        onLoadedMetadata={(event) => {
          event.currentTarget.playbackRate = 0.5
        }}
        className="pointer-events-none absolute inset-0 -z-20 size-full object-cover motion-reduce:hidden"
      >
        <source src={backgroundVideoUrl} type="video/webm" />
      </video>
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 bg-background/42 backdrop-blur-[2px] dark:bg-background/72"
      />

      <SidebarProvider
        style={{ "--sidebar-width": "15rem" } as CSSProperties}
        className="h-svh min-h-0 gap-2 bg-transparent p-2"
      >
        <AppSidebar
          collapsible="none"
          className="rounded-lg border border-border bg-sidebar/75 shadow-sm backdrop-blur-md"
        />

        <SidebarInset
          id="panel-content"
          tabIndex={-1}
          className="min-h-0 overflow-hidden rounded-lg border border-border bg-background/75 shadow-sm backdrop-blur-md"
        >
          <SiteHeader />

          <div className="min-h-0 flex-1 overflow-auto">
            <div className="min-h-full w-full p-4 lg:p-5">
              <BookingSelectionProvider>
                <Routes>
                  <Route
                    path="/warehouse/:rentalItemId"
                    element={<RentalItemDetailPage />}
                  />
                  <Route path="/booking" element={<BookingCatalogPage />} />
                  <Route
                    path="/booking/continue"
                    element={<BookingContinuePage />}
                  />
                  <Route path="/equipment" element={<EquipmentPage />} />
                  <Route path="/" element={<HomePage />} />
                  <Route path="/kpi" element={<KpiPage />} />
                  <Route path="/claims" element={<ClaimsPage />} />
                  <Route path="/warehouse" element={<RentalItemsPage />} />
                  <Route path="/inventory" element={<InventoryEntryPage />} />
                  <Route
                    path="/inventory/history"
                    element={<InventoryHistoryPage />}
                  />
                  <Route
                    path="/inventory/history/:inventoryId"
                    element={<InventoryHistoryDetailPage />}
                  />
                  <Route
                    path="/inventory/:inventoryId/finish"
                    element={<InventoryFinishPage />}
                  />
                  <Route
                    path="/inventory/:inventoryId"
                    element={<InventorySessionPage />}
                  />
                  <Route path="/estimates" element={<RepairEstimatesPage />} />
                  <Route path="/repairs" element={<RepairsPage />} />
                  <Route path="/task-board" element={<TaskBoardPage />} />
                  <Route path="/acceptance" element={<AcceptancePage />} />
                  <Route
                    path="/logistics/tasks"
                    element={<Navigate to="/logistics/transfers" replace />}
                  />
                  <Route
                    path="/logistics/board"
                    element={<Navigate to="/logistics/shipments" replace />}
                  />
                  <Route
                    path="/logistics/order-tasks"
                    element={<Navigate to="/logistics/shipments" replace />}
                  />
                  <Route
                    path="/logistics/returns"
                    element={<LogisticsReturnsPage />}
                  />
                  <Route
                    path="/logistics/shipments"
                    element={<LogisticsShipmentsPage />}
                  />
                  <Route
                    path="/logistics/transfers"
                    element={<WarehouseTransfersPage />}
                  />
                  <Route
                    path="/orders/*"
                    element={<Navigate to="/" replace />}
                  />
                  <Route
                    path="/clients/*"
                    element={<Navigate to="/" replace />}
                  />
                  <Route
                    path="/assistant"
                    element={<Navigate to="/" replace />}
                  />
                  <Route path="/write-offs" element={<WriteOffsPage />} />
                  <Route
                    path="/write-offs/equipment"
                    element={<EquipmentWriteOffsPage />}
                  />
                  <Route
                    path="/settings/*"
                    element={<Navigate to="/" replace />}
                  />

                  {pages
                    .filter(
                      (page) =>
                        ![
                          "/warehouse",
                          "/equipment",
                          "/kpi",
                          "/inventory",
                          "/estimates",
                          "/repairs",
                          "/task-board",
                          "/acceptance",
                          "/logistics/tasks",
                          "/logistics/board",
                          "/logistics/order-tasks",
                          "/logistics/returns",
                          "/logistics/shipments",
                          "/logistics/transfers",
                          "/write-offs",
                          "/write-offs/equipment",
                        ].includes(page.path)
                    )
                    .map((page) => (
                      <Route
                        key={page.path}
                        path={page.path}
                        element={<EmptyPage title={page.title} />}
                      />
                    ))}
                </Routes>
              </BookingSelectionProvider>
            </div>
          </div>
        </SidebarInset>
      </SidebarProvider>
    </div>
  )
}

export default function App() {
  return (
    <WarehouseProvider>
      <AppLayout />
    </WarehouseProvider>
  )
}
