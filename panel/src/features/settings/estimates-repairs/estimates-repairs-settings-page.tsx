import { useMemo, useRef, useState, type ReactNode } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  ArrowRight01Icon,
  CanvasIcon,
  Delete01Icon,
  HammerIcon,
  PackageIcon,
  PencilEdit01Icon,
  Refresh01Icon,
  Route01Icon,
  Sofa01Icon,
  TaskDone01Icon,
  ToolsIcon,
  WorkIcon,
} from "@hugeicons/core-free-icons"

import {
  deleteRepairEstimateCatalogCanvasLink,
  deleteRepairEstimateCatalogCanvasNode,
  getRepairEstimateCatalogCanvasMock,
  getRepairEstimateCatalogCanvasSettings,
  resetRepairEstimateCatalogCanvasMock,
  saveRepairEstimateCatalogCanvasLink,
  saveRepairEstimateCatalogCanvasNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api"
import {
  deleteRepairEstimateFurnitureCatalogItem,
  getRepairEstimateFurnitureCatalogMock,
  getRepairEstimateFurnitureCatalogSettings,
  saveRepairEstimateFurnitureCatalogItem,
} from "@/features/settings/estimates-repairs/api/repair-estimate-furniture-catalog-settings-api"
import {
  deleteRepairEstimateMaterialCatalogItem,
  getRepairEstimateMaterialCatalogMock,
  getRepairEstimateMaterialCatalogSettings,
  saveRepairEstimateMaterialCatalogItem,
} from "@/features/settings/estimates-repairs/api/repair-estimate-material-catalog-settings-api"
import {
  deleteRepairEstimateWorkCatalogItem,
  getRepairEstimateWorkCatalogMock,
  getRepairEstimateWorkCatalogSettings,
  saveRepairEstimateWorkCatalogItem,
} from "@/features/settings/estimates-repairs/api/repair-estimate-work-catalog-settings-api"
import { getItemsForCategory } from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import { getRepairSettingsActions } from "@/features/settings/estimates-repairs/api/repair-settings-api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldContent,
  FieldGroup,
  FieldLabel,
  FieldTitle,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"
import type {
  EstimateCatalogSettingsActionDto,
  RepairSettingsActionDto,
} from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogLinkType,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogNodeType,
  RepairEstimateCatalogSectionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"
import {
  repairEstimateCatalogLinkTypeLabel,
  repairEstimateCatalogNodeTypeLabel,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

type SettingsAction =
  | (EstimateCatalogSettingsActionDto & { group: "estimate" })
  | (RepairSettingsActionDto & { group: "repair" })

type EstimateScreen =
  | { level: "root" }
  | { level: "action"; action: EstimateCatalogSettingsActionDto }
  | {
      level: "category"
      action: EstimateCatalogSettingsActionDto
      categoryId: string
    }

type EstimateActionData =
  RepairEstimateCatalogCanvasDto | RepairEstimateCatalogSectionDto

type CatalogTableSortColumn =
  | "name"
  | "unit"
  | "unitPrice"
  | "defaultQuantity"
  | "durationMinutes"
  | "includeInEstimate"
  | "commonItem"

type CatalogTableSortState = {
  column: CatalogTableSortColumn
  direction: "asc" | "desc"
}

type CatalogLinkAnchor = "TOP" | "BOTTOM"

type CatalogLinkStart = {
  nodeId: string
  anchor: CatalogLinkAnchor
}

type NodeDialogState = {
  title: string
  description: string
  submitLabel: string
  allowTypeSelect: boolean
  value: RepairEstimateCatalogNodeMutation
  save: (input: RepairEstimateCatalogNodeMutation) => Promise<unknown>
}

type LinkDialogState = {
  value: RepairEstimateCatalogLinkMutation
  nodes: RepairEstimateCatalogNodeDto[]
}

const ESTIMATE_CATALOG_QUERY_KEY = ["estimate-catalog"] as const
const CATALOG_TABLE_SORT_STORAGE_PREFIX =
  "rwms:repair-estimate-catalog-table-sort:v1:"
const CANVAS_NODE_WIDTH = 256
const CANVAS_NODE_HEIGHT = 208
const CATALOG_ANCHOR_COMMENT_PREFIX = "__anchors__:"

function getEstimateActionIcon(id: EstimateCatalogSettingsActionDto["id"]) {
  switch (id) {
    case "repair-estimate-catalog-canvas":
      return CanvasIcon
    case "repair-estimate-catalog-works":
      return WorkIcon
    case "repair-estimate-catalog-materials":
      return PackageIcon
    case "repair-estimate-catalog-furniture":
      return Sofa01Icon
  }
}

function getRepairActionIcon(id: RepairSettingsActionDto["id"]) {
  switch (id) {
    case "repair-stage-settings":
      return ToolsIcon
    case "repair-route-settings":
      return Route01Icon
    case "repair-acceptance-settings":
      return TaskDone01Icon
    case "repair-rework-settings":
      return HammerIcon
  }
}

function getSectionKind(action: EstimateCatalogSettingsActionDto) {
  switch (action.id) {
    case "repair-estimate-catalog-works":
      return "works"
    case "repair-estimate-catalog-materials":
      return "materials"
    case "repair-estimate-catalog-furniture":
      return "furniture"
    case "repair-estimate-catalog-canvas":
      return null
  }
}

async function getEstimateActionData(action: EstimateCatalogSettingsActionDto) {
  switch (action.id) {
    case "repair-estimate-catalog-canvas":
      return getRepairEstimateCatalogCanvasMock()
    case "repair-estimate-catalog-works":
      return getRepairEstimateWorkCatalogMock()
    case "repair-estimate-catalog-materials":
      return getRepairEstimateMaterialCatalogMock()
    case "repair-estimate-catalog-furniture":
      return getRepairEstimateFurnitureCatalogMock()
  }
}

function isSectionData(
  data: EstimateActionData
): data is RepairEstimateCatalogSectionDto {
  return "items" in data
}

function SettingsActionButton({
  action,
  active,
  onClick,
}: {
  action: SettingsAction
  active: boolean
  onClick: (action: SettingsAction) => void
}) {
  const Icon =
    action.group === "estimate"
      ? getEstimateActionIcon(action.id)
      : getRepairActionIcon(action.id)

  return (
    <Button
      type="button"
      variant={active ? "secondary" : "outline"}
      className="h-auto min-h-14 w-full justify-between gap-3 px-3 py-3 text-left"
      onClick={() => onClick(action)}
    >
      <span className="flex min-w-0 items-center gap-3">
        <HugeiconsIcon icon={Icon} data-icon="inline-start" />
        <span className="min-w-0 truncate">{action.title}</span>
      </span>
      <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-end" />
    </Button>
  )
}

function SectionSkeleton() {
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {Array.from({ length: 4 }).map((_, index) => (
        <Skeleton key={index} className="h-14 rounded-md" />
      ))}
    </div>
  )
}

function SettingsSection({
  title,
  count,
  children,
}: {
  title: string
  count: number
  children: ReactNode
}) {
  return (
    <section className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg border bg-card">
      <div className="flex shrink-0 items-center justify-between gap-3 border-b px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <h2 className="truncate text-lg font-semibold">{title}</h2>
          <Badge variant="secondary">{count} шт.</Badge>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-4">{children}</div>
    </section>
  )
}

function NativeSelect({
  value,
  onChange,
  children,
  disabled = false,
}: {
  value: string
  onChange: (value: string) => void
  children: ReactNode
  disabled?: boolean
}) {
  return (
    <select
      className="h-9 w-full rounded-md border border-input bg-input/20 px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:opacity-50 md:text-xs/relaxed"
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
    >
      {children}
    </select>
  )
}

function toNumberOrNull(value: string) {
  if (value.trim() === "") {
    return null
  }

  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function canUseLocalStorage() {
  return (
    typeof window !== "undefined" && typeof window.localStorage !== "undefined"
  )
}

function isCatalogTableSortColumn(
  value: unknown
): value is CatalogTableSortColumn {
  return (
    value === "name" ||
    value === "unit" ||
    value === "unitPrice" ||
    value === "defaultQuantity" ||
    value === "durationMinutes" ||
    value === "includeInEstimate" ||
    value === "commonItem"
  )
}

function readCatalogTableSortState(
  storageKey: string
): CatalogTableSortState | null {
  if (!canUseLocalStorage()) {
    return null
  }

  const raw = window.localStorage.getItem(storageKey)
  if (!raw) {
    return null
  }

  try {
    const parsed = JSON.parse(raw) as Partial<CatalogTableSortState>
    if (
      isCatalogTableSortColumn(parsed.column) &&
      (parsed.direction === "asc" || parsed.direction === "desc")
    ) {
      return parsed as CatalogTableSortState
    }
  } catch {
    window.localStorage.removeItem(storageKey)
  }

  return null
}

function writeCatalogTableSortState(
  storageKey: string,
  state: CatalogTableSortState | null
) {
  if (!canUseLocalStorage()) {
    return
  }

  if (state === null) {
    window.localStorage.removeItem(storageKey)
    return
  }

  window.localStorage.setItem(storageKey, JSON.stringify(state))
}

function getCatalogTableSortValue(
  item: RepairEstimateCatalogNodeDto,
  column: CatalogTableSortColumn
) {
  switch (column) {
    case "name":
      return item.name
    case "unit":
      return item.unit
    case "unitPrice":
      return item.unitPrice
    case "defaultQuantity":
      return item.defaultQuantity
    case "durationMinutes":
      return item.durationMinutes
    case "includeInEstimate":
      return item.includeInEstimate
    case "commonItem":
      return item.commonItem
  }
}

function compareCatalogTableValues(
  left: string | number | boolean | null,
  right: string | number | boolean | null
) {
  const leftEmpty = left === null || left === ""
  const rightEmpty = right === null || right === ""

  if (leftEmpty || rightEmpty) {
    if (leftEmpty && rightEmpty) {
      return 0
    }
    return leftEmpty ? 1 : -1
  }

  if (typeof left === "string" && typeof right === "string") {
    return left.localeCompare(right, "ru", {
      numeric: true,
      sensitivity: "base",
    })
  }

  if (typeof left === "number" && typeof right === "number") {
    return left - right
  }

  if (typeof left === "boolean" && typeof right === "boolean") {
    return Number(left) - Number(right)
  }

  return String(left).localeCompare(String(right), "ru", {
    numeric: true,
    sensitivity: "base",
  })
}

function compareCatalogTableItems(
  left: RepairEstimateCatalogNodeDto,
  right: RepairEstimateCatalogNodeDto,
  state: CatalogTableSortState
) {
  const result = compareCatalogTableValues(
    getCatalogTableSortValue(left, state.column),
    getCatalogTableSortValue(right, state.column)
  )

  return state.direction === "asc" ? result : -result
}

function normalizeCatalogAnchor(value: string | null | undefined) {
  return value?.toUpperCase() === "TOP" ? "TOP" : "BOTTOM"
}

function parseCatalogAnchorComment(comment: string | null | undefined): {
  sourceAnchor: CatalogLinkAnchor
  targetAnchor: CatalogLinkAnchor
} | null {
  if (!comment?.startsWith(CATALOG_ANCHOR_COMMENT_PREFIX)) {
    return null
  }

  const [sourceAnchor, targetAnchor] = comment
    .slice(CATALOG_ANCHOR_COMMENT_PREFIX.length)
    .split("->")

  return {
    sourceAnchor: normalizeCatalogAnchor(sourceAnchor),
    targetAnchor: normalizeCatalogAnchor(targetAnchor),
  }
}

function createCatalogAnchorComment(
  sourceAnchor: CatalogLinkAnchor,
  targetAnchor: CatalogLinkAnchor
) {
  return `${CATALOG_ANCHOR_COMMENT_PREFIX}${sourceAnchor}->${targetAnchor}`
}

function getCatalogLinkAnchors(
  link: RepairEstimateCatalogLinkDto | RepairEstimateCatalogLinkMutation,
  source?: { y: number } | null,
  target?: { y: number } | null
) {
  const parsed = parseCatalogAnchorComment(link.comment)
  if (parsed !== null) {
    return parsed
  }

  if (source && target && source.y > target.y) {
    return {
      sourceAnchor: "TOP" as const,
      targetAnchor: "BOTTOM" as const,
    }
  }

  return {
    sourceAnchor: "BOTTOM" as const,
    targetAnchor: "TOP" as const,
  }
}

function catalogAnchorLabel(anchor: CatalogLinkAnchor) {
  return anchor === "TOP" ? "Сверху" : "Снизу"
}

function createNodeMutation(
  node: RepairEstimateCatalogNodeDto
): RepairEstimateCatalogNodeMutation {
  return {
    id: node.id,
    code: node.code,
    name: node.name,
    nodeType: node.nodeType,
    parentId: node.parentId,
    active: node.active,
    sortOrder: node.sortOrder,
    unit: node.unit,
    unitPrice: node.unitPrice,
    defaultQuantity: node.defaultQuantity,
    durationMinutes: node.durationMinutes,
    additionalOption: node.additionalOption,
    showInMainMenu: node.showInMainMenu,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    furnitureCategory: node.furnitureCategory,
    canvasX: node.canvasX,
    canvasY: node.canvasY,
    comment: node.comment,
  }
}

function createBlankNodeMutation({
  nodeType,
  parentId,
  furnitureCategory,
  sortOrder,
}: {
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
  furnitureCategory: boolean
  sortOrder: number
}): RepairEstimateCatalogNodeMutation {
  const priced = nodeType === "WORK" || nodeType === "MATERIAL"

  return {
    code: "",
    name: "",
    nodeType,
    parentId,
    active: true,
    sortOrder,
    unit: priced ? "шт." : null,
    unitPrice: priced ? 0 : null,
    defaultQuantity: 1,
    durationMinutes: nodeType === "WORK" ? 60 : null,
    additionalOption: false,
    showInMainMenu: false,
    includeInEstimate: priced,
    commonItem: false,
    furnitureCategory,
    canvasX: null,
    canvasY: null,
    comment: null,
  }
}

function BooleanField({
  title,
  checked,
  disabled = false,
  onCheckedChange,
}: {
  title: string
  checked: boolean
  disabled?: boolean
  onCheckedChange: (checked: boolean) => void
}) {
  return (
    <Field orientation="horizontal" data-disabled={disabled}>
      <Checkbox
        checked={checked}
        disabled={disabled}
        onCheckedChange={(value) => onCheckedChange(value === true)}
      />
      <FieldContent>
        <FieldTitle>{title}</FieldTitle>
      </FieldContent>
    </Field>
  )
}

function NodeEditorDialog({
  state,
  onClose,
  onSaved,
}: {
  state: NodeDialogState | null
  onClose: () => void
  onSaved: () => void
}) {
  if (state === null) {
    return null
  }

  return (
    <NodeEditorDialogContent
      key={state.value.id ?? `${state.title}-${state.value.nodeType}`}
      state={state}
      onClose={onClose}
      onSaved={onSaved}
    />
  )
}

function NodeEditorDialogContent({
  state,
  onClose,
  onSaved,
}: {
  state: NodeDialogState
  onClose: () => void
  onSaved: () => void
}) {
  const [draft, setDraft] = useState(state.value)
  const [error, setError] = useState<string | null>(null)
  const priced = draft.nodeType === "WORK" || draft.nodeType === "MATERIAL"

  const mutation = useMutation({
    mutationFn: state.save,
    onSuccess: () => {
      onSaved()
      onClose()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось сохранить запись"
      )
    },
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{state.title}</DialogTitle>
          <DialogDescription>{state.description}</DialogDescription>
        </DialogHeader>

        <FieldGroup>
          {state.allowTypeSelect && (
            <Field>
              <FieldLabel>Тип</FieldLabel>
              <NativeSelect
                value={draft.nodeType}
                onChange={(value) =>
                  setDraft((current) => ({
                    ...current,
                    nodeType: value as RepairEstimateCatalogNodeType,
                    includeInEstimate: value === "WORK" || value === "MATERIAL",
                    durationMinutes:
                      value === "WORK" ? current.durationMinutes : null,
                    unit:
                      value === "WORK" || value === "MATERIAL"
                        ? current.unit
                        : null,
                    unitPrice:
                      value === "WORK" || value === "MATERIAL"
                        ? current.unitPrice
                        : null,
                  }))
                }
              >
                {(
                  [
                    "SUBCATEGORY",
                    "WORK",
                    "MATERIAL",
                    "LOCATION",
                    "OPTION",
                  ] satisfies RepairEstimateCatalogNodeType[]
                ).map((type) => (
                  <option key={type} value={type}>
                    {repairEstimateCatalogNodeTypeLabel(type)}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}

          <div className="grid gap-3 md:grid-cols-2">
            <Field>
              <FieldLabel>Название</FieldLabel>
              <Input
                value={draft.name}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    name: event.target.value,
                  }))
                }
              />
            </Field>

            <Field>
              <FieldLabel>Код</FieldLabel>
              <Input
                value={draft.code}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    code: event.target.value,
                  }))
                }
              />
            </Field>
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <Field>
              <FieldLabel>Единица</FieldLabel>
              <Input
                value={draft.unit ?? ""}
                disabled={!priced}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    unit: event.target.value,
                  }))
                }
              />
            </Field>

            <Field>
              <FieldLabel>Цена</FieldLabel>
              <Input
                value={draft.unitPrice ?? ""}
                disabled={!priced}
                inputMode="decimal"
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    unitPrice: toNumberOrNull(event.target.value),
                  }))
                }
              />
            </Field>
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <Field>
              <FieldLabel>Количество по умолчанию</FieldLabel>
              <Input
                value={draft.defaultQuantity}
                disabled={!priced}
                inputMode="numeric"
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    defaultQuantity: Number(event.target.value) || 1,
                  }))
                }
              />
            </Field>

            {draft.nodeType === "WORK" && (
              <Field>
                <FieldLabel>Длительность, мин</FieldLabel>
                <Input
                  value={draft.durationMinutes ?? ""}
                  inputMode="numeric"
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      durationMinutes: toNumberOrNull(event.target.value),
                    }))
                  }
                />
              </Field>
            )}
          </div>

          <div className="grid gap-2 md:grid-cols-2">
            <BooleanField
              title="Активно"
              checked={draft.active}
              onCheckedChange={(checked) =>
                setDraft((current) => ({ ...current, active: checked }))
              }
            />
            <BooleanField
              title="Учет в смете"
              checked={draft.includeInEstimate}
              onCheckedChange={(checked) =>
                setDraft((current) => ({
                  ...current,
                  includeInEstimate: checked,
                }))
              }
            />
            <BooleanField
              title="Общий"
              checked={draft.commonItem}
              onCheckedChange={(checked) =>
                setDraft((current) => ({ ...current, commonItem: checked }))
              }
            />
            {draft.nodeType === "CATEGORY" && (
              <BooleanField
                title="Мебельная категория"
                checked={draft.furnitureCategory}
                onCheckedChange={(checked) =>
                  setDraft((current) => ({
                    ...current,
                    furnitureCategory: checked,
                  }))
                }
              />
            )}
          </div>

          <Field>
            <FieldLabel>Комментарий</FieldLabel>
            <Textarea
              value={draft.comment ?? ""}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  comment: event.target.value,
                }))
              }
            />
          </Field>

          {error !== null && <ErrorBox>{error}</ErrorBox>}
        </FieldGroup>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button
            type="button"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate(draft)}
          >
            {state.submitLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function LinkEditorDialog({
  state,
  onClose,
  onSaved,
}: {
  state: LinkDialogState | null
  onClose: () => void
  onSaved: () => void
}) {
  if (state === null) {
    return null
  }

  return (
    <LinkEditorDialogContent
      key={state.value.id ?? "new-link"}
      state={state}
      onClose={onClose}
      onSaved={onSaved}
    />
  )
}

function LinkEditorDialogContent({
  state,
  onClose,
  onSaved,
}: {
  state: LinkDialogState
  onClose: () => void
  onSaved: () => void
}) {
  const [draft, setDraft] = useState(state.value)
  const initialAnchors = getCatalogLinkAnchors(state.value)
  const [sourceAnchor, setSourceAnchor] = useState<CatalogLinkAnchor>(
    initialAnchors.sourceAnchor
  )
  const [targetAnchor, setTargetAnchor] = useState<CatalogLinkAnchor>(
    initialAnchors.targetAnchor
  )
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: saveRepairEstimateCatalogCanvasLink,
    onSuccess: () => {
      onSaved()
      onClose()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось сохранить связь"
      )
    },
  })

  const targets = state.nodes.filter((node) => node.nodeType !== "CATEGORY")

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Связь каталога смет</DialogTitle>
          <DialogDescription>
            Сохраняется в mock-слое конструктора каталога.
          </DialogDescription>
        </DialogHeader>

        <FieldGroup>
          <Field>
            <FieldLabel>Исходный узел</FieldLabel>
            <NativeSelect
              value={draft.sourceNodeId}
              onChange={(value) =>
                setDraft((current) => ({ ...current, sourceNodeId: value }))
              }
            >
              {state.nodes.map((node) => (
                <option key={node.id} value={node.id}>
                  {node.code} - {node.name}
                </option>
              ))}
            </NativeSelect>
          </Field>

          <Field>
            <FieldLabel>Целевой узел</FieldLabel>
            <NativeSelect
              value={draft.targetNodeId}
              onChange={(value) =>
                setDraft((current) => ({ ...current, targetNodeId: value }))
              }
            >
              {targets.map((node) => (
                <option key={node.id} value={node.id}>
                  {node.code} - {node.name}
                </option>
              ))}
            </NativeSelect>
          </Field>

          <Field>
            <FieldLabel>Тип связи</FieldLabel>
            <NativeSelect
              value={draft.linkType}
              onChange={(value) =>
                setDraft((current) => ({
                  ...current,
                  linkType: value as RepairEstimateCatalogLinkType,
                }))
              }
            >
              {(
                [
                  "FOLLOW_UP",
                  "DEPENDENCY",
                ] satisfies RepairEstimateCatalogLinkType[]
              ).map((type) => (
                <option key={type} value={type}>
                  {repairEstimateCatalogLinkTypeLabel(type)}
                </option>
              ))}
            </NativeSelect>
          </Field>

          <div className="grid gap-3 md:grid-cols-2">
            <Field>
              <FieldLabel>Точка исходного блока</FieldLabel>
              <NativeSelect
                value={sourceAnchor}
                onChange={(value) =>
                  setSourceAnchor(normalizeCatalogAnchor(value))
                }
              >
                {(["BOTTOM", "TOP"] satisfies CatalogLinkAnchor[]).map(
                  (anchor) => (
                    <option key={anchor} value={anchor}>
                      {catalogAnchorLabel(anchor)}
                    </option>
                  )
                )}
              </NativeSelect>
            </Field>

            <Field>
              <FieldLabel>Точка целевого блока</FieldLabel>
              <NativeSelect
                value={targetAnchor}
                onChange={(value) =>
                  setTargetAnchor(normalizeCatalogAnchor(value))
                }
              >
                {(["TOP", "BOTTOM"] satisfies CatalogLinkAnchor[]).map(
                  (anchor) => (
                    <option key={anchor} value={anchor}>
                      {catalogAnchorLabel(anchor)}
                    </option>
                  )
                )}
              </NativeSelect>
            </Field>
          </div>

          <BooleanField
            title="Активно"
            checked={draft.active}
            onCheckedChange={(checked) =>
              setDraft((current) => ({ ...current, active: checked }))
            }
          />

          {error !== null && <ErrorBox>{error}</ErrorBox>}
        </FieldGroup>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button
            type="button"
            disabled={mutation.isPending}
            onClick={() =>
              mutation.mutate({
                ...draft,
                comment: createCatalogAnchorComment(sourceAnchor, targetAnchor),
              })
            }
          >
            Сохранить связь
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ErrorBox({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
      {children}
    </div>
  )
}

