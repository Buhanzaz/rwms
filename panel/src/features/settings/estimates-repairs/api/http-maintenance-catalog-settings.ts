import {
  listMaintenanceCatalogLinks,
  listMaintenanceCatalogNodes,
  listMaintenanceCatalogVersions,
  replaceMaintenanceCatalogLinks,
  replaceMaintenanceCatalogNodes,
  type MaintenanceCatalogLink,
  type MaintenanceCatalogNode,
} from "@/features/maintenance/api/maintenance-api"
import { canonicalClientUuid } from "@/features/maintenance/maintenance-runtime"
import type {
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogSnapshotDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

async function draft(warehouseId: string) {
  if (!warehouseId.trim()) throw new Error("Не выбран склад каталога ремонта.")
  const versions = await listMaintenanceCatalogVersions(warehouseId, "DRAFT")
  const value = versions.items
    .filter((version) => version.warehouseId === warehouseId)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
  if (!value) {
    throw new Error(
      "Для выбранного склада нет черновика каталога. Сначала импортируйте проверенный снимок каталога."
    )
  }
  return value
}

function settingsNode(
  node: MaintenanceCatalogNode,
  byId: Map<string, MaintenanceCatalogNode>
): RepairEstimateCatalogNodeDto {
  return {
    id: node.id,
    code: node.code,
    name: node.name,
    nodeType: node.nodeType,
    parentId: node.parentNodeId,
    parentCode: node.parentNodeId
      ? (byId.get(node.parentNodeId)?.code ?? null)
      : null,
    active: node.active,
    sortOrder: null,
    unit: node.unit,
    unitPrice: node.unitPrice,
    defaultQuantity: 1,
    durationMinutes: node.durationMinutes,
    additionalOption: node.nodeType === "OPTION",
    showInMainMenu: node.showInMainMenu,
    mainMenuOrder: null,
    mainMenuTitle: null,
    routeQueueKind:
      node.routing?.queueKind === "MOVEMENT" ||
      node.routing?.queueKind === "REPAIR" ||
      node.routing?.queueKind === "HOLDING"
        ? node.routing.queueKind
        : null,
    workQueueCode: node.routing?.queueCode ?? null,
    photoRequired: node.photoRequired,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    furnitureCategory: false,
    canvasX: null,
    canvasY: null,
    comment: node.comment,
  }
}

function settingsLink(
  link: MaintenanceCatalogLink
): RepairEstimateCatalogLinkDto {
  return {
    id: link.id,
    sourceNodeId: link.fromNodeId,
    targetNodeId: link.toNodeId,
    linkType: link.linkType,
    active: true,
    sortOrder: link.sortOrder,
    comment: null,
  }
}

async function state(warehouseId: string) {
  const version = await draft(warehouseId)
  const [nodes, links] = await Promise.all([
    listMaintenanceCatalogNodes(warehouseId, version.id),
    listMaintenanceCatalogLinks(warehouseId, version.id),
  ])
  return { version, nodes, links }
}

export async function getHttpMaintenanceCatalogSnapshot(
  warehouseId: string
): Promise<RepairEstimateCatalogSnapshotDto> {
  const current = await state(warehouseId)
  const byId = new Map(current.nodes.map((node) => [node.id, node]))
  return {
    nodes: current.nodes.map((node) => settingsNode(node, byId)),
    links: current.links.map(settingsLink),
    seedMeta: {
      nodeCount: current.version.counts.nodes,
      linkCount: current.version.counts.links,
      note: current.version.sourceSha256,
    },
  }
}

async function nodeInput(
  input: RepairEstimateCatalogNodeMutation,
  existing?: MaintenanceCatalogNode
) {
  const code = input.code.trim().toUpperCase()
  if (!/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(code)) {
    throw new Error("Код должен быть задан латиницей, цифрами, _ или -.")
  }
  if (
    input.routeQueueKind &&
    (!existing?.routing || existing.routing.queueKind !== input.routeQueueKind)
  ) {
    throw new Error(
      "Новую привязку очереди нужно выбрать по каноническому идентификатору очереди."
    )
  }
  return {
    id:
      existing?.id ??
      (await canonicalClientUuid(input.id ?? `catalog:${code}`)),
    code,
    nodeType: input.nodeType,
    name: input.name.trim(),
    active: input.active,
    parentNodeId: input.parentId,
    unit: input.unit,
    unitPrice: input.unitPrice,
    durationMinutes: input.durationMinutes ?? 0,
    includeInEstimate: input.includeInEstimate,
    commonItem: input.commonItem,
    showInMainMenu: input.showInMainMenu,
    photoRequired: input.photoRequired,
    routing: input.routeQueueKind ? (existing?.routing ?? null) : null,
    references: existing?.references ?? [],
    comment: input.comment,
    mediaReferences: existing?.mediaReferences ?? [],
  }
}

function unchangedNode(node: MaintenanceCatalogNode) {
  return {
    id: node.id,
    code: node.code,
    nodeType: node.nodeType,
    name: node.name,
    active: node.active,
    parentNodeId: node.parentNodeId,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    showInMainMenu: node.showInMainMenu,
    photoRequired: node.photoRequired,
    routing: node.routing,
    references: node.references,
    comment: node.comment,
    mediaReferences: node.mediaReferences,
  }
}

export async function saveHttpMaintenanceCatalogNode(
  warehouseId: string,
  input: RepairEstimateCatalogNodeMutation
) {
  const current = await state(warehouseId)
  const existing = input.id
    ? current.nodes.find((node) => node.id === input.id)
    : undefined
  const changed = await nodeInput(input, existing)
  const nodes = existing
    ? current.nodes.map((node) =>
        node.id === existing.id ? changed : unchangedNode(node)
      )
    : [...current.nodes.map(unchangedNode), changed]
  await replaceMaintenanceCatalogNodes(
    warehouseId,
    current.version.id,
    current.version.version,
    nodes
  )
  const materialized: MaintenanceCatalogNode = {
    ...changed,
    catalogVersionId: current.version.id,
  }
  return settingsNode(
    materialized,
    new Map([...current.nodes, materialized].map((node) => [node.id, node]))
  )
}

export async function deleteHttpMaintenanceCatalogNode(
  warehouseId: string,
  id: string
) {
  const current = await state(warehouseId)
  await replaceMaintenanceCatalogNodes(
    warehouseId,
    current.version.id,
    current.version.version,
    current.nodes.filter((node) => node.id !== id).map(unchangedNode)
  )
}

function unchangedLink(link: MaintenanceCatalogLink) {
  return {
    id: link.id,
    fromNodeId: link.fromNodeId,
    toNodeId: link.toNodeId,
    linkType: link.linkType,
    sortOrder: link.sortOrder,
  }
}

export async function saveHttpMaintenanceCatalogLink(
  warehouseId: string,
  input: RepairEstimateCatalogLinkMutation
) {
  const current = await state(warehouseId)
  const id =
    input.id ??
    (await canonicalClientUuid(
      `catalog-link:${input.sourceNodeId}:${input.targetNodeId}:${input.linkType}`
    ))
  const changed = {
    id,
    fromNodeId: input.sourceNodeId,
    toNodeId: input.targetNodeId,
    linkType: input.linkType,
    sortOrder: input.sortOrder ?? 0,
  }
  const links = current.links.some((link) => link.id === id)
    ? current.links.map((link) =>
        link.id === id ? changed : unchangedLink(link)
      )
    : [...current.links.map(unchangedLink), changed]
  await replaceMaintenanceCatalogLinks(
    warehouseId,
    current.version.id,
    current.version.version,
    links
  )
  return settingsLink({ ...changed, catalogVersionId: current.version.id })
}

export async function deleteHttpMaintenanceCatalogLink(
  warehouseId: string,
  id: string
) {
  const current = await state(warehouseId)
  await replaceMaintenanceCatalogLinks(
    warehouseId,
    current.version.id,
    current.version.version,
    current.links.filter((link) => link.id !== id).map(unchangedLink)
  )
}
