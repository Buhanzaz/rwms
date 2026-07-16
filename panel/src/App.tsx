import { type CSSProperties } from "react"
import { Route, Routes } from "react-router-dom"
import { AppSidebar } from "@/components/app-sidebar"
import { SiteHeader } from "@/components/site-header"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { useWarehouse } from "@/hooks/use-warehouse"
import { AssetEquipmentPage } from "@/features/assets/asset-equipment-page"
import { AssetEquipmentWriteOffsPage } from "@/features/assets/asset-equipment-write-offs-page"
import { AssetRentalItemDetailPage } from "@/features/assets/asset-rental-item-detail-page"
import { AssetRentalItemsPage } from "@/features/assets/asset-rental-items-page"
import { AssetSettingsPage } from "@/features/assets/asset-settings-page"
import { TaskBoardPage } from "@/features/task-board/task-board-page"
import { UsersPage } from "@/features/settings/users/users-page"
import { TaskBoardSettingsPage } from "@/features/settings/task-board/task-board-settings-page"
import { WarehouseSettingsPage } from "@/features/settings/warehouses/warehouse-settings-page"
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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

function DeferredWorkflowPage({ title }: { title: string }) {
  return (
    <Card className="h-full" size="sm">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>
          Этот workflow отложен до своего сервисного этапа. Production panel не
          использует для него browser fixtures или local storage.
        </CardDescription>
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
              element={<AssetRentalItemDetailPage />}
            />
            <Route path="/equipment" element={<AssetEquipmentPage />} />
            <Route path="/warehouse" element={<AssetRentalItemsPage />} />
            <Route
              path="/inventory/*"
              element={<DeferredWorkflowPage title="Инвентаризация" />}
            />
            <Route
              path="/estimates"
              element={<DeferredWorkflowPage title="Сметы" />}
            />
            <Route
              path="/repairs"
              element={<DeferredWorkflowPage title="Ремонты" />}
            />
            <Route path="/task-board" element={<TaskBoardPage />} />
            <Route
              path="/acceptance"
              element={<DeferredWorkflowPage title="Приёмка и доработки" />}
            />
            <Route
              path="/logistics/returns"
              element={<DeferredWorkflowPage title="Возврат из аренды" />}
            />
            <Route
              path="/logistics/shipments"
              element={<DeferredWorkflowPage title="Отгрузка в аренду" />}
            />
            <Route
              path="/logistics/transfers"
              element={<DeferredWorkflowPage title="Перемещения" />}
            />
            <Route
              path="/write-offs"
              element={<DeferredWorkflowPage title="Списание бытовок" />}
            />
            <Route
              path="/write-offs/equipment"
              element={<AssetEquipmentWriteOffsPage />}
            />
            <Route
              path="/settings/estimates-repairs"
              element={
                <DeferredWorkflowPage title="Настройка смет и ремонтов" />
              }
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
            <Route path="/settings/assets" element={<AssetSettingsPage />} />

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
                    "/settings/task-board",
                    "/settings/assets",
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
