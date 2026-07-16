export type WarehouseLocationSectorCode = "A" | "B" | "C"
export type WarehouseLocationSectorName = "START" | "CENTER" | "END"
export type WarehouseLocationSideName = "LEFT" | "RIGHT"
export type WarehouseLocationEdgeType = "SAME_ROAD" | "CROSS_ROAD" | "MANUAL"

export type WarehouseLocationNodeDto = {
  id: string
  warehouseId: string
  code: string
  roadNumber: 1 | 2
  sectorCode: WarehouseLocationSectorCode
  sectorName: WarehouseLocationSectorName
  sectorOrder: 0 | 1 | 2
  sideNumber: 1 | 2
  sideName: WarehouseLocationSideName
  displayName: string
  active: boolean
}

export type WarehouseLocationEdgeDto = {
  id: string
  warehouseId: string
  fromNodeId: string
  toNodeId: string
  distanceWeight: number
  edgeType: WarehouseLocationEdgeType
  active: boolean
}

export type NearbyLocationNodeDto = {
  node: WarehouseLocationNodeDto
  distanceWeight: number
  distanceLabel: string
}

export type WarehouseInventoryStockItemDto = {
  name: string
  availableQuantity: number
}

export type NearbyInventorySourceDto = {
  rentalItemId: string
  number: string
  type: string
  locationNodeId: string
  locationCode: string
  locationDisplayName: string
  distanceWeight: number
  distanceLabel: string
  inventory: Array<{
    name: string
    availableQuantity: number
  }>
}

export type AddInventoryFromWarehousePayload = {
  items: Array<{
    name: string
    quantity: number
  }>
}

export type TransferInventoryFromRentalItemPayload = {
  sourceRentalItemId: string
  items: Array<{
    name: string
    quantity: number
  }>
}
