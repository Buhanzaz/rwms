export type OrdersModuleRole =
  | "SYSTEM_ADMIN"
  | "WMS_ADMIN"
  | "WAREHOUSE_MANAGER"
  | "RENTAL_MANAGER"
  | "VIEWER"

export type OrdersModuleUser = {
  id: string
  globalRole: OrdersModuleRole
}

export type OrdersModuleWarehouse = {
  id: string
  code: string
  name: string
  city: string
  address: string | null
}

export type OrdersModuleRuntime = {
  accessToken: string | null
  currentUser: OrdersModuleUser | null
  warehouses: OrdersModuleWarehouse[]
}
