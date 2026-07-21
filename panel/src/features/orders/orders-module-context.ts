import { createContext, useContext } from "react"

import type { OrdersModuleRuntime } from "@/features/orders/domain/orders-module"

export const OrdersModuleContext = createContext<OrdersModuleRuntime | null>(
  null
)

export function useOrdersModule() {
  const runtime = useContext(OrdersModuleContext)
  if (runtime === null) {
    throw new Error("Orders module must be rendered inside its provider.")
  }
  return runtime
}
