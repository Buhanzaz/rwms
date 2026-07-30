import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type MaintenanceCatalogLifecycle = "DRAFT" | "ACTIVE" | "SUPERSEDED"
export type MaintenanceCatalogNodeType =
  "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"
export type MaintenanceCatalogLinkType = "DEPENDENCY" | "FOLLOW_UP"

export type MaintenanceCatalogRouting = {
  queueId: string
  queueName: string
  queueType: string
}

export type MaintenanceCatalogRoutingInput = {
  queueId: string
  queueType: string
}

export type MaintenanceFurnitureEquipmentReference = {
  equipmentId: string
  equipmentName: string
}

export type MaintenanceCabinCharacteristicReference = {
  characteristicId: string
  characteristicName: string
}

export type MaintenanceCatalogNodeInput = {
  id: string
  nodeType: MaintenanceCatalogNodeType
  name: string
  /** Optional semantic button colour configured in the catalog settings. */
  displayColor?: string | null
  active: boolean
  parentNodeId: string | null
  unit: string | null
  unitPrice: string | null
  durationMinutes: number
  includeInEstimate: boolean
  commonItem: boolean
  showInMainMenu: boolean
  furnitureCategory: boolean
  furnitureEquipment: MaintenanceFurnitureEquipmentReference | null
  forcesCapitalRepair: boolean
  characteristicId: string | null
  routing: MaintenanceCatalogRoutingInput | null
  canvasX: number | null
  canvasY: number | null
  comment: string | null
}

export type MaintenanceCatalogNode = Omit<
  MaintenanceCatalogNodeInput,
  "routing" | "characteristicId"
> & {
  catalogVersionId: string
  routing: MaintenanceCatalogRouting | null
  characteristic: MaintenanceCabinCharacteristicReference | null
}

export type MaintenanceCatalogLinkInput = {
  id: string
  fromNodeId: string
  toNodeId: string
  linkType: MaintenanceCatalogLinkType
  sortOrder: number
  sourceAnchor: "TOP" | "BOTTOM" | null
  targetAnchor: "TOP" | "BOTTOM" | null
}

export type MaintenanceCatalogLink = MaintenanceCatalogLinkInput & {
  catalogVersionId: string
}

export type MaintenanceCatalogVersion = {
  id: string
  warehouseId: string
  version: number
  lifecycle: MaintenanceCatalogLifecycle
  sourceSha256: string
  counts: { nodes: number; links: number }
  validation: {
    valid: boolean
    errorCount: number
    warningCount: number
    reportSha256: string
  }
  createdAt: string
  activatedAt: string | null
}

export type MaintenanceCatalogVersionPage = {
  items: MaintenanceCatalogVersion[]
  page: number
  size: number
  totalElements: number
}

export type MaintenanceCreateCatalogRequest = {
  warehouseId: string
}

const CATALOG_API = `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/catalog`

function catalogVersionEndpoint(warehouseId: string, catalogVersionId: string) {
  const endpoint = new URL(
    `${CATALOG_API}/versions/${encodeURIComponent(catalogVersionId)}`
  )
  endpoint.searchParams.set("warehouseId", warehouseId)
  return endpoint
}

function json(
  method: string,
  body: unknown,
  headers?: HeadersInit
): RequestInit {
  return { method, headers, body: JSON.stringify(body) }
}

export function listMaintenanceCatalogVersions(
  accessToken: string,
  warehouseId: string,
  lifecycle?: MaintenanceCatalogLifecycle
) {
  const endpoint = new URL(`${CATALOG_API}/versions`)
  endpoint.searchParams.set("warehouseId", warehouseId)
  endpoint.searchParams.set("page", "0")
  endpoint.searchParams.set("size", "200")
  if (lifecycle) endpoint.searchParams.set("lifecycle", lifecycle)

  return bearerRequest<MaintenanceCatalogVersionPage>(accessToken, endpoint)
}

export function listMaintenanceCatalogNodes(
  accessToken: string,
  warehouseId: string,
  catalogVersionId: string
) {
  const endpoint = catalogVersionEndpoint(warehouseId, catalogVersionId)
  endpoint.pathname += "/nodes"
  return bearerRequest<MaintenanceCatalogNode[]>(accessToken, endpoint)
}

export function listMaintenanceCatalogLinks(
  accessToken: string,
  warehouseId: string,
  catalogVersionId: string
) {
  const endpoint = catalogVersionEndpoint(warehouseId, catalogVersionId)
  endpoint.pathname += "/links"
  return bearerRequest<MaintenanceCatalogLink[]>(accessToken, endpoint)
}

export function replaceMaintenanceCatalogNodes(
  accessToken: string,
  warehouseId: string,
  catalogVersionId: string,
  expectedVersion: number,
  nodes: MaintenanceCatalogNodeInput[]
) {
  const endpoint = catalogVersionEndpoint(warehouseId, catalogVersionId)
  endpoint.pathname += "/nodes"
  return bearerRequest<MaintenanceCatalogVersion>(
    accessToken,
    endpoint,
    json("PUT", { expectedVersion, nodes })
  )
}

export function replaceMaintenanceCatalog(
  accessToken: string,
  warehouseId: string,
  catalogVersionId: string,
  expectedVersion: number,
  nodes: MaintenanceCatalogNodeInput[],
  links: MaintenanceCatalogLinkInput[]
) {
  const endpoint = catalogVersionEndpoint(warehouseId, catalogVersionId)
  return bearerRequest<MaintenanceCatalogVersion>(
    accessToken,
    endpoint,
    json("PUT", { expectedVersion, nodes, links })
  )
}

export function replaceMaintenanceCatalogLinks(
  accessToken: string,
  warehouseId: string,
  catalogVersionId: string,
  expectedVersion: number,
  links: MaintenanceCatalogLinkInput[]
) {
  const endpoint = catalogVersionEndpoint(warehouseId, catalogVersionId)
  endpoint.pathname += "/links"
  return bearerRequest<MaintenanceCatalogVersion>(
    accessToken,
    endpoint,
    json("PUT", { expectedVersion, links })
  )
}

export function createMaintenanceCatalog(
  accessToken: string,
  idempotencyKey: string,
  request: MaintenanceCreateCatalogRequest
) {
  return bearerRequest<MaintenanceCatalogVersion>(
    accessToken,
    `${CATALOG_API}/versions`,
    json("POST", request, { "Idempotency-Key": idempotencyKey })
  )
}
