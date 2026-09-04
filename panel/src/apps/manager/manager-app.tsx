import type { IconSvgElement } from "@hugeicons/react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  AiChat02Icon,
  ClipboardCheckIcon,
  ClipboardListIcon,
  Logout03Icon,
  UserGroupIcon,
} from "@hugeicons/core-free-icons"
import { NavLink, Navigate, Route, Routes } from "react-router-dom"

import logotypeUrl from "../../../../Logotype.svg"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { AssistantPage } from "@/features/assistant/pages/assistant-page"
import { useAuth } from "@/features/auth/use-auth"
import { ClientsRoutes } from "@/features/clients/clients-routes"
import { ClaimsPage } from "@/features/claims/claims-page"
import { RENTAL_MANAGER_ORDERS_CAPABILITIES } from "@/features/orders/domain/orders-module"
import { OrdersRoutes } from "@/features/orders/orders-routes"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"
import { ManagerBookingChangeQuotes } from "@/apps/manager/manager-booking-change-quotes"

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

function ManagerNavigation({ mobile = false }: { mobile?: boolean }) {
  return (
    <nav
      aria-label={mobile ? "Основные разделы менеджера" : "Разделы менеджера"}
      className={cn(
        "rwms-manager-navigation",
        mobile ? "grid grid-cols-4 gap-1" : "flex flex-col gap-1"
      )}
    >
      {navigation.map((item) => {
        return (
          <NavLink
            key={item.to}
            to={item.to}
            className={cn(
              "flex min-w-0 items-center justify-center gap-2 rounded-xl px-3 py-3 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-[current=page]:bg-accent aria-[current=page]:text-accent-foreground motion-reduce:transition-none",
              mobile ? "flex-col gap-1 px-1 py-2 text-xs" : "justify-start"
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
    <div className="rwms-manager-shell relative isolate flex h-svh flex-col gap-2 overflow-hidden p-2 md:p-3">
      <header className="rwms-shell-surface z-30 shrink-0 rounded-2xl">
        <div className="mx-auto flex w-full max-w-[1600px] items-center gap-3 px-4 py-3 lg:px-6">
          <NavLink
            to="/assistant"
            aria-label="BLOCKBOX: менеджер аренды"
            className="mr-auto flex min-w-0 items-center gap-2"
          >
            <img
              src={logotypeUrl}
              alt=""
              className="size-9 shrink-0 object-contain"
            />
            <span className="min-w-0">
              <span className="block truncate text-sm font-bold tracking-wide text-primary">
                BLOCKBOX
              </span>
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

      <div className="mx-auto flex min-h-0 w-full max-w-[1600px] flex-1 gap-2 pb-[calc(5rem+env(safe-area-inset-bottom))] md:pb-0">
        <aside className="rwms-shell-surface hidden w-56 shrink-0 overflow-y-auto rounded-2xl p-3 md:block">
          <ManagerNavigation />
        </aside>
        <main className="min-h-0 min-w-0 flex-1 overflow-auto rounded-2xl p-2 sm:p-4 lg:p-5">
          {warehouses.length === 0 ? (
            <Alert className="mb-4">
              <AlertTitle>Не назначены обслуживаемые склады</AlertTitle>
              <AlertDescription>
                Чат и клиенты доступны. Для подбора бытовок и оформления заказов
                попросите администратора назначить вам склады.
              </AlertDescription>
            </Alert>
          ) : null}
          <ManagerBookingChangeQuotes />
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

      <div className="rwms-shell-surface fixed inset-x-2 bottom-[max(0.5rem,env(safe-area-inset-bottom))] z-30 rounded-2xl md:hidden">
        <div className="p-1.5">
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
