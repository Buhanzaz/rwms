import { readRentalItems, writeRentalItems } from "@/features/rental-items/api/rental-items-api"
import type {
  NearbyLocationNodeDto,
  WarehouseLocationEdgeDto,
  WarehouseLocationEdgeType,
  WarehouseLocationNodeDto,
  WarehouseLocationSectorCode,
  WarehouseLocationSectorName,
  WarehouseLocationSideName,
} from "@/types/warehouse-location"

export const WAREHOUSE_LOCATION_NODES_STORAGE_KEY =
  "wms:mock-warehouse-location-nodes"
export const WAREHOUSE_LOCATION_EDGES_STORAGE_KEY =
  "wms:mock-warehouse-location-edges"
export const WAREHOUSE_LOCATION_MOCK_UPDATED_EVENT =
  "wms:mock-warehouse-location-updated"

type LocationSpec = {
  sectorCode: WarehouseLocationSectorCode
  sectorName: WarehouseLocationSectorName
  sectorOrder: 0 | 1 | 2
}

const locationSpecs: LocationSpec[] = [
  {
    sectorCode: "A",
    sectorName: "START",
    sectorOrder: 0,
  },
  {
    sectorCode: "B",
    sectorName: "CENTER",
    sectorOrder: 1,
  },
  {
    sectorCode: "C",
    sectorName: "END",
    sectorOrder: 2,
  },
]

const sectorLabelByName: Record<WarehouseLocationSectorName, string> = {
  START: "начало",
  CENTER: "центр",
  END: "конец",
}

const sideLabelByName: Record<WarehouseLocationSideName, string> = {
  LEFT: "левая сторона",
  RIGHT: "правая сторона",
}

let locationNodesCache: WarehouseLocationNodeDto[] | null = null
let locationEdgesCache: WarehouseLocationEdgeDto[] | null = null

function delay<T>(data: T, timeout = 150): Promise<T> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(data), timeout)
  })
}

function emitLocationUpdated() {
  if (typeof window === "undefined") {
    return
  }

  window.dispatchEvent(new Event(WAREHOUSE_LOCATION_MOCK_UPDATED_EVENT))
}

function safeParseArray<T>(value: string | null): T[] | null {
  if (!value) {
    return null
  }

  try {
    const parsed = JSON.parse(value)

    if (!Array.isArray(parsed)) {
      return null
    }

    return parsed as T[]
  } catch {
    return null
  }
}

function readLocationNodes(): WarehouseLocationNodeDto[] {
  if (typeof window === "undefined") {
    return locationNodesCache ?? []
  }

  const storedItems = safeParseArray<WarehouseLocationNodeDto>(
    window.localStorage.getItem(WAREHOUSE_LOCATION_NODES_STORAGE_KEY)
  )

  if (storedItems) {
    locationNodesCache = storedItems
    return storedItems
  }

  locationNodesCache = []
  return []
}

function writeLocationNodes(
  nodes: WarehouseLocationNodeDto[],
  emitEvent = true
) {
  locationNodesCache = nodes

  if (typeof window === "undefined") {
    return
  }

  window.localStorage.setItem(
    WAREHOUSE_LOCATION_NODES_STORAGE_KEY,
    JSON.stringify(nodes)
  )

  if (emitEvent) {
    emitLocationUpdated()
  }
}

function readLocationEdges(): WarehouseLocationEdgeDto[] {
  if (typeof window === "undefined") {
    return locationEdgesCache ?? []
  }

  const storedItems = safeParseArray<WarehouseLocationEdgeDto>(
    window.localStorage.getItem(WAREHOUSE_LOCATION_EDGES_STORAGE_KEY)
  )

  if (storedItems) {
    locationEdgesCache = storedItems
    return storedItems
  }

  locationEdgesCache = []
  return []
}

function writeLocationEdges(
  edges: WarehouseLocationEdgeDto[],
  emitEvent = true
) {
  locationEdgesCache = edges

  if (typeof window === "undefined") {
    return
  }

  window.localStorage.setItem(
    WAREHOUSE_LOCATION_EDGES_STORAGE_KEY,
    JSON.stringify(edges)
  )

  if (emitEvent) {
    emitLocationUpdated()
  }
}

function getNodeId(warehouseId: string, code: string) {
  return `${warehouseId}:${code}`
}

function getEdgeId(fromNodeId: string, toNodeId: string) {
  return `${fromNodeId}->${toNodeId}`
}

function getDistanceLabel(weight: number) {
  if (weight === 0) {
    return "Та же позиция"
  }

  if (weight === 1) {
    return "Та же дорога / тот же сектор"
  }

  if (weight === 2 || weight === 3) {
    return "Та же дорога / соседний сектор"
  }

  if (weight === 4) {
    return "Соседняя дорога / тот же сектор"
  }

  return "Соседняя дорога / соседний сектор"
}

