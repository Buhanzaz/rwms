import type { RepairEstimateCatalogRequest } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

type CanvasPosition = { x: number; y: number }
type CanvasAnchor = "TOP" | "BOTTOM"
type LinkAnchors = { source: CanvasAnchor; target: CanvasAnchor }

type CatalogLayout = {
  nodePositions: Record<string, CanvasPosition>
  linkAnchors: Record<string, LinkAnchors>
}

const STORAGE_PREFIX = "rwms:repair-estimate-catalog-layout:v1:"
const EMPTY_LAYOUT: CatalogLayout = { nodePositions: {}, linkAnchors: {} }

function storageKey(request: RepairEstimateCatalogRequest) {
  return `${STORAGE_PREFIX}${request.warehouseId}:${request.catalogVersionId}`
}

function isPosition(value: unknown): value is CanvasPosition {
  return (
    typeof value === "object" &&
    value !== null &&
    Number.isFinite((value as CanvasPosition).x) &&
    Number.isFinite((value as CanvasPosition).y)
  )
}

function isAnchor(value: unknown): value is CanvasAnchor {
  return value === "TOP" || value === "BOTTOM"
}

function readLayout(request: RepairEstimateCatalogRequest): CatalogLayout {
  if (typeof window === "undefined") return EMPTY_LAYOUT

  let raw: string | null
  try {
    raw = window.localStorage.getItem(storageKey(request))
  } catch {
    return EMPTY_LAYOUT
  }
  if (!raw) return EMPTY_LAYOUT

  try {
    const parsed = JSON.parse(raw) as Partial<CatalogLayout>
    const nodePositions = Object.fromEntries(
      Object.entries(parsed.nodePositions ?? {}).filter(([, value]) =>
        isPosition(value)
      )
    )
    const linkAnchors = Object.fromEntries(
      Object.entries(parsed.linkAnchors ?? {}).filter(
        ([, value]) =>
          typeof value === "object" &&
          value !== null &&
          isAnchor((value as LinkAnchors).source) &&
          isAnchor((value as LinkAnchors).target)
      )
    )
    return { nodePositions, linkAnchors }
  } catch {
    return EMPTY_LAYOUT
  }
}

function writeLayout(
  request: RepairEstimateCatalogRequest,
  layout: CatalogLayout
) {
  if (typeof window === "undefined") return
  try {
    window.localStorage.setItem(storageKey(request), JSON.stringify(layout))
  } catch {
    // Canvas layout is a non-authoritative preference; catalog commands stay successful.
  }
}

export function getRepairEstimateCatalogLayout(
  request: RepairEstimateCatalogRequest
) {
  return readLayout(request)
}

export function saveRepairEstimateCatalogNodePosition(
  request: RepairEstimateCatalogRequest,
  nodeId: string,
  position: CanvasPosition
) {
  const layout = readLayout(request)
  writeLayout(request, {
    ...layout,
    nodePositions: { ...layout.nodePositions, [nodeId]: position },
  })
  return position
}

export function saveRepairEstimateCatalogLinkAnchors(
  request: RepairEstimateCatalogRequest,
  linkId: string,
  anchors: LinkAnchors
) {
  const layout = readLayout(request)
  writeLayout(request, {
    ...layout,
    linkAnchors: { ...layout.linkAnchors, [linkId]: anchors },
  })
}

export function deleteRepairEstimateCatalogLayoutLink(
  request: RepairEstimateCatalogRequest,
  linkId: string
) {
  const layout = readLayout(request)
  const linkAnchors = { ...layout.linkAnchors }
  delete linkAnchors[linkId]
  writeLayout(request, { ...layout, linkAnchors })
}
