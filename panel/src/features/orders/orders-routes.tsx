import { useMemo, type ReactNode } from "react"
import { Route, Routes } from "react-router-dom"

import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  RWMS_ORDERS_CAPABILITIES,
  type OrdersModuleCapabilities,
  type OrdersModuleRuntime,
} from "@/features/orders/domain/orders-module"
import { OrdersModuleProvider } from "@/features/orders/orders-module-provider"
import { OrderDetailPage } from "@/features/orders/pages/order-detail-page"
import { OrdersListPage } from "@/features/orders/pages/orders-list-page"
import { useWarehouse } from "@/hooks/use-warehouse"

export function VaultPanelOrdersModuleAdapter({
  children,
  capabilities = RWMS_ORDERS_CAPABILITIES,
}: {
  children: ReactNode
  capabilities?: OrdersModuleCapabilities
}) {
  const { accessToken, currentUser } = useAuth()
  const { warehouses } = useWarehouse()
  const runtime = useMemo<OrdersModuleRuntime>(
    () => ({
      accessToken,
      currentUser: currentUser
        ? {
            id: currentUser.id,
            displayName: currentUser.displayName || currentUser.id,
            globalRole: currentUser.globalRole,
          }
        : null,
      warehouses: warehouses.map((warehouse) => ({
        id: warehouse.id,
        name: warehouse.name,
        city: warehouse.city,
        address: warehouse.address,
      })),
      capabilities,
      canManageBookingChanges: (warehouseId) =>
        Boolean(
          accessToken &&
          currentUser?.rentalAccess &&
          [
            "SYSTEM_ADMIN",
            "WMS_ADMIN",
            "WAREHOUSE_MANAGER",
            "RENTAL_MANAGER",
          ].includes(currentUser.globalRole) &&
          hasWarehouseAccess(currentUser, warehouseId, "EDIT")
        ),
    }),
    [accessToken, capabilities, currentUser, warehouses]
  )

  return <OrdersModuleProvider value={runtime}>{children}</OrdersModuleProvider>
}

export function OrdersRoutes({
  capabilities = RWMS_ORDERS_CAPABILITIES,
}: {
  capabilities?: OrdersModuleCapabilities
} = {}) {
  return (
    <VaultPanelOrdersModuleAdapter capabilities={capabilities}>
      <ManagerBookingAlertDialog />
      <Routes>
        <Route index element={<OrdersListPage />} />
        <Route path="new" element={<OrdersListPage />} />
        <Route path=":orderId" element={<OrderDetailPage />} />
      </Routes>
    </VaultPanelOrdersModuleAdapter>
  )
}
