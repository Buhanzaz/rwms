import { type CSSProperties } from "react"
import { Route, Routes } from "react-router-dom"
import { RentalItemDetailPage } from "@/features/rental-items/rental-item-detail-page"
import { AppSidebar } from "@/components/app-sidebar"
import { SiteHeader } from "@/components/site-header"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { RentalItemsPage } from "@/features/rental-items/rental-items-page"
import { useWarehouse } from "@/hooks/use-warehouse"
import { EquipmentPage } from "@/features/equipment/equipment-page"
import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"
import { RepairEstimatesPage } from "@/features/repair-estimates/repair-estimates-page"
import { RepairsPage } from "@/features/repairs/repairs-page"
import { TaskBoardPage } from "@/features/task-board/task-board-page"
import { AcceptancePage } from "@/features/acceptance/acceptance-page"
import { WriteOffsPage } from "@/features/write-offs/write-offs-page"
import { EquipmentWriteOffsPage } from "@/features/write-offs/equipment-write-offs-page"
import { UsersPage } from "@/features/settings/users/users-page"
import { WarehouseSettingsPage } from "@/features/settings/warehouses/warehouse-settings-page"
import { TaskBoardSettingsPage } from "@/features/settings/task-board/task-board-settings-page"
import { LogisticsReturnsPage } from "@/features/logistics/logistics-returns-page"
import { LogisticsShipmentsPage } from "@/features/logistics/logistics-shipments-page"
import { WarehouseTransfersPage } from "@/features/logistics/warehouse-transfers/warehouse-transfers-page"
import { OrdersRoutes } from "@/features/orders/orders-routes"
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
    path: "/",
    title: "Главная",
    description:
      "Общая сводка по складу, ремонтам, задачам и ключевым показателям.",
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
      "Очереди задач для рабочих, электриков, СЭС, водителей и других исполнителей.",
  },
  {
    path: "/acceptance",
    title: "Приёмка и доработки",
    description: "Приёмка выполненных работ и отправка задач на переделку.",
  },
  {
    path: "/write-offs",
    title: "Списание",
    description: "Архив списанных бытовок и решений по ремонтному циклу.",
  },
  {
    path: "/write-offs/equipment",
    title: "Списание доп. оборудования",
    description: "Агрегированные количества списанного доп. оборудования.",
  },
  {
    path: "/settings/estimates-repairs",
    title: "Настройка смет и ремонтов",
    description:
      "Параметры смет, ремонтного цикла, каталогов работ и материалов.",
  },
  {
    path: "/settings/task-board",
    title: "Настройка Доски задач",
    description:
      "Очереди, маршруты, исполнители и параметры отображения доски задач.",
  },
  {
    path: "/settings",
    title: "Настройки",
    description: "Настройки системы, справочники и параметры терминала.",
  },
]

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
  const { isLoading, error } = useWarehouse()

  if (isLoading) {
    return (
      <div className="flex h-svh items-center justify-center text-sm text-muted-foreground">
        Загрузка складов...
      </div>
    )
  }

  if (error !== null) {
    return (
      <div className="flex h-svh items-center justify-center text-sm text-destructive">
        {error}
      </div>
    )
  }

  return (
    <SidebarProvider
      className="h-svh"
      style={
        {
          "--sidebar-width": "18rem",
          "--header-height": "3rem",
        } as CSSProperties
      }
    >
      <AppSidebar variant="inset" />

      <SidebarInset className="min-h-0 min-w-0 overflow-hidden">
        <SiteHeader />

        <div className="min-h-0 flex-1 overflow-hidden p-4 lg:p-6">
          <Routes>
            <Route
              path="/warehouse/:rentalItemId"
              element={<RentalItemDetailPage />}
            />
            <Route path="/equipment" element={<EquipmentPage />} />
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
            <Route path="/orders/*" element={<OrdersRoutes />} />
            <Route path="/write-offs" element={<WriteOffsPage />} />
            <Route
              path="/write-offs/equipment"
              element={<EquipmentWriteOffsPage />}
            />
            <Route
              path="/settings/estimates-repairs"
              element={<EstimatesRepairsSettingsPage />}
            />
            <Route path="/settings/users" element={<UsersPage />} />
            <Route
              path="/settings/warehouses"
              element={<WarehouseSettingsPage />}
            />
            <Route
              path="/settings/task-board"
              element={<TaskBoardSettingsPage />}
            />

            {pages
              .filter(
                (page) =>
                  ![
                    "/warehouse",
                    "/equipment",
                    "/inventory",
                    "/estimates",
                    "/repairs",
                    "/task-board",
                    "/acceptance",
                    "/logistics/returns",
                    "/logistics/shipments",
                    "/logistics/transfers",
                    "/write-offs",
                    "/write-offs/equipment",
                    "/settings/estimates-repairs",
                    "/settings/warehouses",
                    "/settings/task-board",
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
        </div>
      </SidebarInset>
    </SidebarProvider>
  )
}

export default function App() {
  return (
    <WarehouseProvider>
      <AppLayout />
    </WarehouseProvider>
  )
}
