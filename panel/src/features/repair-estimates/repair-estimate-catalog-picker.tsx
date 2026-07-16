import { useCallback, useEffect, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  Search01Icon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
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
import { Field, FieldLabel } from "@/components/ui/field"
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
  getOperationalRepairEstimateCatalog,
  getRepairEstimateCatalogMainMenuTitle,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogNodeDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import { repairEstimateCatalogNodeTypeLabel } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import { applyCatalogNodesToEstimateLines } from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

type CatalogMode = "LINKED_SET" | "WORKS_ONLY" | "MATERIALS_ONLY"

type AddContext = {
  nodes: RepairEstimateCatalogNodeDto[]
  quantityNode: RepairEstimateCatalogNodeDto
  locationTitle: string | null
  continuationNode: RepairEstimateCatalogNodeDto
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
}

export type RepairEstimateCatalogPager = {
  canGoBack: boolean
  canGoForward: boolean
  goBack: () => void
  goForward: () => void
}

const CATALOG_PAGE_SIZE = 9

function uniqueNodes(nodes: readonly RepairEstimateCatalogNodeDto[]) {
  return Array.from(new Map(nodes.map((node) => [node.id, node])).values())
}

export function RepairEstimateCatalogPicker({
  lines,
  readOnly,
  onChange,
  onPagerChange,
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
  const [search, setSearch] = useState("")
  const [pendingWork, setPendingWork] =
    useState<RepairEstimateCatalogNodeDto | null>(null)
  const [pendingMaterial, setPendingMaterial] =
    useState<RepairEstimateCatalogNodeDto | null>(null)
  const [addContext, setAddContext] = useState<AddContext | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [page, setPage] = useState(0)

  const currentNode =
    path.length > 0 && catalog ? catalog.nodesById.get(path.at(-1)!) : null

  const visibleNodes = useMemo(() => {
    if (!catalog) {
      return []
    }

    const searchValue = search.trim().toLocaleLowerCase("ru")
    if (searchValue) {
      return catalog.operationalEstimateNodes
        .filter((node) =>
          node.name.toLocaleLowerCase("ru").includes(searchValue)
        )
        .filter((node) => {
          if (mode === "WORKS_ONLY") {
            return node.nodeType === "WORK"
          }
          if (mode === "MATERIALS_ONLY") {
            return node.nodeType !== "WORK"
          }
          return true
        })
    }

    if (pendingMaterial) {
      return uniqueNodes([
        ...catalog.getChildren(pendingMaterial.id),
        ...catalog.getFollowUpNodes(pendingMaterial.id),
        ...catalog.getDependencyNodes(pendingMaterial.id),
      ]).filter((node) => node.nodeType === "LOCATION")
    }

    if (pendingWork) {
      return catalog
        .getDependencyRelatedNodes(pendingWork.id)
        .filter((node) => node.nodeType === "MATERIAL" && node.active)
    }

    const candidates = currentNode
      ? uniqueNodes([
          ...catalog.getDependencyNodes(currentNode.id),
          ...catalog.getFollowUpNodes(currentNode.id),
          ...catalog.getChildren(currentNode.id),
        ])
      : [...catalog.operationalMenuNodes]

    return candidates.filter((node) => {
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
  }, [catalog, currentNode, mode, pendingMaterial, pendingWork, search])

  const showMainMenuTitles =
    search.trim() === "" &&
    path.length === 0 &&
    pendingWork === null &&
    pendingMaterial === null
  const breadcrumbs: CatalogBreadcrumb[] = [
    {
      key: "main-menu",
      label: "Главное меню",
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
    setSearch("")
    setPendingWork(null)
    setPendingMaterial(null)
    setMessage(null)
    setPage(0)
  }

  function openAdd(
    nodes: RepairEstimateCatalogNodeDto[],
    quantityNode: RepairEstimateCatalogNodeDto,
    continuationNode: RepairEstimateCatalogNodeDto,
    locationTitle: string | null = null
  ) {
    setAddContext({ nodes, quantityNode, continuationNode, locationTitle })
  }

  function navigateInto(node: RepairEstimateCatalogNodeDto) {
    setPath((current) => [...current, node.id])
    setSearch("")
    setMessage(null)
    setPage(0)
  }

  function navigateToBreadcrumb(pathLength: number) {
    setPath((current) => current.slice(0, pathLength))
    setSearch("")
    setPendingWork(null)
    setPendingMaterial(null)
    setMessage(null)
    setPage(0)
  }

  function hasMenu(node: RepairEstimateCatalogNodeDto) {
    if (!catalog) {
      return false
    }
    return (
      catalog.getChildren(node.id).length > 0 ||
      catalog.getDependencyNodes(node.id).length > 0 ||
      catalog.getFollowUpNodes(node.id).length > 0
    )
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

    // A selected search result starts the next navigation step immediately.
    // Otherwise the global result list masks pending material/location choices.
    setSearch("")
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
      openAdd([pendingWork, pendingMaterial], pendingMaterial, node, node.name)
      return
    }

    if (node.nodeType === "WORK") {
      const materials = catalog
        .getDependencyRelatedNodes(node.id)
        .filter((related) => related.nodeType === "MATERIAL" && related.active)
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
      openAdd([node], node, node)
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
        openAdd([pendingWork, node], node, node)
        return
      }

      const linkedWorks =
        mode === "LINKED_SET"
          ? catalog
              .getDependencyRelatedNodes(node.id)
              .filter(
                (related) => related.nodeType === "WORK" && related.active
              )
          : []
      openAdd([...linkedWorks, node], node, node)
      return
    }

    setMessage("Элемент нельзя добавить в смету")
  }

  function finishAdd(quantity: number, comment: string) {
    if (!addContext || !catalog) {
      return
    }

    if (!addContext.nodes.some((node) => node.includeInEstimate)) {
      setAddContext(null)
      setMessage("Выбранная позиция не настроена для включения в смету")
      return
    }

    onChange(
      applyCatalogNodesToEstimateLines({
        lines,
        nodes: addContext.nodes,
        quantity,
        comment,
        locationTitle: addContext.locationTitle,
      })
    )
    const followUps = catalog.getFollowUpNodes(addContext.continuationNode.id)
    setAddContext(null)
    setPendingWork(null)
    setPendingMaterial(null)
    setSearch("")
    setMessage("Позиция добавлена в смету")
    setPage(0)
    if (followUps.length > 0) {
      setPath((current) => [...current, addContext.continuationNode.id])
    }
  }

  if (readOnly) {
    return null
  }

  return (
    <section className="flex flex-col gap-3">
      <div className="grid [grid-template-columns:repeat(auto-fit,minmax(min(12rem,100%),1fr))] gap-3">
        <Field>
          <FieldLabel htmlFor="estimate-catalog-search">Поиск</FieldLabel>
          <div className="relative">
            <HugeiconsIcon
              icon={Search01Icon}
              className="pointer-events-none absolute top-1/2 left-2 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              id="estimate-catalog-search"
              aria-label="Поиск по каталогу сметы"
              className="pl-8"
              value={search}
              placeholder="Работа или материал"
              onChange={(event) => {
                setSearch(event.target.value)
                setPage(0)
              }}
            />
          </div>
        </Field>

        <Field>
          <FieldLabel htmlFor="estimate-catalog-mode">
            Режим каталога
          </FieldLabel>
          <Select
            value={mode}
            onValueChange={(value) => resetNavigation(value as CatalogMode)}
          >
            <SelectTrigger
              id="estimate-catalog-mode"
              aria-label="Режим каталога"
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
      </div>

      <nav
        aria-label="Путь по каталогу"
        className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground"
      >
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
        {(path.length > 0 || pendingWork) && !search ? (
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              if (pendingMaterial) {
                setPendingMaterial(null)
              } else if (pendingWork) {
                setPendingWork(null)
              } else {
                setPath((current) => current.slice(0, -1))
              }
              setMessage(null)
              setPage(0)
            }}
          >
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Button>
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
            Измените поиск или проверьте связи в настройках каталога.
          </CardContent>
        </Card>
      ) : (
        <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
          {pagedVisibleNodes.map((node) => (
            <button
              key={node.id}
              type="button"
              aria-label={`${nodeActionLabel(node)}: ${node.name}`}
              className="group min-w-0 w-full rounded-lg text-left outline-none focus-visible:ring-2 focus-visible:ring-ring/30 focus-visible:ring-inset"
              onClick={() => selectNode(node)}
            >
              <Card
                size="sm"
                className="h-full min-h-24 cursor-pointer justify-between ring-inset"
              >
                <CardHeader>
                  <CardTitle className="min-w-0 break-words">
                    {showMainMenuTitles
                      ? getRepairEstimateCatalogMainMenuTitle(node)
                      : node.name}
                  </CardTitle>
                </CardHeader>
                <CardContent className="mt-auto">
                  <div className="flex flex-wrap gap-1">
                    <Badge variant="secondary">
                      {repairEstimateCatalogNodeTypeLabel(node.nodeType)}
                    </Badge>
                    {node.includeInEstimate ? <Badge>Смета</Badge> : null}
                  </div>
                </CardContent>
              </Card>
            </button>
          ))}
        </div>
      )}

      <CatalogAddDialog
        key={addContext?.quantityNode.id ?? "closed"}
        context={addContext}
        onOpenChange={(open) => !open && setAddContext(null)}
        onConfirm={finishAdd}
      />
    </section>
  )
}

function CatalogAddDialog({
  context,
  onOpenChange,
  onConfirm,
}: {
  context: AddContext | null
  onOpenChange: (open: boolean) => void
  onConfirm: (quantity: number, comment: string) => void
}) {
  const [quantity, setQuantity] = useState(
    Math.max(1, context?.quantityNode.defaultQuantity ?? 1)
  )
  const [comment, setComment] = useState("")
  return (
    <Dialog open={context !== null} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {context?.quantityNode.nodeType === "WORK"
              ? "Добавить работу"
              : "Добавить материал"}
          </DialogTitle>
          <DialogDescription>
            {context?.quantityNode.name ?? "Позиция каталога"}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <Field>
            <FieldLabel htmlFor="catalog-add-quantity">Количество</FieldLabel>
            <Input
              id="catalog-add-quantity"
              aria-label="Количество позиции каталога"
              type="number"
              min={1}
              step={1}
              value={quantity}
              onChange={(event) =>
                setQuantity(Math.max(1, Number(event.target.value) || 1))
              }
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="catalog-add-comment">Комментарий</FieldLabel>
            <Textarea
              id="catalog-add-comment"
              aria-label="Комментарий к позиции каталога"
              value={comment}
              onChange={(event) => setComment(event.target.value)}
            />
          </Field>
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button type="button" onClick={() => onConfirm(quantity, comment)}>
            Добавить
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