function CatalogMeta({
  nodeCount,
  linkCount,
}: {
  nodeCount: number
  linkCount: number
}) {
  return (
    <div className="flex flex-wrap gap-2">
      <Badge variant="secondary">{nodeCount} узлов</Badge>
      <Badge variant="secondary">{linkCount} связей</Badge>
    </div>
  )
}

function categoryItemCount(
  data: EstimateActionData,
  categoryId: string
): number {
  if (isSectionData(data)) {
    return getItemsForCategory(data, categoryId).length
  }

  const nodesById = new Map(data.nodes.map((node) => [node.id, node]))
  return data.nodes.filter(
    (node) =>
      node.id !== categoryId &&
      includesNodeInCategory(node, categoryId, nodesById)
  ).length
}

function EstimateActionCategoryMenu({
  action,
  onOpenCategory,
}: {
  action: EstimateCatalogSettingsActionDto
  onOpenCategory: (categoryId: string) => void
}) {
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const dataQuery = useQuery({
    queryKey: [...ESTIMATE_CATALOG_QUERY_KEY, "action-menu", action.id],
    queryFn: () => getEstimateActionData(action),
  })

  if (dataQuery.isLoading) {
    return <SectionSkeleton />
  }

  if (!dataQuery.data) {
    return null
  }

  const canCreateCategory = action.id === "repair-estimate-catalog-canvas"
  const nodeCount = isSectionData(dataQuery.data)
    ? dataQuery.data.items.length
    : dataQuery.data.nodes.length
  const linkCount = dataQuery.data.links.length

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CatalogMeta nodeCount={nodeCount} linkCount={linkCount} />
        {canCreateCategory && (
          <Button
            type="button"
            onClick={() =>
              setDialogState({
                title: "Создать категорию",
                description: "Категория верхнего уровня каталога смет.",
                submitLabel: "Создать",
                allowTypeSelect: false,
                value: createBlankNodeMutation({
                  nodeType: "CATEGORY",
                  parentId: null,
                  furnitureCategory: false,
                  sortOrder: dataQuery.data.categories.length * 10 + 10,
                }),
                save: saveRepairEstimateCatalogCanvasNode,
              })
            }
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Создать категорию
          </Button>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {dataQuery.data.categories.map((category) => (
          <button
            key={category.id}
            type="button"
            className="flex min-h-28 flex-col items-start justify-between gap-4 rounded-lg border bg-background p-4 text-left transition-colors hover:bg-accent"
            onClick={() => onOpenCategory(category.id)}
          >
            <span className="flex w-full items-center justify-between gap-2">
              <span className="min-w-0 truncate font-medium">
                {category.name}
              </span>
              <HugeiconsIcon icon={ArrowRight01Icon} />
            </span>
            <Badge variant="secondary">
              {categoryItemCount(dataQuery.data, category.id)} записей
            </Badge>
          </button>
        ))}
      </div>

      <NodeEditorDialog
        state={dialogState}
        onClose={() => setDialogState(null)}
        onSaved={() =>
          void queryClient.invalidateQueries({
            queryKey: ESTIMATE_CATALOG_QUERY_KEY,
          })
        }
      />
    </div>
  )
}

function includesNodeInCategory(
  node: RepairEstimateCatalogNodeDto,
  categoryId: string,
  nodesById: Map<string, RepairEstimateCatalogNodeDto>
) {
  if (node.id === categoryId) {
    return true
  }

  let current = node
  const visited = new Set<string>()
  while (current.parentId) {
    if (visited.has(current.id)) {
      return false
    }
    visited.add(current.id)
    if (current.parentId === categoryId) {
      return true
    }
    const parent = nodesById.get(current.parentId)
    if (!parent) {
      return false
    }
    current = parent
  }

  return false
}

function getCanvasNodes(
  data: RepairEstimateCatalogCanvasDto,
  categoryId: string | null
) {
  if (categoryId === null) {
    return []
  }

  const nodesById = new Map(data.nodes.map((node) => [node.id, node]))
  return data.nodes.filter((node) =>
    includesNodeInCategory(node, categoryId, nodesById)
  )
}

function getCanvasPosition(node: RepairEstimateCatalogNodeDto, index: number) {
  if (node.nodeType === "CATEGORY") {
    return {
      x: node.canvasX ?? 40,
      y: node.canvasY ?? 40,
    }
  }

  return {
    x: node.canvasX ?? 40 + (index % 3) * 340,
    y: node.canvasY ?? 270 + Math.floor(index / 3) * 260,
  }
}

type CanvasPosition = {
  x: number
  y: number
}

type CanvasDragState = {
  nodeId: string
  pointerId: number
  startClientX: number
  startClientY: number
  startX: number
  startY: number
  moved: boolean
}

function setPointerCaptureSafely(element: Element, pointerId: number) {
  try {
    element.setPointerCapture(pointerId)
  } catch {
    // Synthetic PointerEvents used by tests may not have an active browser pointer.
  }
}

function releasePointerCaptureSafely(element: Element, pointerId: number) {
  try {
    if (element.hasPointerCapture(pointerId)) {
      element.releasePointerCapture(pointerId)
    }
  } catch {
    // Matching guard for synthetic PointerEvents.
  }
}

function getCurveMidpoint(
  source: CanvasPosition,
  target: CanvasPosition,
  sourceAnchor: CatalogLinkAnchor,
  targetAnchor: CatalogLinkAnchor
) {
  const curve = Math.max(80, Math.abs(target.y - source.y) / 2)
  const sourceControl = {
    x: source.x,
    y: sourceAnchor === "TOP" ? source.y - curve : source.y + curve,
  }
  const targetControl = {
    x: target.x,
    y: targetAnchor === "TOP" ? target.y - curve : target.y + curve,
  }
  const t = 0.5
  const inverse = 1 - t

  return {
    x:
      inverse ** 3 * source.x +
      3 * inverse ** 2 * t * sourceControl.x +
      3 * inverse * t ** 2 * targetControl.x +
      t ** 3 * target.x,
    y:
      inverse ** 3 * source.y +
      3 * inverse ** 2 * t * sourceControl.y +
      3 * inverse * t ** 2 * targetControl.y +
      t ** 3 * target.y,
  }
}

function CatalogCanvas({
  nodes,
  links,
  selectedNodeId,
  selectedLinkId,
  draftLinkStart,
  onSelectNode,
  onSelectLink,
  onBeginLink,
  onCompleteLink,
  onMoveNode,
  onEditNode,
  onDeleteLink,
}: {
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
  selectedNodeId: string | null
  selectedLinkId: string | null
  draftLinkStart: CatalogLinkStart | null
  onSelectNode: (node: RepairEstimateCatalogNodeDto) => void
  onSelectLink: (link: RepairEstimateCatalogLinkDto) => void
  onBeginLink: (start: CatalogLinkStart) => void
  onCompleteLink: (
    target: RepairEstimateCatalogNodeDto,
    targetAnchor: CatalogLinkAnchor
  ) => void
  onMoveNode: (
    node: RepairEstimateCatalogNodeDto,
    position: CanvasPosition
  ) => void
  onEditNode: (node: RepairEstimateCatalogNodeDto) => void
  onDeleteLink: (link: RepairEstimateCatalogLinkDto) => void
}) {
  const dragRef = useRef<CanvasDragState | null>(null)
  const suppressClickRef = useRef(false)
  const [dragPositions, setDragPositions] = useState<
    Record<string, CanvasPosition>
  >({})
  const positionedNodes = nodes.map((node, index) => ({
    node,
    position: {
      ...(dragPositions[node.id] ?? getCanvasPosition(node, index)),
      width: CANVAS_NODE_WIDTH,
      height: CANVAS_NODE_HEIGHT,
    },
  }))
  const positionById = new Map(
    positionedNodes.map(({ node, position }) => [node.id, position])
  )
  const height = Math.max(
    520,
    ...positionedNodes.map(({ position }) => position.y + position.height + 40)
  )
  const width = Math.max(
    980,
    ...positionedNodes.map(({ position }) => position.x + position.width + 40)
  )

  const anchorPoint = (
    position: {
      x: number
      y: number
      width: number
      height: number
    },
    anchor: CatalogLinkAnchor
  ) => ({
    x: position.x + position.width / 2,
    y: anchor === "TOP" ? position.y : position.y + position.height,
  })

  const pathD = (
    source: { x: number; y: number },
    target: { x: number; y: number },
    sourceAnchor: CatalogLinkAnchor,
    targetAnchor: CatalogLinkAnchor
  ) => {
    const curve = Math.max(80, Math.abs(target.y - source.y) / 2)
    const sourceControlY =
      sourceAnchor === "TOP" ? source.y - curve : source.y + curve
    const targetControlY =
      targetAnchor === "TOP" ? target.y - curve : target.y + curve

    return `M ${source.x} ${source.y} C ${source.x} ${sourceControlY}, ${target.x} ${targetControlY}, ${target.x} ${target.y}`
  }

  const finishDrag = (
    node: RepairEstimateCatalogNodeDto,
    position: CanvasPosition
  ) => {
    onMoveNode(node, {
      x: Math.round(position.x),
      y: Math.round(position.y),
    })
  }

  return (
    <div className="relative" style={{ width, height }}>
      <svg className="absolute inset-0 h-full w-full overflow-visible">
        <defs>
          <marker
            id="catalog-arrow-end"
            viewBox="0 0 14 14"
            refX="12"
            refY="7"
            markerWidth="10"
            markerHeight="10"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 14 7 L 0 14 z" fill="context-stroke" />
          </marker>
        </defs>
        {links.map((link) => {
          const source = positionById.get(link.sourceNodeId)
          const target = positionById.get(link.targetNodeId)
          if (!source || !target) {
            return null
          }

          const { sourceAnchor, targetAnchor } = getCatalogLinkAnchors(
            link,
            source,
            target
          )
          const sourcePoint = anchorPoint(source, sourceAnchor)
          const targetPoint = anchorPoint(target, targetAnchor)
          const selected = link.id === selectedLinkId
          const dependency = link.linkType === "DEPENDENCY"
          const path = pathD(
            sourcePoint,
            targetPoint,
            sourceAnchor,
            targetAnchor
          )

          return (
            <g key={link.id}>
              <path
                d={path}
                fill="none"
                stroke="transparent"
                strokeWidth={22}
                strokeLinecap="round"
                pointerEvents="stroke"
                className="cursor-pointer"
                onClick={(event) => {
                  event.stopPropagation()
                  onSelectLink(link)
                }}
              />
              <path
                d={path}
                fill="none"
                stroke="currentColor"
                strokeWidth={selected ? 4 : 3}
                strokeLinecap="round"
                strokeDasharray={dependency ? undefined : "8 8"}
                markerStart={dependency ? "url(#catalog-arrow-end)" : undefined}
                markerEnd="url(#catalog-arrow-end)"
                pointerEvents="none"
                className={cn(
                  dependency ? "text-primary" : "text-muted-foreground",
                  selected && "text-primary drop-shadow-sm"
                )}
              />
            </g>
          )
        })}
      </svg>

      {links.map((link) => {
        if (link.id !== selectedLinkId) {
          return null
        }

        const source = positionById.get(link.sourceNodeId)
        const target = positionById.get(link.targetNodeId)
        if (!source || !target) {
          return null
        }

        const { sourceAnchor, targetAnchor } = getCatalogLinkAnchors(
          link,
          source,
          target
        )
        const midpoint = getCurveMidpoint(
          anchorPoint(source, sourceAnchor),
          anchorPoint(target, targetAnchor),
          sourceAnchor,
          targetAnchor
        )

        return (
          <Button
            key={`delete-${link.id}`}
            type="button"
            variant="outline"
            size="sm"
            className="absolute z-30 -translate-x-1/2 -translate-y-1/2 border-border bg-background/95 text-muted-foreground shadow-md hover:border-destructive hover:bg-background hover:text-destructive"
            style={{
              left: midpoint.x,
              top: midpoint.y,
            }}
            onClick={(event) => {
              event.stopPropagation()
              onDeleteLink(link)
            }}
            onPointerDown={(event) => event.stopPropagation()}
            onPointerUp={(event) => event.stopPropagation()}
          >
            <HugeiconsIcon icon={Delete01Icon} data-icon="inline-start" />
            Удалить
          </Button>
        )
      })}

      {positionedNodes.map(({ node, position }) => (
        <div
          key={node.id}
          role="group"
          aria-label={`Блок: ${node.name}`}
          tabIndex={0}
          className={cn(
            "absolute flex cursor-grab touch-none flex-col items-stretch gap-3 overflow-visible rounded-lg border bg-card px-3 py-3 text-left text-sm shadow-sm transition-colors select-none hover:bg-accent active:cursor-grabbing",
            node.id === selectedNodeId && "border-primary bg-accent"
          )}
          style={{
            left: position.x,
            top: position.y,
            width: CANVAS_NODE_WIDTH,
            minHeight: CANVAS_NODE_HEIGHT,
          }}
          onClick={() => {
            if (suppressClickRef.current) {
              suppressClickRef.current = false
              return
            }
            onSelectNode(node)
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault()
              onSelectNode(node)
            }
          }}
          onPointerDown={(event) => {
            if (event.button !== 0 && event.pointerType === "mouse") {
              return
            }

            onSelectNode(node)
            setPointerCaptureSafely(event.currentTarget, event.pointerId)
            dragRef.current = {
              nodeId: node.id,
              pointerId: event.pointerId,
              startClientX: event.clientX,
              startClientY: event.clientY,
              startX: position.x,
              startY: position.y,
              moved: false,
            }
          }}
          onPointerMove={(event) => {
            const drag = dragRef.current
            if (
              drag === null ||
              drag.nodeId !== node.id ||
              drag.pointerId !== event.pointerId
            ) {
              return
            }

            const deltaX = event.clientX - drag.startClientX
            const deltaY = event.clientY - drag.startClientY
            if (Math.abs(deltaX) > 3 || Math.abs(deltaY) > 3) {
              drag.moved = true
              suppressClickRef.current = true
            }

            if (!drag.moved) {
              return
            }

            const nextPosition = {
              x: Math.max(16, drag.startX + deltaX),
              y: Math.max(16, drag.startY + deltaY),
            }
            setDragPositions((current) => ({
              ...current,
              [node.id]: nextPosition,
            }))
          }}
          onPointerUp={(event) => {
            const drag = dragRef.current
            if (
              drag !== null &&
              drag.nodeId === node.id &&
              drag.pointerId === event.pointerId
            ) {
              dragRef.current = null
              releasePointerCaptureSafely(event.currentTarget, event.pointerId)
              if (drag.moved) {
                event.preventDefault()
                event.stopPropagation()
                const finalPosition = {
                  x: Math.max(
                    16,
                    drag.startX + event.clientX - drag.startClientX
                  ),
                  y: Math.max(
                    16,
                    drag.startY + event.clientY - drag.startClientY
                  ),
                }
                setDragPositions((current) => ({
                  ...current,
                  [node.id]: finalPosition,
                }))
                finishDrag(node, finalPosition)
                return
              }
            }

            if (draftLinkStart === null || draftLinkStart.nodeId === node.id) {
              return
            }

            const rect = event.currentTarget.getBoundingClientRect()
            const targetAnchor =
              event.clientY <= rect.top + rect.height / 2 ? "TOP" : "BOTTOM"
            onCompleteLink(node, targetAnchor)
          }}
          onPointerCancel={(event) => {
            const drag = dragRef.current
            if (
              drag !== null &&
              drag.nodeId === node.id &&
              drag.pointerId === event.pointerId
            ) {
              dragRef.current = null
            }
          }}
        >
          {(["TOP", "BOTTOM"] satisfies CatalogLinkAnchor[]).map((anchor) => (
            <button
              key={anchor}
              type="button"
              aria-label={`${anchor === "TOP" ? "Верхняя" : "Нижняя"} точка связи: ${node.name}`}
              className={cn(
                "absolute left-1/2 z-10 size-5 -translate-x-1/2 rounded-full border-2 border-background bg-primary shadow-md",
                anchor === "TOP" ? "-top-2.5" : "-bottom-2.5",
                draftLinkStart?.nodeId === node.id &&
                  draftLinkStart.anchor === anchor &&
                  "ring-2 ring-ring ring-offset-2"
              )}
              onClick={(event) => event.stopPropagation()}
              onPointerDown={(event) => {
                event.preventDefault()
                event.stopPropagation()
                if (
                  draftLinkStart === null ||
                  draftLinkStart.nodeId === node.id
                ) {
                  onBeginLink({ nodeId: node.id, anchor })
                }
              }}
              onPointerUp={(event) => {
                event.preventDefault()
                event.stopPropagation()
                if (
                  draftLinkStart !== null &&
                  draftLinkStart.nodeId !== node.id
                ) {
                  onCompleteLink(node, anchor)
                }
              }}
            />
          ))}
          <div className="flex w-full items-start justify-between gap-2">
            <span className="min-w-0 flex-1 leading-snug font-medium break-words whitespace-normal">
              {node.name}
            </span>
            <Badge variant="secondary" className="shrink-0">
              {repairEstimateCatalogNodeTypeLabel(node.nodeType)}
            </Badge>
          </div>
          <span className="flex flex-1 flex-col gap-1 text-xs text-muted-foreground">
            <span>К-во. по умолчанию: {node.defaultQuantity}</span>
            <span>Единица: {node.unit ?? ""}</span>
            <span>Активно: {node.active ? "Да" : "Нет"}</span>
            {node.commonItem && <span>Общий</span>}
            {node.comment && (
              <span className="line-clamp-2">{node.comment}</span>
            )}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="mt-auto w-full"
            onClick={(event) => {
              event.stopPropagation()
              onEditNode(node)
            }}
            onPointerDown={(event) => event.stopPropagation()}
            onPointerUp={(event) => event.stopPropagation()}
          >
            Редактировать
          </Button>
        </div>
      ))}
    </div>
  )
}