function buildNode(params: {
  warehouseId: string
  roadNumber: 1 | 2
  sector: LocationSpec
  sideNumber: 1 | 2
}): WarehouseLocationNodeDto {
  const sideName: WarehouseLocationSideName =
    params.sideNumber === 1 ? "LEFT" : "RIGHT"

  const code = `R${params.roadNumber}${params.sector.sectorCode}${params.sideNumber}`

  return {
    id: getNodeId(params.warehouseId, code),
    warehouseId: params.warehouseId,
    code,
    roadNumber: params.roadNumber,
    sectorCode: params.sector.sectorCode,
    sectorName: params.sector.sectorName,
    sectorOrder: params.sector.sectorOrder,
    sideNumber: params.sideNumber,
    sideName,
    displayName: `${params.roadNumber} дорога / ${
      sectorLabelByName[params.sector.sectorName]
    } / ${sideLabelByName[sideName]}`,
    active: true,
  }
}

export function generateDefaultNodes(
  warehouseId: string
): WarehouseLocationNodeDto[] {
  const nodes: WarehouseLocationNodeDto[] = []

  ;([1, 2] as const).forEach((roadNumber) => {
    locationSpecs.forEach((sector) => {
      ;([1, 2] as const).forEach((sideNumber) => {
        nodes.push(
          buildNode({
            warehouseId,
            roadNumber,
            sector,
            sideNumber,
          })
        )
      })
    })
  })

  return nodes
}

function isSameRoadNeighbor(
  fromNode: WarehouseLocationNodeDto,
  toNode: WarehouseLocationNodeDto
) {
  if (fromNode.roadNumber !== toNode.roadNumber) {
    return false
  }

  const sectorDiff = Math.abs(fromNode.sectorOrder - toNode.sectorOrder)

  return sectorDiff <= 1
}

function isCrossRoadNeighbor(
  fromNode: WarehouseLocationNodeDto,
  toNode: WarehouseLocationNodeDto
) {
  const isRoad1LeftToRoad2Right =
    fromNode.roadNumber === 1 &&
    fromNode.sideNumber === 1 &&
    toNode.roadNumber === 2 &&
    toNode.sideNumber === 2

  const isRoad2RightToRoad1Left =
    fromNode.roadNumber === 2 &&
    fromNode.sideNumber === 2 &&
    toNode.roadNumber === 1 &&
    toNode.sideNumber === 1

  if (!isRoad1LeftToRoad2Right && !isRoad2RightToRoad1Left) {
    return false
  }

  const sectorDiff = Math.abs(fromNode.sectorOrder - toNode.sectorOrder)

  return sectorDiff <= 1
}

function getDistanceWeight(
  fromNode: WarehouseLocationNodeDto,
  toNode: WarehouseLocationNodeDto
): number | null {
  if (fromNode.id === toNode.id) {
    return 0
  }

  if (isSameRoadNeighbor(fromNode, toNode)) {
    const sameSector = fromNode.sectorOrder === toNode.sectorOrder
    const sameSide = fromNode.sideNumber === toNode.sideNumber

    if (sameSector && !sameSide) {
      return 1
    }

    if (!sameSector && sameSide) {
      return 2
    }

    if (!sameSector && !sameSide) {
      return 3
    }
  }

  if (isCrossRoadNeighbor(fromNode, toNode)) {
    const sameSector = fromNode.sectorOrder === toNode.sectorOrder

    return sameSector ? 4 : 5
  }

  return null
}

function getEdgeType(
  fromNode: WarehouseLocationNodeDto,
  toNode: WarehouseLocationNodeDto
): WarehouseLocationEdgeType {
  if (fromNode.roadNumber === toNode.roadNumber) {
    return "SAME_ROAD"
  }

  return "CROSS_ROAD"
}

export function generateDefaultEdges(
  warehouseId: string,
  nodes: WarehouseLocationNodeDto[]
): WarehouseLocationEdgeDto[] {
  const warehouseNodes = nodes.filter((node) => {
    return node.warehouseId === warehouseId
  })

  const edges: WarehouseLocationEdgeDto[] = []

  warehouseNodes.forEach((fromNode) => {
    warehouseNodes.forEach((toNode) => {
      if (fromNode.id === toNode.id) {
        return
      }

      const distanceWeight = getDistanceWeight(fromNode, toNode)

      if (distanceWeight === null || distanceWeight === 0) {
        return
      }

      edges.push({
        id: getEdgeId(fromNode.id, toNode.id),
        warehouseId,
        fromNodeId: fromNode.id,
        toNodeId: toNode.id,
        distanceWeight,
        edgeType: getEdgeType(fromNode, toNode),
        active: true,
      })
    })
  })

  return edges
}

function mergeNodes(
  currentNodes: WarehouseLocationNodeDto[],
  nextNodes: WarehouseLocationNodeDto[]
) {
  const nodeByKey = new Map<string, WarehouseLocationNodeDto>()

  currentNodes.forEach((node) => {
    nodeByKey.set(`${node.warehouseId}:${node.code}`, node)
  })

  nextNodes.forEach((node) => {
    const key = `${node.warehouseId}:${node.code}`

    if (!nodeByKey.has(key)) {
      nodeByKey.set(key, node)
    }
  })

  return Array.from(nodeByKey.values())
}

