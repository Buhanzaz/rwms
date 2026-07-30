import { useMemo, type ReactNode } from "react"
import { Route, Routes } from "react-router-dom"

import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import type { OrdersModuleRuntime } from "@/features/orders/domain/orders-module"
import { OrdersModuleProvider } from "@/features/orders/orders-module-provider"
import { OrderDetailPage } from "@/features/orders/pages/order-detail-page"
import { OrdersListPage } from "@/features/orders/pages/orders-list-page"
import { useWarehouse } from "@/hooks/use-warehouse"

export function VaultPanelOrdersModuleAdapter({
  children,
}: {
  children: ReactNode
}) {
  const { accessToken, currentUser } = useAuth()
  const { warehouses } = useWarehouse()
  const runtime = useMemo<OrdersModuleRuntime>(
    () => ({
      accessToken,
      currentUser,
      warehouses: warehouses.map((warehouse) => ({
        id: warehouse.id,
        name: warehouse.name,
        city: warehouse.city,
        address: warehouse.address,
      })),
    }),
    [accessToken, currentUser, warehouses]
  )

  return <OrdersModuleProvider value={runtime}>{children}</OrdersModuleProvider>
}

export function OrdersRoutes() {
  return (
    <VaultPanelOrdersModuleAdapter>
      <ManagerBookingAlertDialog />
      <Routes>
        <Route index element={<OrdersListPage />} />
        <Route path="new" element={<OrdersListPage />} />
        <Route path=":orderId" element={<OrderDetailPage />} />
      </Routes>
    </VaultPanelOrdersModuleAdapter>
  )
}
