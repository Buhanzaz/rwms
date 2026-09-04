import { useEffect, useRef, type CSSProperties, type ReactNode } from "react"
import {
  AiChat02Icon,
  Alert02Icon,
  Calendar03Icon,
  ChartIncreaseIcon,
  ClipboardCheckIcon,
  Home01Icon,
  Queue01Icon,
  Settings02Icon,
  Task01Icon,
  UserGroupIcon,
  WarehouseIcon,
  Wrench01Icon,
} from "@hugeicons/core-free-icons"
import { Navigate, Route, Routes, useLocation } from "react-router-dom"

import {
  AdminClassesSettingsPage,
  AdminKpiSettingsPage,
  AdminLogisticsSettingsPage,
  AdminWorkScheduleSettingsPage,
} from "@/apps/admin/general-settings-pages"
import { ObjectSettingsPage } from "@/apps/admin/object-settings-page"
import {
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarInset,
  SidebarMenu,
  SidebarProvider,
} from "@/components/ui/sidebar"
import {
  PanelSidebarMenuLink,
  PanelSidebarShell,
} from "@/components/panel-sidebar-shell"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { AnimatedThemeToggler } from "@/components/ui/animated-theme-toggler"
import { RentalSettingsPage } from "@/features/assistant/pages/rental-settings-page"
import { useAuth } from "@/features/auth/use-auth"
import { ClaimsPage } from "@/features/claims/claims-page"
import { CabinCompositionSettingsPage } from "@/features/settings/cabin-composition"
import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"
import { TaskBoardSettingsPage } from "@/features/settings/task-board/task-board-settings-page"
import { UsersPage } from "@/features/settings/users/users-page"
import { WarehouseSettingsPage } from "@/features/settings/warehouses/warehouse-settings-page"
import { useWarehouse } from "@/hooks/use-warehouse"

const backgroundVideoUrl = `${import.meta.env.BASE_URL}background.webm`

type AdminNavigationGroup = {
  label: string | null
  items: Array<{
    to: string
    label: string
    icon: typeof Alert02Icon
  }>
}

const navigationGroups: AdminNavigationGroup[] = [
  {
    label: "Претензии",
    items: [{ to: "/admin/claims", label: "Претензии", icon: Alert02Icon }],
  },
  {
    label: "Настройки пользователей",
    items: [{ to: "/admin/users", label: "Пользователи", icon: UserGroupIcon }],
  },
  {
    label: "Настройки объектов",
    items: [
      { to: "/admin/warehouses", label: "Объекты", icon: WarehouseIcon },
      {
        to: "/admin/object-settings",
        label: "Настройки объекта",
        icon: Settings02Icon,
      },
    ],
  },
  {
    label: "Общие настройки",
    items: [
      { to: "/admin/cabins", label: "Бытовки", icon: Home01Icon },
      {
        to: "/admin/rental",
        label: "Бронирование и чат",
        icon: AiChat02Icon,
      },
      { to: "/admin/kpi", label: "KPI", icon: ChartIncreaseIcon },
      {
        to: "/admin/estimates-repairs",
        label: "Сметы и ремонт",
        icon: Wrench01Icon,
      },
      {
        to: "/admin/task-boards",
        label: "Доски задач",
        icon: ClipboardCheckIcon,
      },
      {
        to: "/admin/logistics",
        label: "Логистика",
        icon: Queue01Icon,
      },
      { to: "/admin/classes", label: "Классы", icon: Task01Icon },
      {
        to: "/admin/work-schedule",
        label: "График работы",
        icon: Calendar03Icon,
      },
    ],
  },
]

const navigationItems = navigationGroups.flatMap((group) => group.items)

function isNavigationItemActive(pathname: string, target: string) {
  return pathname === target || pathname.startsWith(`${target}/`)
}

function currentPageTitle(pathname: string) {
  return (
    navigationItems.find((item) => isNavigationItemActive(pathname, item.to))
      ?.label ?? "Администрирование"
  )
}

