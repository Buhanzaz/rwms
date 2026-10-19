import type {
  OrdersModuleRole,
  OrdersModuleUser,
} from "@/features/orders/domain/orders-module"

const ORDERS_ADMINISTRATIVE_ROLES = new Set<OrdersModuleRole>([
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
])

export function canViewAllOrders(user: OrdersModuleUser | null) {
  return user !== null && ORDERS_ADMINISTRATIVE_ROLES.has(user.globalRole)
}

export const ORDERS_NAVIGATION = {
  rootLabel: "Заказы",
  listPath: "/orders",
  createPath: "/orders/new",
  createLabel: "Новый заказ",
} as const
