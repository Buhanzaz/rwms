import type { IconSvgElement } from "@hugeicons/react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  AiChat02Icon,
  ClipboardCheckIcon,
  ClipboardListIcon,
  Logout03Icon,
  UserGroupIcon,
} from "@hugeicons/core-free-icons"
import { NavLink, Navigate, Route, Routes, useLocation } from "react-router-dom"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { AssistantPage } from "@/features/assistant/pages/assistant-page"
import { useAuth } from "@/features/auth/use-auth"
import { ClientsRoutes } from "@/features/clients/clients-routes"
import { ClaimsPage } from "@/features/claims/claims-page"
import { RENTAL_MANAGER_ORDERS_CAPABILITIES } from "@/features/orders/domain/orders-module"
import { OrdersRoutes } from "@/features/orders/orders-routes"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"

type ManagerNavigationItem = {
  to: string
  label: string
  icon: IconSvgElement
}

const navigation: readonly ManagerNavigationItem[] = [
  { to: "/assistant", label: "Чат", icon: AiChat02Icon },
  { to: "/clients", label: "Клиенты", icon: UserGroupIcon },
  { to: "/orders", label: "Заказы", icon: ClipboardListIcon },
  { to: "/claims", label: "Претензии", icon: ClipboardCheckIcon },
]

function isActivePath(pathname: string, target: string) {
  return pathname === target || pathname.startsWith(`${target}/`)
}

function ManagerNavigation({ mobile = false }: { mobile?: boolean }) {
  const location = useLocation()

  return (
    <nav
      aria-label={mobile ? "Основные разделы менеджера" : "Разделы менеджера"}
      className={cn(mobile ? "grid grid-cols-4 gap-1" : "flex flex-col gap-1")}
    >
      {navigation.map((item) => {
        const active = isActivePath(location.pathname, item.to)
        return (
          <NavLink
            key={item.to}
            to={item.to}
            className={cn(
              "flex min-w-0 items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
              mobile ? "flex-col gap-1 px-1 text-xs" : "justify-start",
              active
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:bg-muted hover:text-foreground"
            )}
          >
            <HugeiconsIcon icon={item.icon} className="size-5 shrink-0" />
            <span className="truncate">{item.label}</span>
          </NavLink>
        )
      })}
    </nav>
  )
}

function ManagerLayout() {
  const { currentUser, logout } = useAuth()
  const { warehouses, isLoading, error } = useWarehouse()

  if (isLoading) {
    return (
      <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6 text-sm text-muted-foreground">
        Загружаем рабочие справочники…
      </main>
    )
  }

  if (error !== null) {
    return (
      <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6 text-center text-sm text-destructive">
        {error}
      </main>
    )
  }

  return (
    <div className="flex min-h-svh flex-col bg-muted/30">
      <header className="sticky top-0 z-30 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex w-full max-w-[1600px] items-center gap-3 px-4 py-3 lg:px-6">
          <NavLink
            to="/assistant"
            className="mr-auto flex min-w-0 items-center gap-2"
          >
            <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary font-bold text-primary-foreground">
              R
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold">RWMS</span>
              <span className="block truncate text-xs text-muted-foreground">
                Менеджер аренды
              </span>
            </span>
          </NavLink>

          <span className="hidden max-w-56 truncate text-sm text-muted-foreground sm:block">
            {currentUser?.displayName}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => void logout()}
          >
            <HugeiconsIcon icon={Logout03Icon} data-icon="inline-start" />
            <span className="hidden sm:inline">Выйти</span>
            <span className="sr-only sm:hidden">Выйти</span>
          </Button>
        </div>
      </header>

      <div className="mx-auto flex min-h-0 w-full max-w-[1600px] flex-1">
        <aside className="hidden w-60 shrink-0 border-r bg-background p-4 md:block">
          <ManagerNavigation />
        </aside>
        <main className="min-h-0 min-w-0 flex-1 p-3 pb-24 sm:p-4 sm:pb-24 lg:p-6 lg:pb-6">
          {warehouses.length === 0 ? (
            <Alert className="mb-4">
              <AlertTitle>Не назначены обслуживаемые склады</AlertTitle>
              <AlertDescription>
                Чат и клиенты доступны. Для подбора бытовок и оформления
                заказов попросите администратора назначить вам склады.
              </AlertDescription>
            </Alert>
          ) : null}
          <Routes>
            <Route path="/assistant" element={<AssistantPage />} />
            <Route
              path="/clients/*"
              element={
                <ClientsRoutes
                  capabilities={RENTAL_MANAGER_ORDERS_CAPABILITIES}
                />
              }
            />
            <Route
              path="/orders/*"
              element={
                <OrdersRoutes
                  capabilities={RENTAL_MANAGER_ORDERS_CAPABILITIES}
                />
              }
            />
            <Route path="/claims" element={<ClaimsPage />} />
            <Route path="/" element={<Navigate to="/assistant" replace />} />
            <Route path="*" element={<Navigate to="/assistant" replace />} />
          </Routes>
        </main>
      </div>

      <div className="fixed inset-x-0 bottom-0 z-30 bg-background md:hidden">
        <Separator />
        <div className="px-2 pt-2 pb-[max(0.5rem,env(safe-area-inset-bottom))]">
          <ManagerNavigation mobile />
        </div>
      </div>
    </div>
  )
}

export function ManagerApp() {
  return (
    <WarehouseProvider>
      <ManagerLayout />
    </WarehouseProvider>
  )
}