function AdminSection({ children }: { children: ReactNode }) {
  return (
    <section className="flex min-h-full flex-col">
      <div className="min-h-0 flex-1">{children}</div>
    </section>
  )
}

function AdminNavigation() {
  const { pathname } = useLocation()

  return (
    <nav aria-label="Разделы администрирования" className="flex flex-col gap-1">
      {navigationGroups.map((group) => (
        <SidebarGroup key={group.label ?? "primary"}>
          {group.label ? (
            <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
          ) : null}
          <SidebarGroupContent>
            <SidebarMenu>
              {group.items.map((item) => {
                const active = isNavigationItemActive(pathname, item.to)
                return (
                  <PanelSidebarMenuLink
                    key={item.to}
                    title={item.label}
                    to={item.to}
                    icon={item.icon}
                    isActive={active}
                  />
                )
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      ))}
    </nav>
  )
}

function AdminEstimatesSettingsPage() {
  return <EstimatesRepairsSettingsPage />
}

function AdminLayout() {
  const { logout } = useAuth()
  const { pathname } = useLocation()
  const { isLoading, error } = useWarehouse()
  const backgroundVideoRef = useRef<HTMLVideoElement>(null)
  const title = currentPageTitle(pathname)

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
        Загружаем справочники…
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
        href="#admin-content"
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
        <PanelSidebarShell
          collapsible="none"
          className="rwms-shell-surface rounded-2xl bg-shell-surface"
          ariaLabel="BLOCKBOX: администрирование"
          caption="Панель администратора"
          onLogout={() => void logout()}
        >
          <AdminNavigation />
        </PanelSidebarShell>

        <SidebarInset
          id="admin-content"
          tabIndex={-1}
          className="rwms-shell-surface min-h-0 overflow-hidden rounded-2xl bg-shell-surface"
        >
          <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border px-4">
            <div className="min-w-0">
              <p className="truncate text-xs text-muted-foreground">
                Администрирование
              </p>
              <p className="truncate text-sm font-medium">{title}</p>
            </div>
            <AnimatedThemeToggler className="ml-auto" />
          </header>

          <div className="min-h-0 flex-1 overflow-auto">
            <div className="min-h-full w-full p-4 lg:p-5">
              <Routes>
                <Route
                  path="/admin/claims"
                  element={
                    <AdminSection>
                      <ClaimsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/users"
                  element={
                    <AdminSection>
                      <UsersPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/warehouses"
                  element={
                    <AdminSection>
                      <WarehouseSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/object-settings"
                  element={
                    <AdminSection>
                      <ObjectSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/object-settings/:section"
                  element={
                    <AdminSection>
                      <ObjectSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/cabins"
                  element={
                    <AdminSection>
                      <CabinCompositionSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/rental"
                  element={
                    <AdminSection>
                      <RentalSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/kpi"
                  element={
                    <AdminSection>
                      <AdminKpiSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/estimates-repairs"
                  element={
                    <AdminSection>
                      <AdminEstimatesSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/task-boards"
                  element={
                    <AdminSection>
                      <TaskBoardSettingsPage section="queue-definitions" />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/logistics"
                  element={
                    <AdminSection>
                      <AdminLogisticsSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/classes"
                  element={
                    <AdminSection>
                      <AdminClassesSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/work-schedule"
                  element={
                    <AdminSection>
                      <AdminWorkScheduleSettingsPage />
                    </AdminSection>
                  }
                />
                <Route
                  path="/admin/task-board"
                  element={<Navigate to="/admin/task-boards" replace />}
                />
                <Route
                  path="/admin"
                  element={<Navigate to="/admin/claims" replace />}
                />
                <Route
                  path="/admin/"
                  element={<Navigate to="/admin/claims" replace />}
                />
                <Route
                  path="*"
                  element={<Navigate to="/admin/claims" replace />}
                />
              </Routes>
            </div>
          </div>
        </SidebarInset>
      </SidebarProvider>
    </div>
  )
}

export function AdminApp() {
  return (
    <WarehouseProvider>
      <AdminLayout />
    </WarehouseProvider>
  )
}