function mergeEdges(
  currentEdges: WarehouseLocationEdgeDto[],
  nextEdges: WarehouseLocationEdgeDto[]
) {
  const edgeByKey = new Map<string, WarehouseLocationEdgeDto>()

  currentEdges.forEach((edge) => {
    edgeByKey.set(`${edge.fromNodeId}:${edge.toNodeId}`, edge)
  })

  nextEdges.forEach((edge) => {
    const key = `${edge.fromNodeId}:${edge.toNodeId}`

    if (!edgeByKey.has(key)) {
      edgeByKey.set(key, edge)
    }
  })

  return Array.from(edgeByKey.values())
}

export async function ensureDefaultGraphForWarehouse(
  warehouseId: string
): Promise<void> {
  const currentNodes = readLocationNodes()
  const generatedNodes = generateDefaultNodes(warehouseId)
  const nextNodes = mergeNodes(currentNodes, generatedNodes)

  writeLocationNodes(nextNodes, false)

  const warehouseNodes = nextNodes.filter((node) => {
    return node.warehouseId === warehouseId
  })

  const currentEdges = readLocationEdges()
  const generatedEdges = generateDefaultEdges(warehouseId, warehouseNodes)
  const nextEdges = mergeEdges(currentEdges, generatedEdges)

  writeLocationEdges(nextEdges, false)

  assignDefaultLocationNodesToRentalItems(warehouseId, warehouseNodes)

  emitLocationUpdated()

  return delay(undefined)
}

export function assignDefaultLocationNodesToRentalItems(
  warehouseId: string,
  nodes = readLocationNodes().filter((node) => node.warehouseId === warehouseId)
) {
  if (nodes.length === 0) {
    return
  }

  const sortedNodes = [...nodes].sort((left, right) => {
    return left.code.localeCompare(right.code, "ru", {
      numeric: true,
    })
  })

  const rentalItems = readRentalItems()

  let warehouseIndex = 0

  const nextRentalItems = rentalItems.map((item) => {
    if (item.warehouseId !== warehouseId) {
      return item
    }

    if (item.locationNodeId) {
      return item
    }

    const node = sortedNodes[warehouseIndex % sortedNodes.length]
    warehouseIndex += 1

    return {
      ...item,
      locationNodeId: node.id,
    }
  })

  writeRentalItems(nextRentalItems)
}

export async function getWarehouseLocationNodes(
  warehouseId: string
): Promise<WarehouseLocationNodeDto[]> {
  await ensureDefaultGraphForWarehouse(warehouseId)

  const nodes = readLocationNodes()
    .filter((node) => node.warehouseId === warehouseId && node.active)
    .sort((left, right) => {
      return left.code.localeCompare(right.code, "ru", {
        numeric: true,
      })
    })

  return delay(nodes)
}

export async function getWarehouseLocationNode(
  warehouseId: string,
  nodeId: string
): Promise<WarehouseLocationNodeDto | null> {
  await ensureDefaultGraphForWarehouse(warehouseId)

  const node = readLocationNodes().find((item) => {
    return item.warehouseId === warehouseId && item.id === nodeId
  })

  return delay(node ?? null)
}

export async function findNearbyNodes(
  locationNodeId: string
): Promise<NearbyLocationNodeDto[]> {
  const nodes = readLocationNodes()
  const edges = readLocationEdges()

  const sourceNode = nodes.find((node) => {
    return node.id === locationNodeId
  })

  if (!sourceNode) {
    return delay([])
  }

  const nearbyNodes = edges
    .filter((edge) => {
      return edge.fromNodeId === locationNodeId && edge.active
    })
    .map((edge) => {
      const node = nodes.find((item) => item.id === edge.toNodeId)

      if (!node) {
        return null
      }

      return {
        node,
        distanceWeight: edge.distanceWeight,
        distanceLabel: getDistanceLabel(edge.distanceWeight),
      }
    })
    .filter((item): item is NearbyLocationNodeDto => item !== null)
    .sort((left, right) => {
      return left.distanceWeight - right.distanceWeight
    })

  return delay(nearbyNodes)
}

export async function getDistanceBetweenNodes(params: {
  warehouseId: string
  targetLocationNodeId: string
  sourceLocationNodeId: string
}): Promise<{
  distanceWeight: number
  distanceLabel: string
} | null> {
  await ensureDefaultGraphForWarehouse(params.warehouseId)

  if (params.targetLocationNodeId === params.sourceLocationNodeId) {
    return delay({
      distanceWeight: 0,
      distanceLabel: getDistanceLabel(0),
    })
  }

  const edge = readLocationEdges().find((item) => {
    return (
      item.warehouseId === params.warehouseId &&
      item.fromNodeId === params.targetLocationNodeId &&
      item.toNodeId === params.sourceLocationNodeId &&
      item.active
    )
  })

  if (!edge) {
    return delay(null)
  }

  return delay({
    distanceWeight: edge.distanceWeight,
    distanceLabel: getDistanceLabel(edge.distanceWeight),
  })
}

export async function isNearbyOrSameLocation(params: {
  warehouseId: string
  targetLocationNodeId: string
  sourceLocationNodeId: string
}): Promise<boolean> {
  const distance = await getDistanceBetweenNodes(params)

  return distance !== null
}
