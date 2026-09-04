export type OrdersModuleRole =
  | "SYSTEM_ADMIN"
  | "WMS_ADMIN"
  | "WAREHOUSE_MANAGER"
  | "RENTAL_MANAGER"
  | "CUSTOMER"
  | "VIEWER"

export type OrdersModuleUser = {
  id: string
  displayName: string
  globalRole: OrdersModuleRole
}

export type OrdersModuleWarehouse = {
  id: string
  name: string
  city: string
  address: string | null
}

/** Application-bound capabilities for shared order screens. */
export type OrdersModuleCapabilities = {
  manualBooking: boolean
  logisticsTaskNavigation: boolean
  dossierEvidence: boolean
  equipmentEditing: boolean
  directWarehouseReplacement: boolean
}

export const RWMS_ORDERS_CAPABILITIES: OrdersModuleCapabilities = {
  manualBooking: true,
  logisticsTaskNavigation: false,
  dossierEvidence: true,
  equipmentEditing: true,
  directWarehouseReplacement: true,
}

export const RENTAL_MANAGER_ORDERS_CAPABILITIES: OrdersModuleCapabilities = {
  manualBooking: false,
  logisticsTaskNavigation: false,
  dossierEvidence: false,
  equipmentEditing: false,
  directWarehouseReplacement: false,
}

export type OrdersModuleRuntime = {
  accessToken: string | null
  currentUser: OrdersModuleUser | null
  warehouses: OrdersModuleWarehouse[]
  capabilities: OrdersModuleCapabilities
}
