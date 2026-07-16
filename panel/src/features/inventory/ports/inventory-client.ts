import type {
  InventoryActorSnapshot,
  InventoryCompleteCommand,
  InventoryFindingDto,
  InventoryFindingInspectionCommand,
  InventoryFindingPublicationStatus,
  InventorySessionDto,
  InventoryStartCommand,
} from "@/features/inventory/model/inventory"

export interface InventoryClient {
  list(warehouseId: string): Promise<InventorySessionDto[]>
  get(inventoryId: string): Promise<InventorySessionDto | null>
  getActive(warehouseId: string): Promise<InventorySessionDto | null>
  start(command: InventoryStartCommand): Promise<InventorySessionDto>
  addFinding(command: {
    inventoryId: string
    expectedVersion: number
    actor: InventoryActorSnapshot
    finding: InventoryFindingDto
  }): Promise<InventorySessionDto>
  saveFinding(
    command: InventoryFindingInspectionCommand
  ): Promise<InventorySessionDto>
  replaceFindings(command: {
    inventoryId: string
    expectedVersion: number
    actor: InventoryActorSnapshot
    findings: InventoryFindingDto[]
  }): Promise<InventorySessionDto>
  complete(command: InventoryCompleteCommand): Promise<InventorySessionDto>
  setFindingPublication(command: {
    inventoryId: string
    expectedVersion: number
    actor: InventoryActorSnapshot
    findingId: string
    status: InventoryFindingPublicationStatus
    operationKey: string | null
    repairTaskId: string | null
    error: string | null
  }): Promise<InventorySessionDto>
  subscribe(listener: () => void): () => void
}
