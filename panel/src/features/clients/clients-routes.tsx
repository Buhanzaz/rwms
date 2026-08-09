import { Route, Routes } from "react-router-dom"

import { ClientCreatePage } from "@/features/clients/pages/client-create-page"
import { ClientDetailPage } from "@/features/clients/pages/client-detail-page"
import { ClientsListPage } from "@/features/clients/pages/clients-list-page"
import { VaultPanelOrdersModuleAdapter } from "@/features/orders/orders-routes"

export function ClientsRoutes() {
  return (
    <VaultPanelOrdersModuleAdapter>
      <Routes>
        <Route index element={<ClientsListPage />} />
        <Route path="new" element={<ClientCreatePage />} />
        <Route path=":clientId" element={<ClientDetailPage />} />
      </Routes>
    </VaultPanelOrdersModuleAdapter>
  )
}
