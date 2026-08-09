import { Navigate, Route, Routes } from "react-router-dom"

import { ClientDetailPage } from "@/features/clients/pages/client-detail-page"
import { ClientsListPage } from "@/features/clients/pages/clients-list-page"
import { VaultPanelOrdersModuleAdapter } from "@/features/orders/orders-routes"

export function ClientsRoutes() {
  return (
    <VaultPanelOrdersModuleAdapter>
      <Routes>
        <Route index element={<ClientsListPage />} />
        <Route path="new" element={<Navigate replace to="/clients" />} />
        <Route path=":clientId" element={<ClientDetailPage />} />
      </Routes>
    </VaultPanelOrdersModuleAdapter>
  )
}