function CatalogCanvasCategoryEditor({ categoryId }: { categoryId: string }) {
  const queryClient = useQueryClient()
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedLinkId, setSelectedLinkId] = useState<string | null>(null)
  const [draftLinkType, setDraftLinkType] =
    useState<RepairEstimateCatalogLinkType>("FOLLOW_UP")
  const [draftLinkStart, setDraftLinkStart] = useState<CatalogLinkStart | null>(
    null
  )
  const [nodeDialogState, setNodeDialogState] =
    useState<NodeDialogState | null>(null)
  const [linkDialogState, setLinkDialogState] =
    useState<LinkDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const canvasQuery = useQuery({
    queryKey: [...ESTIMATE_CATALOG_QUERY_KEY, "canvas"],
    queryFn: getRepairEstimateCatalogCanvasMock,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ESTIMATE_CATALOG_QUERY_KEY })
  }

  const deleteNodeMutation = useMutation({
    mutationFn: deleteRepairEstimateCatalogCanvasNode,
    onSuccess: () => {
      setError(null)
      setSelectedNodeId(null)
      invalidate()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось удалить блок"
      )
    },
  })

  const deleteLinkMutation = useMutation({
    mutationFn: deleteRepairEstimateCatalogCanvasLink,
    onSuccess: () => {
      setError(null)
      setSelectedLinkId(null)
      invalidate()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось удалить связь"
      )
    },
  })

  const createLinkMutation = useMutation({
    mutationFn: saveRepairEstimateCatalogCanvasLink,
    onSuccess: (link) => {
      setError(null)
      setDraftLinkStart(null)
      setSelectedNodeId(null)
      setSelectedLinkId(link.id)
      invalidate()
    },
    onError: (mutationError) => {
      setDraftLinkStart(null)
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось создать связь"
      )
    },
  })

  const moveNodeMutation = useMutation({
    mutationFn: ({
      node,
      position,
    }: {
      node: RepairEstimateCatalogNodeDto
      position: CanvasPosition
    }) =>
      saveRepairEstimateCatalogCanvasNode({
        ...createNodeMutation(node),
        canvasX: position.x,
        canvasY: position.y,
      }),
    onSuccess: (node) => {
      setError(null)
      setSelectedNodeId(node.id)
      setSelectedLinkId(null)
      invalidate()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось переместить блок"
      )
    },
  })

  const resetMutation = useMutation({
    mutationFn: resetRepairEstimateCatalogCanvasMock,
    onSuccess: () => {
      setError(null)
      setSelectedNodeId(null)
      setSelectedLinkId(null)
      setDraftLinkStart(null)
      invalidate()
    },
  })

  if (canvasQuery.isLoading) {
    return <SectionSkeleton />
  }

  if (!canvasQuery.data) {
    return null
  }

  const data = canvasQuery.data
  const category =
    data.categories.find((item) => item.id === categoryId) ?? null
  const canvasNodes = getCanvasNodes(data, categoryId)
  const selectedNode =
    data.nodes.find((node) => node.id === selectedNodeId) ?? null
  const selectedLink =
    data.links.find((link) => link.id === selectedLinkId) ?? null
  const nodeIds = new Set(canvasNodes.map((node) => node.id))
  const canvasLinks = data.links.filter(
    (link) => nodeIds.has(link.sourceNodeId) && nodeIds.has(link.targetNodeId)
  )
  const createLinkFromAnchors = (
    target: RepairEstimateCatalogNodeDto,
    targetAnchor: CatalogLinkAnchor
  ) => {
    if (draftLinkStart === null) {
      return
    }

    const sourceNode = canvasNodes.find(
      (node) => node.id === draftLinkStart.nodeId
    )
    if (!sourceNode || sourceNode.id === target.id) {
      return
    }
    if (target.nodeType === "CATEGORY") {
      setDraftLinkStart(null)
      setError("Категория не может быть целевым блоком")
      return
    }

    createLinkMutation.mutate({
      sourceNodeId: sourceNode.id,
      targetNodeId: target.id,
      linkType: draftLinkType,
      active: true,
      sortOrder: canvasLinks.length * 10 + 10,
      comment: createCatalogAnchorComment(draftLinkStart.anchor, targetAnchor),
    })
  }

  const openNodeEditor = (node: RepairEstimateCatalogNodeDto) => {
    setNodeDialogState({
      title: "Редактировать блок",
      description: node.code,
      submitLabel: "Сохранить",
      allowTypeSelect: node.nodeType !== "CATEGORY",
      value: createNodeMutation(node),
      save: saveRepairEstimateCatalogCanvasNode,
    })
  }

  if (category === null) {
    return <ErrorBox>Категория не найдена</ErrorBox>
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-lg font-semibold">{category.name}</h2>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            className="w-52 justify-start"
            onClick={() =>
              setNodeDialogState({
                title: "Добавить блок",
                description: category.name,
                submitLabel: "Сохранить",
                allowTypeSelect: true,
                value: createBlankNodeMutation({
                  nodeType: "WORK",
                  parentId: category.id,
                  furnitureCategory: category.furnitureCategory,
                  sortOrder: canvasNodes.length * 10 + 10,
                }),
                save: saveRepairEstimateCatalogCanvasNode,
              })
            }
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Блок
          </Button>
          <div className="w-52">
            <NativeSelect
              value={draftLinkType}
              onChange={(value) =>
                setDraftLinkType(value as RepairEstimateCatalogLinkType)
              }
            >
              {(
                [
                  "FOLLOW_UP",
                  "DEPENDENCY",
                ] satisfies RepairEstimateCatalogLinkType[]
              ).map((type) => (
                <option key={type} value={type}>
                  {repairEstimateCatalogLinkTypeLabel(type)}
                </option>
              ))}
            </NativeSelect>
          </div>
          {draftLinkStart !== null && (
            <Button
              type="button"
              variant="outline"
              onClick={() => setDraftLinkStart(null)}
            >
              Отменить точку
            </Button>
          )}
          <Button
            type="button"
            variant="outline"
            disabled={resetMutation.isPending}
            onClick={() => resetMutation.mutate()}
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Сбросить mock
          </Button>
        </div>
      </div>

      <div className="min-h-[28rem] flex-1 overflow-auto rounded-lg border bg-background">
        <CatalogCanvas
          nodes={canvasNodes}
          links={canvasLinks}
          selectedNodeId={selectedNodeId}
          selectedLinkId={selectedLinkId}
          draftLinkStart={draftLinkStart}
          onSelectNode={(node) => {
            setSelectedNodeId(node.id)
            setSelectedLinkId(null)
            setDraftLinkStart(null)
          }}
          onSelectLink={(link) => {
            setSelectedLinkId(link.id)
            setSelectedNodeId(null)
            setDraftLinkStart(null)
          }}
          onBeginLink={(start) => {
            setError(null)
            setSelectedNodeId(start.nodeId)
            setSelectedLinkId(null)
            setDraftLinkStart(start)
          }}
          onCompleteLink={createLinkFromAnchors}
          onMoveNode={(node, position) =>
            moveNodeMutation.mutate({ node, position })
          }
          onEditNode={openNodeEditor}
          onDeleteLink={(link) => deleteLinkMutation.mutate(link.id)}
        />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="outline"
          disabled={selectedNode === null}
          onClick={() => {
            if (selectedNode === null) {
              return
            }

            openNodeEditor(selectedNode)
          }}
        >
          <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
          Редактировать блок
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={selectedNode === null}
          onClick={() =>
            selectedNode && deleteNodeMutation.mutate(selectedNode.id)
          }
        >
          <HugeiconsIcon icon={Delete01Icon} data-icon="inline-start" />
          Удалить блок
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={selectedLink === null}
          onClick={() => {
            if (selectedLink === null) {
              return
            }

            setLinkDialogState({
              nodes: canvasNodes,
              value: {
                id: selectedLink.id,
                sourceNodeId: selectedLink.sourceNodeId,
                targetNodeId: selectedLink.targetNodeId,
                linkType: selectedLink.linkType,
                active: selectedLink.active,
                sortOrder: selectedLink.sortOrder,
                comment: selectedLink.comment,
              },
            })
          }}
        >
          <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
          Редактировать связь
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={selectedLink === null}
          onClick={() =>
            selectedLink && deleteLinkMutation.mutate(selectedLink.id)
          }
        >
          <HugeiconsIcon icon={Delete01Icon} data-icon="inline-start" />
          Удалить связь
        </Button>
      </div>

      {error !== null && <ErrorBox>{error}</ErrorBox>}

      <NodeEditorDialog
        state={nodeDialogState}
        onClose={() => setNodeDialogState(null)}
        onSaved={() => {
          setError(null)
          invalidate()
        }}
      />
      <LinkEditorDialog
        state={linkDialogState}
        onClose={() => setLinkDialogState(null)}
        onSaved={() => {
          setError(null)
          invalidate()
        }}
      />
    </div>
  )
}

