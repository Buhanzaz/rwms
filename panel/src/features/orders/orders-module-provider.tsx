import type { ReactNode } from "react"

import type { OrdersModuleRuntime } from "@/features/orders/domain/orders-module"
import { OrdersModuleContext } from "@/features/orders/orders-module-context"

export function OrdersModuleProvider({
  value,
  children,
}: {
  value: OrdersModuleRuntime
  children: ReactNode
}) {
  return (
    <OrdersModuleContext.Provider value={value}>
      {children}
    </OrdersModuleContext.Provider>
  )
}
