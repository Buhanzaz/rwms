import { Route, Routes, useLocation } from "react-router-dom"
import { RentalItemDetailPage } from "@/features/rental-items/rental-item-detail-page"
import { AppSidebar } from "@/components/app-sidebar"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { RentalItemsPage } from "@/features/rental-items/rental-items-page"
import { useWarehouse } from "@/hooks/use-warehouse"
import { EquipmentPage } from "@/features/equipment/equipment-page"
import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar"

type PageConfig = {
  path: string
  title: string
  description: string
}

const pages: PageConfig[] = [
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

function getCurrentPage(pathname: string) {
  const page = pages.find((item) => {
    if (item.path === "/") {
      return pathname === "/"
    }

    return pathname.startsWith(item.path)
  })

  return (
    page ?? {
      path: pathname,
      title: "Страница",
      description: "Раздел WMS Panel.",
    }
  )
}

function EmptyPage({ title }: { title: string }) {
  return (
    <div className="h-full rounded-lg border bg-card p-4">
      <div className="text-sm text-muted-foreground">
        Раздел «{title}» пока пустой.
      </div>
    </div>
  )
}

function AppLayout() {
  const location = useLocation()
  const currentPage = getCurrentPage(location.pathname)

  const { selectedWarehouse, isLoading, error } = useWarehouse()

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

  const pageTitle = selectedWarehouse
    ? `${currentPage.title}: ${selectedWarehouse.code}`
    : currentPage.title

  return (
    <SidebarProvider>
      <AppSidebar />

      <SidebarInset className="h-svh overflow-hidden">
        <header className="flex h-16 shrink-0 items-center gap-3 border-b px-4">
          <SidebarTrigger />

          <div className="min-w-0">
            <h1 className="truncate text-base font-semibold">{pageTitle}</h1>

            <p className="truncate text-xs text-muted-foreground">
              {currentPage.description}
            </p>
          </div>
        </header>

        <main className="flex-1 overflow-hidden p-4">
          <Routes>
            <Route
              path="/warehouse/:rentalItemId"
              element={<RentalItemDetailPage />}
            />
            <Route path="/equipment" element={<EquipmentPage />} />
            <Route path="/warehouse" element={<RentalItemsPage />} />
            <Route
              path="/settings/estimates-repairs"
              element={<EstimatesRepairsSettingsPage />}
            />

            {pages
              .filter(
                (page) =>
                  ![
                    "/warehouse",
                    "/equipment",
                    "/settings/estimates-repairs",
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
        </main>
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
