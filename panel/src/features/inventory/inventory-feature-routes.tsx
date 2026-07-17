import { Route, Routes } from "react-router-dom"

import {
  InventoryEntryPage,
  InventoryFinishPage,
  InventoryHistoryDetailPage,
  InventoryHistoryPage,
  InventorySessionPage,
} from "@/features/inventory/inventory-pages"
import { DEV_INVENTORY_FIXTURES_ENABLED } from "@/features/inventory/inventory-runtime"
import { InventoryServiceRoutes } from "@/features/inventory/inventory-service-pages"

export function InventoryFeatureRoutes() {
  if (!DEV_INVENTORY_FIXTURES_ENABLED) return <InventoryServiceRoutes />

  return (
    <Routes>
      <Route index element={<InventoryEntryPage />} />
      <Route path="history" element={<InventoryHistoryPage />} />
      <Route
        path="history/:inventoryId"
        element={<InventoryHistoryDetailPage />}
      />
      <Route path=":inventoryId/finish" element={<InventoryFinishPage />} />
      <Route path=":inventoryId" element={<InventorySessionPage />} />
    </Routes>
  )
}