function CatalogItemsTable({
  section,
  categoryId,
  items,
  onEdit,
  onDelete,
}: {
  section: RepairEstimateCatalogSectionDto
  categoryId: string
  items: RepairEstimateCatalogNodeDto[]
  onEdit: (node: RepairEstimateCatalogNodeDto) => void
  onDelete: (node: RepairEstimateCatalogNodeDto) => void
}) {
  const storageKey = `${CATALOG_TABLE_SORT_STORAGE_PREFIX}${section.kind}:${categoryId}`
  const [sortState, setSortState] = useState<CatalogTableSortState | null>(() =>
    readCatalogTableSortState(storageKey)
  )

  const sortedItems = useMemo(() => {
    if (sortState === null) {
      return items
    }

    const originalIndexById = new Map(
      items.map((item, index) => [item.id, index])
    )

    return items.slice().sort((left, right) => {
      const result = compareCatalogTableItems(left, right, sortState)
      if (result !== 0) {
        return result
      }

      return (
        (originalIndexById.get(left.id) ?? 0) -
        (originalIndexById.get(right.id) ?? 0)
      )
    })
  }, [items, sortState])

  const setColumnSort = (column: CatalogTableSortColumn) => {
    setSortState((current) => {
      const next =
        current?.column !== column
          ? ({ column, direction: "asc" } satisfies CatalogTableSortState)
          : current.direction === "asc"
            ? ({ column, direction: "desc" } satisfies CatalogTableSortState)
            : null

      writeCatalogTableSortState(storageKey, next)
      return next
    })
  }

  const renderSortableHeader = (
    column: CatalogTableSortColumn,
    label: string
  ) => {
    const direction = sortState?.column === column ? sortState.direction : null

    return (
      <th
        className="px-3 py-2 text-left font-medium"
        aria-sort={
          direction === "asc"
            ? "ascending"
            : direction === "desc"
              ? "descending"
              : "none"
        }
      >
        <button
          type="button"
          className="flex w-full items-center gap-1 text-left text-inherit"
          onClick={() => setColumnSort(column)}
        >
          <span>{label}</span>
          <span
            className={cn(
              "text-xs text-muted-foreground",
              direction !== null && "text-foreground"
            )}
            aria-hidden
          >
            {direction === "asc" ? "↑" : direction === "desc" ? "↓" : "↕"}
          </span>
        </button>
      </th>
    )
  }

  return (
    <table className="w-full min-w-[56rem] border-collapse text-sm">
      <thead className="sticky top-0 bg-muted text-muted-foreground">
        <tr>
          {renderSortableHeader("name", "Название")}
          {renderSortableHeader("unit", "Ед.")}
          {renderSortableHeader("unitPrice", "Цена")}
          {renderSortableHeader("defaultQuantity", "Кол.")}
          {section.sectionType === "WORK" &&
            renderSortableHeader("durationMinutes", "Мин.")}
          {renderSortableHeader("includeInEstimate", "Смета")}
          {renderSortableHeader("commonItem", "Общий")}
          <th className="px-3 py-2 text-right font-medium">Действия</th>
        </tr>
      </thead>
      <tbody>
        {sortedItems.map((item) => (
          <tr key={item.id} className="border-t">
            <td className="max-w-72 px-3 py-2">
              <div className="truncate font-medium">{item.name}</div>
              {item.comment && (
                <div className="truncate text-xs text-muted-foreground">
                  {item.comment}
                </div>
              )}
            </td>
            <td className="px-3 py-2">{item.unit ?? ""}</td>
            <td className="px-3 py-2">{item.unitPrice ?? ""}</td>
            <td className="px-3 py-2">{item.defaultQuantity}</td>
            {section.sectionType === "WORK" && (
              <td className="px-3 py-2">{item.durationMinutes ?? ""}</td>
            )}
            <td className="px-3 py-2">
              {item.includeInEstimate ? "Да" : "Нет"}
            </td>
            <td className="px-3 py-2">{item.commonItem ? "Да" : "Нет"}</td>
            <td className="px-3 py-2">
              <div className="flex justify-end gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => onEdit(item)}
                >
                  <HugeiconsIcon icon={PencilEdit01Icon} />
                  <span className="sr-only">Редактировать</span>
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => onDelete(item)}
                >
                  <HugeiconsIcon icon={Delete01Icon} />
                  <span className="sr-only">Удалить</span>
                </Button>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function CatalogSectionCategoryEditor({
  action,
  categoryId,
}: {
  action: EstimateCatalogSettingsActionDto
  categoryId: string
}) {
  const kind = getSectionKind(action)
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const sectionQuery = useQuery({
    queryKey: [...ESTIMATE_CATALOG_QUERY_KEY, "section", kind],
    queryFn: () =>
      kind === null
        ? getRepairEstimateWorkCatalogMock()
        : getEstimateActionData(action),
    enabled: kind !== null,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ESTIMATE_CATALOG_QUERY_KEY })
  }

  const deleteMutation = useMutation({
    mutationFn: (id: string) => {
      switch (kind) {
        case "works":
          return deleteRepairEstimateWorkCatalogItem(id)
        case "materials":
          return deleteRepairEstimateMaterialCatalogItem(id)
        case "furniture":
          return deleteRepairEstimateFurnitureCatalogItem(id)
        case null:
          return deleteRepairEstimateWorkCatalogItem(id)
      }
    },
    onSuccess: () => {
      setError(null)
      invalidate()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось удалить запись"
      )
    },
  })

  if (sectionQuery.isLoading) {
    return <SectionSkeleton />
  }

  const section = sectionQuery.data
  if (!section || !isSectionData(section)) {
    return null
  }

  const category =
    section.categories.find((item) => item.id === categoryId) ?? null
  if (category === null) {
    return <ErrorBox>Категория не найдена</ErrorBox>
  }

  const items = getItemsForCategory(section, categoryId)
  const save = (input: RepairEstimateCatalogNodeMutation) => {
    switch (kind) {
      case "works":
        return saveRepairEstimateWorkCatalogItem(input)
      case "materials":
        return saveRepairEstimateMaterialCatalogItem(input)
      case "furniture":
        return saveRepairEstimateFurnitureCatalogItem(input)
      case null:
        return saveRepairEstimateWorkCatalogItem(input)
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-lg font-semibold">{category.name}</h2>
        </div>
        <Button
          type="button"
          onClick={() =>
            setDialogState({
              title: `Добавить: ${section.title}`,
              description: category.name,
              submitLabel: "Сохранить",
              allowTypeSelect: false,
              value: createBlankNodeMutation({
                nodeType: section.sectionType,
                parentId: category.id,
                furnitureCategory: kind === "furniture",
                sortOrder: items.length * 10 + 10,
              }),
              save,
            })
          }
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Добавить
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto rounded-lg border">
        <CatalogItemsTable
          key={`${section.kind}:${categoryId}`}
          section={section}
          categoryId={categoryId}
          items={items}
          onEdit={(node) =>
            setDialogState({
              title: `Редактировать: ${section.title}`,
              description: node.code,
              submitLabel: "Сохранить",
              allowTypeSelect: false,
              value: createNodeMutation(node),
              save,
            })
          }
          onDelete={(node) => deleteMutation.mutate(node.id)}
        />
      </div>

      {error !== null && <ErrorBox>{error}</ErrorBox>}

      <NodeEditorDialog
        state={dialogState}
        onClose={() => setDialogState(null)}
        onSaved={() => {
          setError(null)
          invalidate()
        }}
      />
    </div>
  )
}

function EstimateCategoryEditor({
  action,
  categoryId,
}: {
  action: EstimateCatalogSettingsActionDto
  categoryId: string
}) {
  if (action.id === "repair-estimate-catalog-canvas") {
    return <CatalogCanvasCategoryEditor categoryId={categoryId} />
  }

  return (
    <CatalogSectionCategoryEditor action={action} categoryId={categoryId} />
  )
}

function BreadcrumbTitle({
  action,
  categoryName,
}: {
  action: EstimateCatalogSettingsActionDto
  categoryName?: string
}) {
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
      <span className="text-muted-foreground">Настройка смет</span>
      <span className="text-muted-foreground">/</span>
      <span className="font-medium">{action.title}</span>
      {categoryName && (
        <>
          <span className="text-muted-foreground">/</span>
          <span className="font-medium">{categoryName}</span>
        </>
      )}
    </div>
  )
}

function EstimateDrilldownView({
  screen,
  onBack,
  onOpenCategory,
}: {
  screen: Exclude<EstimateScreen, { level: "root" }>
  onBack: () => void
  onOpenCategory: (categoryId: string) => void
}) {
  const categoryNameQuery = useQuery({
    queryKey: [
      ...ESTIMATE_CATALOG_QUERY_KEY,
      "category-title",
      screen.action.id,
      screen.level === "category" ? screen.categoryId : null,
    ],
    queryFn: () => getEstimateActionData(screen.action),
    enabled: screen.level === "category",
  })

  const categoryName =
    screen.level === "category"
      ? categoryNameQuery.data?.categories.find(
          (category) => category.id === screen.categoryId
        )?.name
      : undefined

  return (
    <section className="flex h-full min-h-0 flex-col overflow-hidden rounded-lg border bg-card">
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <Button
            type="button"
            variant="outline"
            className="items-center gap-1.5"
            onClick={onBack}
          >
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            <span className="leading-none">Назад</span>
          </Button>
          <BreadcrumbTitle action={screen.action} categoryName={categoryName} />
        </div>
        <Badge variant="secondary">{screen.action.legacyViewId}</Badge>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-4">
        {screen.level === "action" ? (
          <EstimateActionCategoryMenu
            action={screen.action}
            onOpenCategory={onOpenCategory}
          />
        ) : (
          <EstimateCategoryEditor
            action={screen.action}
            categoryId={screen.categoryId}
          />
        )}
      </div>
    </section>
  )
}

function RepairMockPanel({
  action,
}: {
  action: RepairSettingsActionDto | null
}) {
  if (action === null) {
    return (
      <div className="rounded-lg border bg-muted/20 p-3 text-sm text-muted-foreground">
        Настройки ремонта пока работают как тестовые mock-действия.
      </div>
    )
  }

  return (
    <div className="rounded-lg border bg-muted/20 p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{action.title}</span>
        <Badge variant="secondary">Тест</Badge>
      </div>
    </div>
  )
}

export function EstimatesRepairsSettingsPage() {
  const [estimateScreen, setEstimateScreen] = useState<EstimateScreen>({
    level: "root",
  })
  const [selectedRepairAction, setSelectedRepairAction] =
    useState<RepairSettingsActionDto | null>(null)

  const catalogCanvasQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-canvas"],
    queryFn: getRepairEstimateCatalogCanvasSettings,
  })

  const workCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-works"],
    queryFn: getRepairEstimateWorkCatalogSettings,
  })

  const materialCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-materials"],
    queryFn: getRepairEstimateMaterialCatalogSettings,
  })

  const furnitureCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-furniture"],
    queryFn: getRepairEstimateFurnitureCatalogSettings,
  })

  const repairSettingsQuery = useQuery({
    queryKey: ["repair-settings", "actions"],
    queryFn: getRepairSettingsActions,
  })

  const estimateActions = useMemo(() => {
    return [
      catalogCanvasQuery.data,
      workCatalogQuery.data,
      materialCatalogQuery.data,
      furnitureCatalogQuery.data,
    ]
      .filter((action): action is EstimateCatalogSettingsActionDto =>
        Boolean(action)
      )
      .sort((left, right) => left.order - right.order)
      .map((action) => ({ ...action, group: "estimate" as const }))
  }, [
    catalogCanvasQuery.data,
    furnitureCatalogQuery.data,
    materialCatalogQuery.data,
    workCatalogQuery.data,
  ])

  const repairActions = useMemo(() => {
    return (repairSettingsQuery.data ?? [])
      .slice()
      .sort((left, right) => left.order - right.order)
      .map((action) => ({ ...action, group: "repair" as const }))
  }, [repairSettingsQuery.data])

  const estimateLoading =
    catalogCanvasQuery.isLoading ||
    workCatalogQuery.isLoading ||
    materialCatalogQuery.isLoading ||
    furnitureCatalogQuery.isLoading

  if (estimateScreen.level !== "root") {
    return (
      <EstimateDrilldownView
        screen={estimateScreen}
        onBack={() => {
          if (estimateScreen.level === "category") {
            setEstimateScreen({
              level: "action",
              action: estimateScreen.action,
            })
            return
          }

          setEstimateScreen({ level: "root" })
        }}
        onOpenCategory={(categoryId) =>
          setEstimateScreen({
            level: "category",
            action: estimateScreen.action,
            categoryId,
          })
        }
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <SettingsSection title="Настройка смет" count={estimateActions.length}>
        {estimateLoading ? (
          <SectionSkeleton />
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {estimateActions.map((action) => (
              <SettingsActionButton
                key={action.id}
                action={action}
                active={false}
                onClick={(selected) => {
                  if (selected.group === "estimate") {
                    setEstimateScreen({ level: "action", action: selected })
                  }
                }}
              />
            ))}
          </div>
        )}
      </SettingsSection>

      <SettingsSection title="Настройка ремонтов" count={repairActions.length}>
        {repairSettingsQuery.isLoading ? (
          <SectionSkeleton />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {repairActions.map((action) => (
                <SettingsActionButton
                  key={action.id}
                  action={action}
                  active={selectedRepairAction?.id === action.id}
                  onClick={(selected) => {
                    if (selected.group === "repair") {
                      setSelectedRepairAction(selected)
                    }
                  }}
                />
              ))}
            </div>
            <RepairMockPanel action={selectedRepairAction} />
          </div>
        )}
      </SettingsSection>
    </div>
  )
}
