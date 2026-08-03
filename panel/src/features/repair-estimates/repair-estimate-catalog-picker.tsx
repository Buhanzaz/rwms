import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
} from "react"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowLeftDoubleIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import {
  REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
  createRepairEstimateCatalogIndex,
  filterRepairEstimateCatalogNodesForUsage,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogNodeDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import {
  applyCatalogNodesToEstimateLines,
  catalogEstimateLineDescription,
  getRepairEstimateCatalogQuantityError,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import type {
  ReadyMediaReference,
  ServiceMediaOwner,
} from "@/features/media/media-service"
import { WorkLinePhotoControls } from "@/features/repair-estimates/work-line-photo-controls"

type CatalogMode = "LINKED_SET" | "WORKS_ONLY" | "MATERIALS_ONLY"

type AddContext = {
  nodes: RepairEstimateCatalogNodeDto[]
  quantityNode: RepairEstimateCatalogNodeDto
  locationTitle: string | null
  targetWorkLineIdsByCatalogNodeId: Record<string, string | null>
}

type DuplicateCatalogWorkContext = {
  addContext: Omit<AddContext, "targetWorkLineIdsByCatalogNodeId">
  choices: Array<{
    node: RepairEstimateCatalogNodeDto
    existingLines: RepairEstimateLineDto[]
  }>
  choiceIndex: number
  targetWorkLineIdsByCatalogNodeId: Record<string, string | null>
}

type CatalogBreadcrumb = {
  key: string
  label: string
  pathLength: number
}

type RepairEstimateCatalogPickerProps = {
  lines: RepairEstimateLineDto[]
  readOnly: boolean
  onChange: (lines: RepairEstimateLineDto[]) => void
  onPagerChange?: (pager: RepairEstimateCatalogPager | null) => void
  excludeFurniture?: boolean
  accessToken?: string | null
  mediaOwner?: ServiceMediaOwner | null
  ensureMediaOwner?: (
    lines?: RepairEstimateLineDto[]
  ) => Promise<ServiceMediaOwner>
}

export type RepairEstimateCatalogPager = {
  canGoBack: boolean
  canGoForward: boolean
  goBack: () => void
  goForward: () => void
}

const CATALOG_PAGE_SIZE = 9
const DISPLAY_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/
const COMMON_CATALOG_SECTION_NAME = "общее"

function normalizedDisplayColor(value: string | null | undefined) {
  if (value === null || value === undefined) {
    return null
  }

  const normalized = value.trim()
  return DISPLAY_COLOR_PATTERN.test(normalized) ? normalized : null
}

function readableCatalogForeground(color: string) {
  const red = Number.parseInt(color.slice(1, 3), 16) / 255
  const green = Number.parseInt(color.slice(3, 5), 16) / 255
  const blue = Number.parseInt(color.slice(5, 7), 16) / 255
  const linear = [red, green, blue].map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  )
  const luminance = 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

  return luminance > 0.42 ? "#111827" : "#FFFFFF"
}

function catalogNodeColorStyle(
  node: RepairEstimateCatalogNodeDto
): CSSProperties | undefined {
  const color = normalizedDisplayColor(node.displayColor)
  if (color === null) {
    return undefined
  }

  return {
    backgroundColor: color,
    borderColor: color,
    color: readableCatalogForeground(color),
  }
}

function uniqueNodes(nodes: readonly RepairEstimateCatalogNodeDto[]) {
  return Array.from(new Map(nodes.map((node) => [node.id, node])).values())
}

function normalizedCatalogName(value: string) {
  return value.trim().toLocaleLowerCase("ru")
}

function isCommonCatalogSection(node: RepairEstimateCatalogNodeDto) {
  return (
    node.nodeType === "CATEGORY" &&
    normalizedCatalogName(node.name) === COMMON_CATALOG_SECTION_NAME
  )
}

function commonCatalogEstimateNodes(
  catalog: Pick<
    ReturnType<typeof createRepairEstimateCatalogIndex>,
    "operationalEstimateNodes"
  >
) {
  return uniqueNodes(
    catalog.operationalEstimateNodes.filter(
      (node) =>
        node.commonItem &&
        (node.nodeType === "WORK" || node.nodeType === "MATERIAL")
    )
  )
}

/**
 * The canvas keeps every block of a category under the category's parent ID so
 * it can be edited together. The arrows, rather than that flat canvas
 * membership, define the sequence shown to an estimator. Prefer outgoing
 * arrows whenever a node has them and retain the parent hierarchy only as a
 * fallback for catalog branches without arrows.
 */
function catalogNavigationNodes(
  catalog: Pick<
    ReturnType<typeof createRepairEstimateCatalogIndex>,
    "getChildren" | "getDependencyNodes" | "getFollowUpNodes"
  >,
  node: RepairEstimateCatalogNodeDto
) {
  const linkedNodes = uniqueNodes([
    ...catalog.getFollowUpNodes(node.id),
    ...catalog.getDependencyNodes(node.id),
  ])

  return linkedNodes.length > 0 ? linkedNodes : catalog.getChildren(node.id)
}

export function RepairEstimateCatalogPicker({
  lines,
  readOnly,
  onChange,
  onPagerChange,
  excludeFurniture = false,
  accessToken = null,
  mediaOwner = null,
  ensureMediaOwner,
}: RepairEstimateCatalogPickerProps) {
  const catalogQuery = useQuery({
    queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    queryFn: getOperationalRepairEstimateCatalog,
  })
  const catalog = useMemo(
    () =>
      catalogQuery.data
        ? createRepairEstimateCatalogIndex(catalogQuery.data)
        : null,
    [catalogQuery.data]
  )
  const [mode, setMode] = useState<CatalogMode>("LINKED_SET")
  const [path, setPath] = useState<string[]>([])
  const [commonOpen, setCommonOpen] = useState(false)
  const [pendingWork, setPendingWork] =
    useState<RepairEstimateCatalogNodeDto | null>(null)
  const [pendingMaterial, setPendingMaterial] =
    useState<RepairEstimateCatalogNodeDto | null>(null)
  const [addContext, setAddContext] = useState<AddContext | null>(null)
  const [duplicateWorkContext, setDuplicateWorkContext] =
    useState<DuplicateCatalogWorkContext | null>(null)
  const [provisionalBaseLines, setProvisionalBaseLines] = useState<
    RepairEstimateLineDto[] | null
  >(null)
  const [message, setMessage] = useState<string | null>(null)
  const [page, setPage] = useState(0)

  const filterForUsage = useCallback(
    (nodes: readonly RepairEstimateCatalogNodeDto[]) =>
      catalog
        ? filterRepairEstimateCatalogNodesForUsage(
            nodes,
            catalog,
            excludeFurniture
          )
        : [],
    [catalog, excludeFurniture]
  )

  const currentNodeId = path.length > 0 ? path[path.length - 1] : null
  const currentNode: RepairEstimateCatalogNodeDto | null =
    currentNodeId && catalog
      ? (catalog.nodesById.get(currentNodeId) ?? null)
      : null

  const visibleNodes = useMemo(() => {
    if (!catalog) {
      return []
    }

    if (pendingMaterial) {
      return filterForUsage(
        uniqueNodes([
          ...catalog.getChildren(pendingMaterial.id),
          ...catalog.getFollowUpNodes(pendingMaterial.id),
          ...catalog.getDependencyNodes(pendingMaterial.id),
        ])
      ).filter((node) => node.nodeType === "LOCATION")
    }

    if (pendingWork) {
      return filterForUsage(
        catalog.getDependencyRelatedNodes(pendingWork.id)
      ).filter((node) => node.nodeType === "MATERIAL" && node.active)
    }

    const currentCommonSection =
      currentNode !== null && isCommonCatalogSection(currentNode)
    const candidates =
      commonOpen || currentCommonSection
        ? commonCatalogEstimateNodes(catalog)
        : uniqueNodes(
            currentNode
              ? catalogNavigationNodes(catalog, currentNode)
              : [...catalog.operationalMenuNodes]
          ).filter((node) => !node.commonItem && !isCommonCatalogSection(node))

    return filterForUsage(candidates).filter((node) => {
      if (commonOpen || currentCommonSection) {
        if (mode === "WORKS_ONLY") {
          return node.nodeType === "WORK"
        }
        if (mode === "MATERIALS_ONLY") {
          return node.nodeType === "MATERIAL"
        }
        return node.nodeType === "WORK" || node.nodeType === "MATERIAL"
      }
      if (mode === "WORKS_ONLY") {
        return node.nodeType !== "MATERIAL"
      }
      if (mode === "MATERIALS_ONLY") {
        return (
          node.nodeType !== "WORK" ||
          catalog
            .getDependencyRelatedNodes(node.id)
            .some((related) => related.nodeType === "MATERIAL")
        )
      }
      return true
    })
  }, [
    catalog,
    commonOpen,
    currentNode,
    filterForUsage,
    mode,
    pendingMaterial,
    pendingWork,
  ])

  const commonNodesAvailable = useMemo(
    () =>
      catalog
        ? filterForUsage(commonCatalogEstimateNodes(catalog)).length > 0
        : false,
    [catalog, filterForUsage]
  )

  const breadcrumbs: CatalogBreadcrumb[] = [
    {
      key: "main-menu",
      label: "Каталог",
      pathLength: 0,
    },
    ...path.map((nodeId, index) => ({
      key: nodeId,
      label: catalog?.nodesById.get(nodeId)?.name ?? "Раздел каталога",
      pathLength: index + 1,
    })),
  ]

  const totalPages = Math.max(
    1,
    Math.ceil(visibleNodes.length / CATALOG_PAGE_SIZE)
  )
  const currentPage = Math.min(page, totalPages - 1)
  const pagedVisibleNodes = visibleNodes.slice(
    currentPage * CATALOG_PAGE_SIZE,
    (currentPage + 1) * CATALOG_PAGE_SIZE
  )
  const goBack = useCallback(() => {
    setPage((current) => Math.max(0, current - 1))
  }, [])
  const goForward = useCallback(() => {
    setPage((current) => Math.min(totalPages - 1, current + 1))
  }, [totalPages])
  const canGoBack = currentPage > 0
  const canGoForward = currentPage < totalPages - 1

  useEffect(() => {
    onPagerChange?.(
      readOnly
        ? null
        : {
            canGoBack,
            canGoForward,
            goBack,
            goForward,
          }
    )
  }, [canGoBack, canGoForward, goBack, goForward, onPagerChange, readOnly])

  function resetNavigation(nextMode = mode) {
    setMode(nextMode)
    setPath([])
    setCommonOpen(false)
    setPendingWork(null)
    setPendingMaterial(null)
    setAddContext(null)
    setDuplicateWorkContext(null)
    setProvisionalBaseLines(null)
    setMessage(null)
    setPage(0)
  }

  function changeMode(nextMode: CatalogMode) {
    const preserveCommon = commonOpen
    resetNavigation(nextMode)
    if (preserveCommon) {
      setCommonOpen(true)
    }
  }

  function openCommonCatalog() {
    setMode("WORKS_ONLY")
    setPath([])
    setCommonOpen(true)
    setPendingWork(null)
    setPendingMaterial(null)
    setAddContext(null)
    setDuplicateWorkContext(null)
    setMessage(null)
    setPage(0)
  }

  function openAdd(
    nodes: RepairEstimateCatalogNodeDto[],
    quantityNode: RepairEstimateCatalogNodeDto,
    locationTitle: string | null = null
  ) {
    setProvisionalBaseLines(null)
    const addContext = { nodes, quantityNode, locationTitle }
    const choices = uniqueNodes(
      nodes.filter((node) => node.nodeType === "WORK")
    )
      .map((node) => {
        const description = catalogEstimateLineDescription(node, locationTitle)
        return {
          node,
          existingLines: lines.filter(
            (line) =>
              line.lineType === "WORK" &&
              line.catalogSnapshot?.nodeId === node.id &&
              line.description.trim() === description.trim()
          ),
        }
      })
      .filter((choice) => choice.existingLines.length > 0)

    setAddContext(null)
    if (choices.length > 0) {
      setDuplicateWorkContext({
        addContext,
        choices,
        choiceIndex: 0,
        targetWorkLineIdsByCatalogNodeId: {},
      })
      return
    }
    setDuplicateWorkContext(null)
    setAddContext({ ...addContext, targetWorkLineIdsByCatalogNodeId: {} })
  }

  function chooseDuplicateWork(targetWorkLineId: string | null) {
    if (!duplicateWorkContext) {
      return
    }
    const choice =
      duplicateWorkContext.choices[duplicateWorkContext.choiceIndex]
    if (!choice) {
      setDuplicateWorkContext(null)
      return
    }

    const targetWorkLineIdsByCatalogNodeId = {
      ...duplicateWorkContext.targetWorkLineIdsByCatalogNodeId,
      [choice.node.id]: targetWorkLineId,
    }
    const nextChoiceIndex = duplicateWorkContext.choiceIndex + 1
    if (nextChoiceIndex < duplicateWorkContext.choices.length) {
      setDuplicateWorkContext({
        ...duplicateWorkContext,
        choiceIndex: nextChoiceIndex,
        targetWorkLineIdsByCatalogNodeId,
      })
      return
    }

    setDuplicateWorkContext(null)
    setAddContext({
      ...duplicateWorkContext.addContext,
      targetWorkLineIdsByCatalogNodeId,
    })
  }

  function navigateInto(node: RepairEstimateCatalogNodeDto) {
    setPath((current) => [...current, node.id])
    setCommonOpen(false)
    setMessage(null)
    setPage(0)
  }

  function navigateToBreadcrumb(pathLength: number) {
    setPath((current) => current.slice(0, pathLength))
    setCommonOpen(false)
    setPendingWork(null)
    setPendingMaterial(null)
    setDuplicateWorkContext(null)
    setMessage(null)
    setPage(0)
  }

  function hasMenu(node: RepairEstimateCatalogNodeDto) {
    if (!catalog) {
      return false
    }
    const nodes = isCommonCatalogSection(node)
      ? commonCatalogEstimateNodes(catalog)
      : catalogNavigationNodes(catalog, node).filter(
          (candidate) => !candidate.commonItem
        )
    return filterForUsage(nodes).length > 0
  }

  function nodeActionLabel(node: RepairEstimateCatalogNodeDto) {
    return hasMenu(node) &&
      !["WORK", "MATERIAL", "LOCATION"].includes(node.nodeType)
      ? "Открыть"
      : "Выбрать"
  }

  function selectNode(node: RepairEstimateCatalogNodeDto) {
    if (!catalog) {
      return
    }
    if (excludeFurniture && catalog.isFurnitureNode(node.id)) {
      setMessage("Мебель добавляется только через смету")
      return
    }

    setPage(0)

    if (["CATEGORY", "SUBCATEGORY"].includes(node.nodeType)) {
      navigateInto(node)
      return
    }

    if (node.nodeType === "OPTION" && hasMenu(node)) {
      navigateInto(node)
      return
    }

    if (node.nodeType === "OPTION" && !node.includeInEstimate) {
      setMessage(
        "Эта опция служит только для навигации и не включается в смету"
      )
      return
    }

    if (node.nodeType === "LOCATION") {
      if (!pendingWork || !pendingMaterial) {
        setMessage("Расположение выбирается после связанного материала")
        return
      }
      openAdd([pendingWork, pendingMaterial], pendingMaterial, node.name)
      return
    }

    if (node.nodeType === "WORK") {
      const materials = filterForUsage(
        catalog.getDependencyRelatedNodes(node.id)
      ).filter((related) => related.nodeType === "MATERIAL" && related.active)
      if (mode === "MATERIALS_ONLY") {
        if (materials.length === 0) {
          setMessage("Для этой работы не настроены материалы")
          return
        }
        setPendingWork(node)
        return
      }
      if (mode === "LINKED_SET" && materials.length > 0) {
        setPendingWork(node)
        return
      }
      openAdd([node], node)
      return
    }

    if (node.nodeType === "MATERIAL" || node.nodeType === "OPTION") {
      if (pendingWork && mode === "LINKED_SET") {
        const locations = uniqueNodes([
          ...catalog.getChildren(node.id),
          ...catalog.getDependencyNodes(node.id),
          ...catalog.getFollowUpNodes(node.id),
        ]).filter((candidate) => candidate.nodeType === "LOCATION")
        if (locations.length > 0) {
          setPendingMaterial(node)
          return
        }
        openAdd([pendingWork, node], node)
        return
      }

      const linkedWorks =
        mode === "LINKED_SET"
          ? filterForUsage(catalog.getDependencyRelatedNodes(node.id)).filter(
              (related) => related.nodeType === "WORK" && related.active
            )
          : []
      openAdd([...linkedWorks, node], node)
      return
    }

    setMessage("Элемент нельзя добавить в смету")
  }

  function finishAdd(
    quantity: number,
    comment: string,
    workNodeId: string | null,
    workMediaReferences: ReadyMediaReference[]
  ) {
    if (!addContext || !catalog) {
      return
    }

    if (!addContext.nodes.some((node) => node.includeInEstimate)) {
      setAddContext(null)
      setMessage("Выбранная позиция не настроена для включения в смету")
      return
    }
    const quantityError = getRepairEstimateCatalogQuantityError(
      addContext.quantityNode,
      quantity
    )
    if (quantityError) {
      setMessage(quantityError)
      return
    }

    onChange(
      applyCatalogNodesToEstimateLines({
        lines: provisionalBaseLines ?? lines,
        nodes: addContext.nodes,
        quantity,
        comment,
        locationTitle: addContext.locationTitle,
        targetWorkLineIdsByCatalogNodeId:
          addContext.targetWorkLineIdsByCatalogNodeId,
        workMediaReferencesByCatalogNodeId:
          workNodeId === null
            ? undefined
            : { [workNodeId]: workMediaReferences },
      })
    )
    setAddContext(null)
    setDuplicateWorkContext(null)
    setProvisionalBaseLines(null)
    setPendingWork(null)
    setPendingMaterial(null)
    setPath([])
    setCommonOpen(false)
    setMessage("Позиция добавлена в смету")
    setPage(0)
  }

  async function ensureOwnerForPendingAdd(
    quantity: number,
    comment: string
  ) {
    if (!addContext || !catalog || !ensureMediaOwner) {
      throw new Error("Сначала сохраните документ, чтобы прикрепить фото")
    }
    const baseLines = provisionalBaseLines ?? lines
    const workNodeId =
      addContext.nodes.find((node) => node.nodeType === "WORK")?.id ?? null
    const provisionalLines = applyCatalogNodesToEstimateLines({
      lines: baseLines,
      nodes: addContext.nodes,
      quantity,
      comment,
      locationTitle: addContext.locationTitle,
      targetWorkLineIdsByCatalogNodeId:
        addContext.targetWorkLineIdsByCatalogNodeId,
      workMediaReferencesByCatalogNodeId:
        workNodeId === null ? undefined : { [workNodeId]: [] },
    })
    const owner = await ensureMediaOwner(provisionalLines)
    setProvisionalBaseLines(baseLines)
    return owner
  }

  if (readOnly) {
    return null
  }

  return (
    <section className="flex flex-col gap-3">
      <Field>
        <FieldLabel htmlFor="estimate-catalog-mode">Состав</FieldLabel>
        <Select
          value={mode}
          onValueChange={(value) => changeMode(value as CatalogMode)}
        >
          <SelectTrigger
            id="estimate-catalog-mode"
            aria-label="Состав каталога"
            className="w-full"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value="LINKED_SET">Работы + материалы</SelectItem>
              <SelectItem value="WORKS_ONLY">Только работы</SelectItem>
              <SelectItem value="MATERIALS_ONLY">Только материалы</SelectItem>
            </SelectGroup>
          </SelectContent>
        </Select>
      </Field>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant={commonOpen ? "default" : "outline"}
          disabled={!commonNodesAvailable}
          onClick={openCommonCatalog}
        >
          Добавить общее
        </Button>
        {commonOpen ? (
          <div
            role="group"
            aria-label="Тип общих позиций"
            className="flex flex-wrap items-center gap-1"
          >
            <Button
              type="button"
              variant={mode === "MATERIALS_ONLY" ? "outline" : "default"}
              size="sm"
              onClick={() => changeMode("WORKS_ONLY")}
            >
              Работы
            </Button>
            <Button
              type="button"
              variant={mode === "MATERIALS_ONLY" ? "default" : "outline"}
              size="sm"
              onClick={() => changeMode("MATERIALS_ONLY")}
            >
              Материалы
            </Button>
            <span className="text-xs text-muted-foreground">
              Показываются только позиции, отмеченные как общие.
            </span>
          </div>
        ) : null}
      </div>

      <nav
        aria-label="Путь по каталогу"
        className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground"
      >
        {path.length > 0 || pendingWork || commonOpen ? (
          <>
            <Button
              type="button"
              variant="outline"
              size="icon"
              aria-label="К корню каталога"
              onClick={() => navigateToBreadcrumb(0)}
            >
              <HugeiconsIcon icon={ArrowLeftDoubleIcon} />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              aria-label="Назад по каталогу"
              onClick={() => {
                if (pendingMaterial) {
                  setPendingMaterial(null)
                } else if (pendingWork) {
                  setPendingWork(null)
                } else if (commonOpen) {
                  setCommonOpen(false)
                } else {
                  setPath((current) => current.slice(0, -1))
                }
                setMessage(null)
                setPage(0)
              }}
            >
              <HugeiconsIcon icon={ArrowLeft01Icon} />
            </Button>
          </>
        ) : null}
        {breadcrumbs.map((breadcrumb, index) => (
          <span key={breadcrumb.key} className="flex items-center gap-1">
            {index > 0 ? <span aria-hidden="true">/</span> : null}
            <Button
              type="button"
              variant="link"
              size="xs"
              onClick={() => navigateToBreadcrumb(breadcrumb.pathLength)}
            >
              {breadcrumb.label}
            </Button>
          </span>
        ))}
        {commonOpen ? (
          <span className="flex items-center gap-1">
            <span aria-hidden="true">/</span>
            <Button type="button" variant="link" size="xs">
              Общее
            </Button>
          </span>
        ) : null}
        {pendingWork ? (
          <span className="flex items-center gap-1">
            <span aria-hidden="true">/</span>
            <Button
              type="button"
              variant="link"
              size="xs"
              onClick={() => {
                setPendingMaterial(null)
                setPage(0)
              }}
            >
              {pendingWork.name}
            </Button>
          </span>
        ) : null}
        {pendingMaterial ? (
          <span className="flex items-center gap-1">
            <span aria-hidden="true">/</span>
            <Button
              type="button"
              variant="link"
              size="xs"
              onClick={() => {
                setPendingMaterial(null)
                setPage(0)
              }}
            >
              {pendingMaterial.name}
            </Button>
          </span>
        ) : null}
      </nav>

      {message ? (
        <p role="status" className="text-xs text-muted-foreground">
          {message}
        </p>
      ) : null}

      {catalogQuery.isLoading ? (
        <p className="text-xs text-muted-foreground">Загрузка каталога...</p>
      ) : catalogQuery.isError ? (
        <p role="alert" className="text-xs text-destructive">
          Не удалось загрузить каталог смет.
        </p>
      ) : visibleNodes.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Ничего не найдено</CardTitle>
          </CardHeader>
          <CardContent className="text-muted-foreground">
            Откройте другой раздел или проверьте связи в настройках каталога.
          </CardContent>
        </Card>
      ) : (
        <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
          {pagedVisibleNodes.map((node) => (
            <button
              key={node.id}
              type="button"
              aria-label={`${nodeActionLabel(node)}: ${node.name}`}
              data-catalog-display-color={
                normalizedDisplayColor(node.displayColor) ?? undefined
              }
              className="group w-full min-w-0 rounded-lg text-left outline-none focus-visible:ring-2 focus-visible:ring-ring/30 focus-visible:ring-inset"
              onClick={() => selectNode(node)}
            >
              <Card
                size="sm"
                className="h-full cursor-pointer ring-inset"
                style={catalogNodeColorStyle(node)}
              >
                <CardHeader>
                  <CardTitle className="min-w-0 break-words">
                    {node.name}
                  </CardTitle>
                </CardHeader>
              </Card>
            </button>
          ))}
        </div>
      )}

      <DuplicateCatalogWorkDialog
        context={duplicateWorkContext}
        onOpenChange={(open) => {
          if (!open) {
            setDuplicateWorkContext(null)
          }
        }}
        onChoose={chooseDuplicateWork}
      />

      <CatalogAddDialog
        key={addContext?.quantityNode.id ?? "closed"}
        context={addContext}
        accessToken={accessToken}
        mediaOwner={mediaOwner}
        ensureMediaOwner={
          ensureMediaOwner ? ensureOwnerForPendingAdd : undefined
        }
        excludedMediaIds={
          new Set(
            lines.flatMap((line) =>
              (line.maintenanceMediaReferences ?? []).map(
                (reference) => reference.mediaId
              )
            )
          )
        }
        onOpenChange={(open) => {
          if (open) return
          if (provisionalBaseLines) onChange(provisionalBaseLines)
          setProvisionalBaseLines(null)
          setAddContext(null)
        }}
        onConfirm={finishAdd}
      />
    </section>
  )
}

