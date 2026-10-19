import { useContext } from "react"

import { WarehouseContext } from "@/contexts/warehouse-context"

export function useWarehouse() {
  const context = useContext(WarehouseContext)

  if (context === null) {
    throw new Error("useWarehouse must be used inside WarehouseProvider")
  }

  return context
}
