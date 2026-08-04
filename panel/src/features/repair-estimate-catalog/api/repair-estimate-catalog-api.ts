import type { RepairEstimateCatalogClient } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-client"
import { httpRepairEstimateCatalogClient } from "@/features/repair-estimate-catalog/api/http-repair-estimate-catalog-client"
import type {
  RepairEstimateCatalogEffectiveQueueBinding,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogRouteQueueKind,
  RepairEstimateCatalogSnapshotDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"

const catalogClient: RepairEstimateCatalogClient =
  httpRepairEstimateCatalogClient

/**
 * Stable query-key prefix shared by settings and operational estimate screens.
 * Prefix invalidation refreshes every maintenance-service catalog projection.
 */
export const REPAIR_ESTIMATE_CATALOG_QUERY_KEY = ["estimate-catalog"] as const

const EMPTY_NODES: readonly RepairEstimateCatalogNodeDto[] = Object.freeze([])

function compareNodes(
  left: RepairEstimateCatalogNodeDto,
  right: RepairEstimateCatalogNodeDto
) {
  return (
    left.name.localeCompare(right.name, "ru") || left.id.localeCompare(right.id)
  )
}

function compareMainMenuNodes(
  left: RepairEstimateCatalogNodeDto,
  right: RepairEstimateCatalogNodeDto
) {
  return compareNodes(left, right)
}

function compareLinkOrder(
  left: RepairEstimateCatalogLinkDto,
  right: RepairEstimateCatalogLinkDto
) {
  return left.sortOrder - right.sortOrder
}

function compareLinks(
  left: RepairEstimateCatalogLinkDto,
  right: RepairEstimateCatalogLinkDto
) {
  const orderResult = compareLinkOrder(left, right)
  if (orderResult !== 0) {
    return orderResult
  }

  return left.id.localeCompare(right.id)
}

function addToGroup<T>(map: Map<string, T[]>, key: string, value: T) {
  const group = map.get(key)
  if (group) {
    group.push(value)
    return
  }

  map.set(key, [value])
}

function freezeNodeGroups(groups: Map<string, RepairEstimateCatalogNodeDto[]>) {
  const result = new Map<string, readonly RepairEstimateCatalogNodeDto[]>()
  for (const [key, nodes] of groups) {
    result.set(key, Object.freeze(nodes.slice().sort(compareNodes)))
  }
  return result as ReadonlyMap<string, readonly RepairEstimateCatalogNodeDto[]>
}

function freezeLinkGroups(
  groups: Map<string, RepairEstimateCatalogLinkDto[]>,
  compare = compareLinks
) {
  const result = new Map<string, readonly RepairEstimateCatalogLinkDto[]>()
  for (const [key, links] of groups) {
    result.set(key, Object.freeze(links.slice().sort(compare)))
  }
  return result as ReadonlyMap<string, readonly RepairEstimateCatalogLinkDto[]>
}

function linkedNodes(
  links: readonly RepairEstimateCatalogLinkDto[],
  nodesById: ReadonlyMap<string, RepairEstimateCatalogNodeDto>
) {
  const seen = new Set<string>()
  const nodes: RepairEstimateCatalogNodeDto[] = []

  for (const link of links) {
    if (seen.has(link.targetNodeId)) {
      continue
    }

    const node = nodesById.get(link.targetNodeId)
    if (node) {
      seen.add(node.id)
      nodes.push(node)
    }
  }

  return Object.freeze(nodes)
}

export type RepairEstimateCatalogIndex = {
  nodesById: ReadonlyMap<string, RepairEstimateCatalogNodeDto>
  furnitureNodeIds: ReadonlySet<string>
  activeRootNodes: readonly RepairEstimateCatalogNodeDto[]
  activeMainMenuNodes: readonly RepairEstimateCatalogNodeDto[]
  operationalMenuNodes: readonly RepairEstimateCatalogNodeDto[]
  operationalEstimateNodes: readonly RepairEstimateCatalogNodeDto[]
  childrenByParentId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >
  followUpLinksBySourceNodeId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogLinkDto[]
  >
  followUpNodesBySourceNodeId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >
  dependencyLinksBySourceNodeId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogLinkDto[]
  >
  dependencyNodesBySourceNodeId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >
  dependencyRelatedNodesByNodeId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >
  incomingGraphParentNodesByNodeId: ReadonlyMap<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >
  effectiveRouteQueueKindByNodeId: ReadonlyMap<
    string,
    RepairEstimateCatalogRouteQueueKind | null
  >
  effectiveQueueBindingByNodeId: ReadonlyMap<
    string,
    RepairEstimateCatalogEffectiveQueueBinding | null
  >
  getChildren: (parentId: string) => readonly RepairEstimateCatalogNodeDto[]
  getFollowUpNodes: (
    sourceNodeId: string
  ) => readonly RepairEstimateCatalogNodeDto[]
  getDependencyNodes: (
    sourceNodeId: string
  ) => readonly RepairEstimateCatalogNodeDto[]
  getDependencyRelatedNodes: (
    nodeId: string
  ) => readonly RepairEstimateCatalogNodeDto[]
  getEffectiveRouteQueueKind: (
    nodeId: string
  ) => RepairEstimateCatalogRouteQueueKind | null
  getEffectiveQueueBinding: (
    nodeId: string
  ) => RepairEstimateCatalogEffectiveQueueBinding | null
  isFurnitureNode: (nodeId: string) => boolean
}

function belongsToFurnitureTree(
  node: RepairEstimateCatalogNodeDto,
  nodesById: ReadonlyMap<string, RepairEstimateCatalogNodeDto>
) {
  const visited = new Set<string>()
  let current: RepairEstimateCatalogNodeDto | undefined = node

  while (current) {
    if (current.furnitureCategory) {
      return true
    }
    if (!current.parentId || visited.has(current.id)) {
      return false
    }
    visited.add(current.id)
    current = nodesById.get(current.parentId)
  }

  return false
}

export function filterRepairEstimateCatalogNodesForUsage(
  nodes: readonly RepairEstimateCatalogNodeDto[],
  catalog: Pick<RepairEstimateCatalogIndex, "isFurnitureNode">,
  excludeFurniture: boolean
) {
  return nodes.filter((node) => {
    const furniture = catalog.isFurnitureNode(node.id)
    if (excludeFurniture && furniture) return false
    return !(
      furniture &&
      node.nodeType === "MATERIAL" &&
      node.furnitureEquipment === null
    )
  })
}

/**
 * Pure operational projection of the catalog DTO. It never reads or writes
 * persistence and keeps parent hierarchy independent from graph links.
 */
export function createRepairEstimateCatalogIndex(
  snapshot: RepairEstimateCatalogSnapshotDto
): RepairEstimateCatalogIndex {
  const allNodesById = new Map(snapshot.nodes.map((node) => [node.id, node]))
  const furnitureNodeIds = new Set(
    snapshot.nodes
      .filter((node) => belongsToFurnitureTree(node, allNodesById))
      .map((node) => node.id)
  )
  const activeNodes = snapshot.nodes.filter((node) => node.active)
  const nodesById = new Map(activeNodes.map((node) => [node.id, node]))
  const activeNodeIds = new Set(nodesById.keys())
  const activeRootNodes = Object.freeze(
    activeNodes
      .filter((node) => node.nodeType === "CATEGORY" && node.parentId === null)
      .sort(compareNodes)
  )
  const activeMainMenuNodes = Object.freeze(
    activeNodes.filter((node) => node.showInMainMenu).sort(compareMainMenuNodes)
  )
  const operationalMenuNodes =
    activeMainMenuNodes.length > 0 ? activeMainMenuNodes : activeRootNodes
  const operationalEstimateNodes = Object.freeze(
    activeNodes
      .filter(
        (node) =>
          node.includeInEstimate &&
          (node.nodeType === "WORK" ||
            node.nodeType === "MATERIAL" ||
            node.nodeType === "OPTION")
      )
      .sort(compareNodes)
  )

  const children = new Map<string, RepairEstimateCatalogNodeDto[]>()
  for (const node of activeNodes) {
    if (node.parentId && activeNodeIds.has(node.parentId)) {
      addToGroup(children, node.parentId, node)
    }
  }
  const childrenByParentId = freezeNodeGroups(children)

  const activeLinks = snapshot.links.filter(
    (link) =>
      activeNodeIds.has(link.sourceNodeId) &&
      activeNodeIds.has(link.targetNodeId)
  )
  const followUpLinks = new Map<string, RepairEstimateCatalogLinkDto[]>()
  const dependencyLinks = new Map<string, RepairEstimateCatalogLinkDto[]>()
  const incomingGraphLinks = new Map<string, RepairEstimateCatalogLinkDto[]>()

  for (const link of activeLinks) {
    addToGroup(incomingGraphLinks, link.targetNodeId, link)
    addToGroup(
      link.linkType === "FOLLOW_UP" ? followUpLinks : dependencyLinks,
      link.sourceNodeId,
      link
    )
  }

  const followUpLinksBySourceNodeId = freezeLinkGroups(followUpLinks)
  const dependencyLinksBySourceNodeId = freezeLinkGroups(dependencyLinks)
  const incomingGraphLinksByNodeId = freezeLinkGroups(
    incomingGraphLinks,
    (left, right) => {
      const orderResult = compareLinkOrder(left, right)
      if (orderResult !== 0) {
        return orderResult
      }

      const leftSource = nodesById.get(left.sourceNodeId)
      const rightSource = nodesById.get(right.sourceNodeId)
      if (leftSource && rightSource) {
        const sourceResult = compareNodes(leftSource, rightSource)
        if (sourceResult !== 0) {
          return sourceResult
        }
      }

      return left.id.localeCompare(right.id)
    }
  )
  const followUpNodesBySourceNodeId = new Map<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >()
  const dependencyNodesBySourceNodeId = new Map<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >()

  for (const [sourceId, links] of followUpLinksBySourceNodeId) {
    followUpNodesBySourceNodeId.set(sourceId, linkedNodes(links, nodesById))
  }
  for (const [sourceId, links] of dependencyLinksBySourceNodeId) {
    dependencyNodesBySourceNodeId.set(sourceId, linkedNodes(links, nodesById))
  }

  const dependencyRelated = new Map<string, Set<string>>()
  for (const link of activeLinks) {
    if (link.linkType !== "DEPENDENCY") {
      continue
    }

    const sourceRelated = dependencyRelated.get(link.sourceNodeId) ?? new Set()
    sourceRelated.add(link.targetNodeId)
    dependencyRelated.set(link.sourceNodeId, sourceRelated)

    const targetRelated = dependencyRelated.get(link.targetNodeId) ?? new Set()
    targetRelated.add(link.sourceNodeId)
    dependencyRelated.set(link.targetNodeId, targetRelated)
  }
  const dependencyRelatedNodesByNodeId = new Map<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >()
  for (const [nodeId, relatedIds] of dependencyRelated) {
    dependencyRelatedNodesByNodeId.set(
      nodeId,
      Object.freeze(
        Array.from(relatedIds)
          .map((relatedId) => nodesById.get(relatedId))
          .filter(
            (node): node is RepairEstimateCatalogNodeDto => node !== undefined
          )
          .sort(compareNodes)
      )
    )
  }

  const incomingGraphParentNodesByNodeId = new Map<
    string,
    readonly RepairEstimateCatalogNodeDto[]
  >()
  for (const [nodeId, links] of incomingGraphLinksByNodeId) {
    const parents = links
      .map((link) => nodesById.get(link.sourceNodeId))
      .filter(
        (node): node is RepairEstimateCatalogNodeDto => node !== undefined
      )
    incomingGraphParentNodesByNodeId.set(nodeId, Object.freeze(parents))
  }

  type QueueBindingCandidate = {
    sourceNodeId: string
    binding: RepairEstimateCatalogEffectiveQueueBinding
  }

  function comparePredecessors(
    left: RepairEstimateCatalogNodeDto,
    right: RepairEstimateCatalogNodeDto,
    parentId: string | null
  ) {
    // The explicit hierarchy is considered before graph predecessors.  The
    // resolver still visits every predecessor; this only makes the result
    // (including the selected display name when the same queue is reachable
    // by several paths) deterministic.
    const leftIsExplicitParent = left.id === parentId ? 0 : 1
    const rightIsExplicitParent = right.id === parentId ? 0 : 1
    return (
      leftIsExplicitParent - rightIsExplicitParent || compareNodes(left, right)
    )
  }

  function bindingKey(binding: RepairEstimateCatalogEffectiveQueueBinding) {
    return `${binding.queueKind ?? ""}:${binding.queueId ?? ""}`
  }

  function mergeQueueBindingCandidates(
    candidates: readonly QueueBindingCandidate[]
  ): RepairEstimateCatalogEffectiveQueueBinding | null {
    const unique = new Map<string, QueueBindingCandidate>()

    for (const candidate of candidates) {
      const key = bindingKey(candidate.binding)
      const existing = unique.get(key)
      if (!existing) {
        unique.set(key, candidate)
        continue
      }

      // A queue id and kind are the stable identity of a binding.  Names are
      // presentation data and may be stale in an old snapshot; prefer the
      // first deterministic value, but use a non-empty name when available.
      if (!existing.binding.queueName && candidate.binding.queueName) {
        unique.set(key, {
          ...existing,
          binding: {
            ...existing.binding,
            queueName: candidate.binding.queueName,
          },
        })
      }
    }

    if (unique.size !== 1) {
      // A graph with two different routes is ambiguous.  Failing closed is
      // safer than silently putting a task into an arbitrary queue.
      return null
    }

    return unique.values().next().value?.binding ?? null
  }

  function resolveEffectiveQueueBinding(
    nodeId: string
  ): RepairEstimateCatalogEffectiveQueueBinding | null {
    const memoized = new Map<string, readonly QueueBindingCandidate[]>()
    const resolving = new Set<string>()

    function collectReachableBindings(
      currentNodeId: string
    ): readonly QueueBindingCandidate[] {
      const cached = memoized.get(currentNodeId)
      if (cached) {
        return cached
      }

      // A cycle is invalid for inheritance, but it must not make the whole
      // catalog projection recurse forever.  The other branches remain
      // usable and are still collected by the caller.
      if (resolving.has(currentNodeId)) {
        return Object.freeze([])
      }

      const current = nodesById.get(currentNodeId)
      if (!current) {
        return Object.freeze([])
      }

      resolving.add(currentNodeId)
      const candidates: QueueBindingCandidate[] = []
      const queueId = current.queueDefinitionId?.trim() || null
      if (queueId && current.routeQueueKind) {
        const result = Object.freeze([
          {
            sourceNodeId: current.id,
            binding: {
              queueId,
              queueName: current.queueDefinitionName?.trim() || null,
              queueKind: current.routeQueueKind,
            },
          },
        ])
        resolving.delete(currentNodeId)
        memoized.set(currentNodeId, result)
        return result
      }

      const predecessorIds = new Set<string>()
      if (current.parentId && nodesById.has(current.parentId)) {
        predecessorIds.add(current.parentId)
      }
      for (const predecessor of incomingGraphParentNodesByNodeId.get(
        current.id
      ) ?? EMPTY_NODES) {
        predecessorIds.add(predecessor.id)
      }

      const predecessors = Array.from(predecessorIds)
        .map((predecessorId) => nodesById.get(predecessorId))
        .filter(
          (predecessor): predecessor is RepairEstimateCatalogNodeDto =>
            predecessor !== undefined
        )
        .sort((left, right) =>
          comparePredecessors(left, right, current.parentId)
        )

      for (const predecessor of predecessors) {
        candidates.push(...collectReachableBindings(predecessor.id))
      }

      resolving.delete(currentNodeId)

      // Keep one deterministic candidate for each queue identity while
      // retaining all distinct identities for the fail-closed check.
      const unique = new Map<string, QueueBindingCandidate>()
      for (const candidate of candidates) {
        const key = bindingKey(candidate.binding)
        const existing = unique.get(key)
        if (!existing) {
          unique.set(key, candidate)
          continue
        }
        if (!existing.binding.queueName && candidate.binding.queueName) {
          unique.set(key, {
            ...existing,
            binding: {
              ...existing.binding,
              queueName: candidate.binding.queueName,
            },
          })
        }
      }

      const result = Object.freeze(Array.from(unique.values()))
      memoized.set(currentNodeId, result)
      return result
    }

    return mergeQueueBindingCandidates(collectReachableBindings(nodeId))
  }

  const effectiveQueueBindingByNodeId = new Map<
    string,
    RepairEstimateCatalogEffectiveQueueBinding | null
  >()
  const effectiveRouteQueueKindByNodeId = new Map<
    string,
    RepairEstimateCatalogRouteQueueKind | null
  >()
  for (const node of activeNodes) {
    const binding = resolveEffectiveQueueBinding(node.id)
    effectiveQueueBindingByNodeId.set(node.id, binding)
    effectiveRouteQueueKindByNodeId.set(node.id, binding?.queueKind ?? null)
  }

  return {
    nodesById,
    furnitureNodeIds,
    activeRootNodes,
    activeMainMenuNodes,
    operationalMenuNodes,
    operationalEstimateNodes,
    childrenByParentId,
    followUpLinksBySourceNodeId,
    followUpNodesBySourceNodeId,
    dependencyLinksBySourceNodeId,
    dependencyNodesBySourceNodeId,
    dependencyRelatedNodesByNodeId,
    incomingGraphParentNodesByNodeId,
    effectiveRouteQueueKindByNodeId,
    effectiveQueueBindingByNodeId,
    getChildren: (parentId) => childrenByParentId.get(parentId) ?? EMPTY_NODES,
    getFollowUpNodes: (sourceNodeId) =>
      followUpNodesBySourceNodeId.get(sourceNodeId) ?? EMPTY_NODES,
    getDependencyNodes: (sourceNodeId) =>
      dependencyNodesBySourceNodeId.get(sourceNodeId) ?? EMPTY_NODES,
    getDependencyRelatedNodes: (nodeId) =>
      dependencyRelatedNodesByNodeId.get(nodeId) ?? EMPTY_NODES,
    getEffectiveRouteQueueKind: (nodeId) =>
      effectiveRouteQueueKindByNodeId.get(nodeId) ?? null,
    getEffectiveQueueBinding: (nodeId) =>
      effectiveQueueBindingByNodeId.get(nodeId) ?? null,
    isFurnitureNode: (nodeId) => furnitureNodeIds.has(nodeId),
  }
}

/**
 * Service-client boundary for operational estimate screens.
 */
export async function getOperationalRepairEstimateCatalog(
  warehouseId?: string | null
): Promise<RepairEstimateCatalogSnapshotDto> {
  return catalogClient.getOperationalCatalog(warehouseId)
}