function DuplicateCatalogWorkDialog({
  context,
  onOpenChange,
  onChoose,
}: {
  context: DuplicateCatalogWorkContext | null
  onOpenChange: (open: boolean) => void
  onChoose: (targetWorkLineId: string | null) => void
}) {
  const choice = context?.choices[context.choiceIndex] ?? null
  return (
    <Dialog open={context !== null} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Работа уже добавлена в смету</DialogTitle>
          <DialogDescription>
            {choice
              ? `«${choice.node.name}» уже есть в смете. Выберите существующую работу или создайте отдельную.`
              : "Выберите, как добавить работу."}
          </DialogDescription>
        </DialogHeader>

        {choice ? (
          <div
            role="list"
            aria-label="Существующие работы"
            className="flex flex-col gap-2"
          >
            {choice.existingLines.map((line, index) => (
              <Button
                key={line.id}
                type="button"
                variant="outline"
                className="h-auto justify-start py-3 text-left whitespace-normal"
                aria-label={`Добавить к работе ${index + 1}: ${line.description}`}
                onClick={() => onChoose(line.id)}
              >
                <span className="flex min-w-0 flex-col items-start gap-1">
                  <span>{line.description}</span>
                  {line.lineComment.trim() ? (
                    <span className="text-xs text-muted-foreground">
                      {line.lineComment}
                    </span>
                  ) : null}
                </span>
              </Button>
            ))}
          </div>
        ) : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button type="button" onClick={() => onChoose(null)}>
            Создать отдельную работу
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CatalogAddDialog({
  context,
  accessToken,
  mediaOwner,
  ensureMediaOwner,
  excludedMediaIds,
  onOpenChange,
  onConfirm,
}: {
  context: AddContext | null
  accessToken: string | null
  mediaOwner: ServiceMediaOwner | null
  ensureMediaOwner?: (
    quantity: number,
    comment: string
  ) => Promise<ServiceMediaOwner>
  excludedMediaIds: ReadonlySet<string>
  onOpenChange: (open: boolean) => void
  onConfirm: (
    quantity: number,
    comment: string,
    workNodeId: string | null,
    workMediaReferences: ReadyMediaReference[]
  ) => void
}) {
  const [quantity, setQuantity] = useState(1)
  const [comment, setComment] = useState("")
  const [workMediaReferences, setWorkMediaReferences] = useState<
    ReadyMediaReference[]
  >([])
  const [photosPending, setPhotosPending] = useState(false)
  const quantityError = context
    ? getRepairEstimateCatalogQuantityError(context.quantityNode, quantity)
    : null
  const quantityInvalid = quantityError !== null
  const hasWork = context?.nodes.some((node) => node.nodeType === "WORK")
  const photoWorkNode = context?.nodes.find((node) => node.nodeType === "WORK")
  return (
    <Dialog open={context !== null} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {hasWork ? "Добавить работу" : "Добавить материал"}
          </DialogTitle>
          <DialogDescription>
            {context?.quantityNode.name ?? "Позиция каталога"}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <Field data-invalid={quantityInvalid || undefined}>
            <FieldLabel htmlFor="catalog-add-quantity">Количество</FieldLabel>
            <Input
              id="catalog-add-quantity"
              aria-label="Количество позиции каталога"
              type="number"
              min={1}
              step={1}
              value={quantity}
              aria-invalid={quantityInvalid || undefined}
              onChange={(event) =>
                setQuantity(Math.max(1, Number(event.target.value) || 1))
              }
            />
            {quantityError && <FieldError>{quantityError}</FieldError>}
          </Field>
          {hasWork ? (
            <>
              <Field>
                <FieldLabel htmlFor="catalog-add-comment">
                  Комментарий к работе
                </FieldLabel>
                <Textarea
                  id="catalog-add-comment"
                  aria-label="Комментарий к работе"
                  value={comment}
                  onChange={(event) => setComment(event.target.value)}
                />
              </Field>
              <WorkLinePhotoControls
                accessToken={accessToken}
                owner={mediaOwner}
                ensureOwner={
                  ensureMediaOwner
                    ? () => ensureMediaOwner(quantity, comment)
                    : undefined
                }
                excludedMediaIds={excludedMediaIds}
                value={workMediaReferences}
                onChange={setWorkMediaReferences}
                onPendingChange={setPhotosPending}
              />
            </>
          ) : null}
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button
            type="button"
            disabled={quantityInvalid || photosPending}
            onClick={() =>
              onConfirm(
                quantity,
                comment,
                photoWorkNode?.id ?? null,
                workMediaReferences
              )
            }
          >
            Далее
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
