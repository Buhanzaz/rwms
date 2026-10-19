import { createContext } from "react"

import type { WarehouseInfo } from "@/api/warehouse-api"

export type WarehouseContextValue = {
  warehouses: WarehouseInfo[]
  selectedWarehouse: WarehouseInfo | null
  selectedWarehouseId: string | null
  isLoading: boolean
  error: string | null
  setSelectedWarehouseId: (warehouseId: string) => void
  reloadWarehouses: () => Promise<void>
}

export const WarehouseContext = createContext<WarehouseContextValue | null>(
  null
)
