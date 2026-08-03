import { type CSSProperties } from "react"
import { Route, Routes } from "react-router-dom"
import { RentalItemDetailPage } from "@/features/rental-items/rental-item-detail-page"
import { AppSidebar } from "@/components/app-sidebar"
import { SiteHeader } from "@/components/site-header"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { RentalItemsPage } from "@/features/rental-items/rental-items-page"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useWarehouseRealtime } from "@/hooks/use-warehouse-realtime"
import { useAuth } from "@/features/auth/use-auth"
import { EquipmentPage } from "@/features/equipment/equipment-page"
import { KpiPage } from "@/features/kpi/kpi-page"
import { KpiSettingsPage } from "@/features/settings/kpi/kpi-settings-page"
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
import { CabinCompositionSettingsPage } from "@/features/settings/cabin-composition"
import { LogisticsReturnsPage } from "@/features/logistics/logistics-returns-page"
import { LogisticsShipmentsPage } from "@/features/logistics/logistics-shipments-page"
import { LogisticsOrderTasksPage } from "@/features/logistics/logistics-order-tasks-page"
import { WarehouseTransfersPage } from "@/features/logistics/warehouse-transfers/warehouse-transfers-page"
import { DriverBoardPage } from "@/features/logistics/driver-board"
import { LogisticsSettingsPage } from "@/features/settings/logistics"
import { OrdersRoutes } from "@/features/orders/orders-routes"
import { AssistantPage } from "@/features/assistant/pages/assistant-page"
import { RentalSettingsPage } from "@/features/assistant/pages/rental-settings-page"
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
    path: "/logistics/order-tasks",
    title: "Задания",
    description:
      "Отгрузка и возврат бытовок по заказам, состав и задания на мебель.",
  },
  {
    path: "/logistics/tasks",
    title: "Перемещение",
    description:
      "Текущая и плановая очередь перемещений, загрузка ремонтных мест и капитальные ремонты.",
  },
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
      "Очереди ремонтных задач для рабочих, электриков, СЭС и других исполнителей.",
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
    path: "/settings/kpi",
    title: "Настройка KPI",
    description:
      "Складские нормативы и параметры расчёта показателей эффективности.",
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
    path: "/settings/logistics",
    title: "Настройки логистики",
    description:
      "Водители, прикреплённые классы и правила совместного выполнения логистических заданий.",
  },
  {
    path: "/settings/cabins",
    title: "Настройки бытовок",
    description: "Типы, габариты, отделка, характеристики и связи между ними.",
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
  const { isLoading, error, selectedWarehouse } = useWarehouse()
  const { accessToken, currentUser } = useAuth()

  // Keep the warehouse projections and media stream alive while the user
  // moves between routes. The cache can then be patched in the background and
  // the warehouse page paints immediately when it is opened again.
  useWarehouseRealtime({
    accessToken,
    warehouseId: selectedWarehouse?.id,
    userId: currentUser?.id,
  })

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

        <div className="min-h-0 flex-1 overflow-hidden p-4 lg:p-3">
          <Routes>
            <Route
              path="/warehouse/:rentalItemId"
              element={<RentalItemDetailPage />}
            />
            <Route path="/equipment" element={<EquipmentPage />} />
            <Route path="/kpi" element={<KpiPage />} />
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
            <Route path="/logistics/tasks" element={<DriverBoardPage />} />
            <Route
              path="/logistics/order-tasks"
              element={<LogisticsOrderTasksPage />}
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
            <Route path="/orders/*" element={<OrdersRoutes />} />
            <Route path="/assistant" element={<AssistantPage />} />
            <Route path="/write-offs" element={<WriteOffsPage />} />
            <Route
              path="/write-offs/equipment"
              element={<EquipmentWriteOffsPage />}
            />
            <Route path="/settings/kpi" element={<KpiSettingsPage />} />
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
            <Route
              path="/settings/logistics"
              element={<LogisticsSettingsPage />}
            />
            <Route
              path="/settings/cabins"
              element={<CabinCompositionSettingsPage />}
            />
            <Route path="/settings/rental" element={<RentalSettingsPage />} />

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
                    "/logistics/order-tasks",
                    "/logistics/returns",
                    "/logistics/shipments",
                    "/logistics/transfers",
                    "/write-offs",
                    "/write-offs/equipment",
                    "/settings/kpi",
                    "/settings/estimates-repairs",
                    "/settings/warehouses",
                    "/settings/task-board",
                    "/settings/logistics",
                    "/settings/cabins",
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
