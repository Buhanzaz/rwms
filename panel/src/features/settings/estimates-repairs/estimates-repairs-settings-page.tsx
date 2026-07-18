import { useId, useMemo, useRef, useState, type ReactNode } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  ArrowRight01Icon,
  CanvasIcon,
  Delete01Icon,
  PackageIcon,
  PencilEdit01Icon,
  Refresh01Icon,
  WorkIcon,
} from "@hugeicons/core-free-icons"

import {
  deleteRepairEstimateCatalogCanvasLink,
  getRepairEstimateCatalogCanvasSettingsData,
  getRepairEstimateCatalogCanvasSettings,
  saveRepairEstimateCatalogCanvasLink,
  saveRepairEstimateCatalogCanvasNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api"
import {
  deleteRepairEstimateMaterialCatalogItem,
  getRepairEstimateMaterialCatalog,
  getRepairEstimateMaterialCatalogSettings,
  saveRepairEstimateMaterialCatalogItem,
} from "@/features/settings/estimates-repairs/api/repair-estimate-material-catalog-settings-api"
import {
  deleteRepairEstimateWorkCatalogItem,
  getRepairEstimateWorkCatalog,
  getRepairEstimateWorkCatalogSettings,
  saveRepairEstimateWorkCatalogItem,
} from "@/features/settings/estimates-repairs/api/repair-estimate-work-catalog-settings-api"
import {
  activateRepairEstimateCatalog,
  getItemsForCategory,
  getRepairEstimateCatalogSectionItems,
  importRepairEstimateCatalog,
  listRepairEstimateCatalogVersions,
  moveRepairEstimateCatalogCanvasNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import { REPAIR_ESTIMATE_CATALOG_QUERY_KEY } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
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
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Textarea } from "@/components/ui/textarea"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"
import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogLinkType,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogNodeType,
  RepairEstimateCatalogRequest,
  RepairEstimateCatalogSectionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"
import {
  repairEstimateCatalogLinkTypeLabel,
  repairEstimateCatalogNodeTypeLabel,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

type SettingsAction = EstimateCatalogSettingsActionDto & { group: "estimate" }

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

const CATALOG_TABLE_SORT_STORAGE_PREFIX =
  "rwms:repair-estimate-catalog-table-sort:v1:"
const CANVAS_NODE_WIDTH = 256
const CANVAS_NODE_MIN_HEIGHT = 208
const CATALOG_COMMENT_MAX_LENGTH = 2000

function getEstimateActionIcon(id: EstimateCatalogSettingsActionDto["id"]) {
  switch (id) {
    case "repair-estimate-catalog-canvas":
      return CanvasIcon
    case "repair-estimate-catalog-works":
      return WorkIcon
    case "repair-estimate-catalog-materials":
      return PackageIcon
  }
}

function getSectionKind(action: EstimateCatalogSettingsActionDto) {
  switch (action.id) {
    case "repair-estimate-catalog-works":
      return "works"
    case "repair-estimate-catalog-materials":
      return "materials"
    case "repair-estimate-catalog-canvas":
      return null
  }
}

async function getEstimateActionData(
  request: RepairEstimateCatalogRequest,
  action: EstimateCatalogSettingsActionDto
) {
  switch (action.id) {
    case "repair-estimate-catalog-canvas":
      return getRepairEstimateCatalogCanvasSettingsData(request)
    case "repair-estimate-catalog-works":
      return getRepairEstimateWorkCatalog(request)
    case "repair-estimate-catalog-materials":
      return getRepairEstimateMaterialCatalog(request)
  }
}

function isSectionData(
  data: EstimateActionData
): data is RepairEstimateCatalogSectionDto {
  return "kind" in data
}

function SettingsActionButton({
  action,
  active,
  disabled = false,
  onClick,
}: {
  action: SettingsAction
  active: boolean
  disabled?: boolean
  onClick: (action: SettingsAction) => void
}) {
  const Icon = getEstimateActionIcon(action.id)

  return (
    <Button
      type="button"
      variant={active ? "secondary" : "outline"}
      disabled={disabled}
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
  id,
  value,
  onChange,
  children,
  disabled = false,
  className,
}: {
  id?: string
  value: string
  onChange: (value: string) => void
  children: ReactNode
  disabled?: boolean
  className?: string
}) {
  return (
    <select
      id={id}
      className={cn(
        "h-9 w-full rounded-md border border-input bg-input/20 px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:opacity-50 md:text-xs/relaxed",
        className
      )}
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

function toMoneyDecimalInput(value: string): string | null | undefined {
  const normalized = value.trim().replace(",", ".")
  if (normalized === "") {
    return null
  }
  return /^\d+(?:\.\d{0,2})?$/.test(normalized) ? normalized : undefined
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
  const result =
    state.column === "unitPrice"
      ? compareMoneyDecimalValues(left.unitPrice, right.unitPrice)
      : compareCatalogTableValues(
          getCatalogTableSortValue(left, state.column),
          getCatalogTableSortValue(right, state.column)
        )

  return state.direction === "asc" ? result : -result
}

function compareMoneyDecimalValues(left: string | null, right: string | null) {
  if (left === null || right === null) {
    if (left === right) {
      return 0
    }
    return left === null ? 1 : -1
  }

  const toMinor = (value: string) => {
    const [whole, fraction = ""] = value.split(".")
    return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"))
  }
  const leftMinor = toMinor(left)
  const rightMinor = toMinor(right)
  return leftMinor < rightMinor ? -1 : leftMinor > rightMinor ? 1 : 0
}

function getCatalogLinkAnchors(
  link: RepairEstimateCatalogLinkDto | RepairEstimateCatalogLinkMutation,
  source?: { y: number } | null,
  target?: { y: number } | null
) {
  if (link.canvasAnchors) {
    return {
      sourceAnchor: link.canvasAnchors.source,
      targetAnchor: link.canvasAnchors.target,
    }
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
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes,
    showInMainMenu: node.showInMainMenu,
    routeQueueKind: node.routeQueueKind,
    routing: node.routing,
    references: node.references,
    mediaReferences: node.mediaReferences,
    photoRequired: node.photoRequired,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    comment: node.comment,
  }
}

function createBlankNodeMutation({
  nodeType,
  parentId,
}: {
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
}): RepairEstimateCatalogNodeMutation {
  const priced = nodeType === "WORK" || nodeType === "MATERIAL"

  return {
    code: "",
    name: "",
    nodeType,
    parentId,
    active: true,
    unit: priced ? "шт." : null,
    unitPrice: priced ? "0.00" : null,
    durationMinutes: nodeType === "WORK" ? 60 : null,
    showInMainMenu: false,
    routeQueueKind: null,
    photoRequired: false,
    includeInEstimate: priced,
    commonItem: false,
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
  const fieldId = useId()

  return (
    <Field orientation="horizontal" data-disabled={disabled}>
      <Checkbox
        id={fieldId}
        aria-label={title}
        checked={checked}
        disabled={disabled}
        onCheckedChange={(value) => onCheckedChange(value === true)}
      />
      <FieldContent>
        <FieldLabel htmlFor={fieldId}>{title}</FieldLabel>
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
  const fieldIdPrefix = useId()
  const [draft, setDraft] = useState(state.value)
  const [error, setError] = useState<string | null>(null)
  const priced = draft.nodeType === "WORK" || draft.nodeType === "MATERIAL"
  const commentLength = (draft.comment ?? "").length
  const commentTooLong = commentLength > CATALOG_COMMENT_MAX_LENGTH

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

        <form
          className="contents"
          onSubmit={(event) => {
            event.preventDefault()
            if (!mutation.isPending && !commentTooLong) {
              mutation.mutate(draft)
            }
          }}
        >
          <FieldGroup>
            {state.allowTypeSelect && (
              <Field>
                <FieldLabel htmlFor={`${fieldIdPrefix}-type`}>Тип</FieldLabel>
                <NativeSelect
                  id={`${fieldIdPrefix}-type`}
                  value={draft.nodeType}
                  onChange={(value) =>
                    setDraft((current) => ({
                      ...current,
                      nodeType: value as RepairEstimateCatalogNodeType,
                      includeInEstimate:
                        value === "WORK" || value === "MATERIAL",
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
                      photoRequired:
                        value === "WORK" ? current.photoRequired : false,
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

            <Field>
              <FieldLabel htmlFor={`${fieldIdPrefix}-name`}>
                Название
              </FieldLabel>
              <Input
                id={`${fieldIdPrefix}-name`}
                value={draft.name}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    name: event.target.value,
                  }))
                }
              />
            </Field>

            <div className="grid gap-3 md:grid-cols-2">
              <Field>
                <FieldLabel htmlFor={`${fieldIdPrefix}-unit`}>
                  Единица
                </FieldLabel>
                <Input
                  id={`${fieldIdPrefix}-unit`}
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
                <FieldLabel htmlFor={`${fieldIdPrefix}-unit-price`}>
                  Цена
                </FieldLabel>
                <Input
                  id={`${fieldIdPrefix}-unit-price`}
                  value={draft.unitPrice ?? ""}
                  disabled={!priced}
                  inputMode="decimal"
                  onChange={(event) => {
                    const unitPrice = toMoneyDecimalInput(event.target.value)
                    if (unitPrice === undefined) {
                      return
                    }
                    setDraft((current) => ({
                      ...current,
                      unitPrice,
                    }))
                  }}
                />
              </Field>
            </div>

            <div className="grid gap-3 md:grid-cols-2">
              {draft.nodeType === "WORK" && (
                <Field>
                  <FieldLabel htmlFor={`${fieldIdPrefix}-duration`}>
                    Длительность, мин
                  </FieldLabel>
                  <Input
                    id={`${fieldIdPrefix}-duration`}
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

            <FieldGroup className="grid gap-2 md:grid-cols-2">
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
              <BooleanField
                title="Показывать в главном меню"
                checked={draft.showInMainMenu}
                onCheckedChange={(checked) =>
                  setDraft((current) => ({
                    ...current,
                    showInMainMenu: checked,
                  }))
                }
              />
              {draft.nodeType === "WORK" && (
                <BooleanField
                  title="Фото обязательно"
                  checked={draft.photoRequired}
                  onCheckedChange={(checked) =>
                    setDraft((current) => ({
                      ...current,
                      photoRequired: checked,
                    }))
                  }
                />
              )}
            </FieldGroup>

            <Field data-invalid={commentTooLong || undefined}>
              <FieldLabel htmlFor={`${fieldIdPrefix}-comment`}>
                Комментарий
              </FieldLabel>
              <Textarea
                id={`${fieldIdPrefix}-comment`}
                value={draft.comment ?? ""}
                aria-invalid={commentTooLong || undefined}
                aria-describedby={`${fieldIdPrefix}-comment-limit`}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    comment: event.target.value,
                  }))
                }
              />
              <FieldDescription id={`${fieldIdPrefix}-comment-limit`}>
                {commentLength} из {CATALOG_COMMENT_MAX_LENGTH} символов
              </FieldDescription>
              {commentTooLong && (
                <FieldError>
                  Максимальная длина комментария — {CATALOG_COMMENT_MAX_LENGTH}{" "}
                  символов. Сократите текст перед сохранением.
                </FieldError>
              )}
            </Field>

            {error !== null && <ErrorBox>{error}</ErrorBox>}
          </FieldGroup>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Отмена
            </Button>
            <Button
              type="submit"
              disabled={mutation.isPending || commentTooLong}
            >
              {state.submitLabel}
            </Button>
          </DialogFooter>
        </form>
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
  request,
  action,
  onOpenCategory,
}: {
  request: RepairEstimateCatalogRequest
  action: EstimateCatalogSettingsActionDto
  onOpenCategory: (categoryId: string) => void
}) {
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const dataQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      request.warehouseId,
      request.catalogVersionId,
      "action-menu",
      action.id,
    ],
    queryFn: () => getEstimateActionData(request, action),
  })

  if (dataQuery.isLoading) {
    return <SectionSkeleton />
  }

  if (dataQuery.error) {
    return (
      <ErrorBox>
        {dataQuery.error instanceof Error
          ? dataQuery.error.message
          : "Не удалось загрузить каталог."}
      </ErrorBox>
    )
  }

  if (!dataQuery.data) {
    return null
  }

  const canCreateCategory = action.id === "repair-estimate-catalog-canvas"
  const nodeCount = isSectionData(dataQuery.data)
    ? getRepairEstimateCatalogSectionItems(dataQuery.data).length
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
                }),
                save: (input) =>
                  saveRepairEstimateCatalogCanvasNode(request, input),
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
            queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
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

function getCanvasNodeHeight(
  node: RepairEstimateCatalogNodeDto,
  nodeHeights: Record<string, number>
) {
  return nodeHeights[node.id] ?? CANVAS_NODE_MIN_HEIGHT
}

function getCanvasPosition(
  node: RepairEstimateCatalogNodeDto,
  index: number,
  nodes: RepairEstimateCatalogNodeDto[],
  nodeHeights: Record<string, number>
) {
  if (node.nodeType === "CATEGORY") {
    return {
      x: node.canvasX ?? 40,
      y: node.canvasY ?? 40,
    }
  }

  const row = Math.floor(index / 3)
  const rowsBefore = Array.from({ length: row }, (_, rowIndex) =>
    nodes.slice(rowIndex * 3, rowIndex * 3 + 3)
  )
  const defaultY =
    270 +
    rowsBefore.reduce(
      (offset, rowNodes) =>
        offset +
        Math.max(
          CANVAS_NODE_MIN_HEIGHT,
          ...rowNodes.map((rowNode) =>
            getCanvasNodeHeight(rowNode, nodeHeights)
          )
        ) +
        52,
      0
    )

  return {
    x: node.canvasX ?? 40 + (index % 3) * 340,
    y: node.canvasY ?? defaultY,
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
  draftLinkType,
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
  draftLinkType: RepairEstimateCatalogLinkType
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
  const canvasRef = useRef<HTMLDivElement | null>(null)
  const dragRef = useRef<CanvasDragState | null>(null)
  const suppressClickRef = useRef(false)
  const [dragPositions, setDragPositions] = useState<
    Record<string, CanvasPosition>
  >({})
  const [nodeHeights, setNodeHeights] = useState<Record<string, number>>({})
  const [activeDragNodeId, setActiveDragNodeId] = useState<string | null>(null)
  const [linkPreviewPoint, setLinkPreviewPoint] =
    useState<CanvasPosition | null>(null)
  const positionedNodes = nodes.map((node, index) => ({
    node,
    position: {
      ...(dragPositions[node.id] ??
        getCanvasPosition(node, index, nodes, nodeHeights)),
      width: CANVAS_NODE_WIDTH,
      height: getCanvasNodeHeight(node, nodeHeights),
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

  const getPointerPosition = (event: { clientX: number; clientY: number }) => {
    const rect = canvasRef.current?.getBoundingClientRect()
    if (!rect) {
      return null
    }

    return {
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
    }
  }

  return (
    <div
      ref={canvasRef}
      className="relative"
      style={{ width, height }}
      onPointerMove={(event) => {
        if (draftLinkStart === null) {
          return
        }

        const point = getPointerPosition(event)
        if (point !== null) {
          setLinkPreviewPoint(point)
        }
      }}
    >
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

      {(activeDragNodeId !== null ||
        selectedLinkId !== null ||
        (draftLinkStart !== null && linkPreviewPoint !== null)) && (
        <svg className="pointer-events-none absolute inset-0 z-20 h-full w-full overflow-visible">
          <defs>
            <marker
              id="catalog-arrow-end-foreground"
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
            const selected = link.id === selectedLinkId
            const attachedToDraggedNode =
              activeDragNodeId !== null &&
              (link.sourceNodeId === activeDragNodeId ||
                link.targetNodeId === activeDragNodeId)
            if (!selected && !attachedToDraggedNode) {
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
            const sourcePoint = anchorPoint(source, sourceAnchor)
            const targetPoint = anchorPoint(target, targetAnchor)
            const dependency = link.linkType === "DEPENDENCY"

            return (
              <path
                key={`foreground-${link.id}`}
                d={pathD(sourcePoint, targetPoint, sourceAnchor, targetAnchor)}
                fill="none"
                stroke="currentColor"
                strokeWidth={selected ? 4 : 3.5}
                strokeLinecap="round"
                strokeDasharray={dependency ? undefined : "8 8"}
                markerStart={
                  dependency ? "url(#catalog-arrow-end-foreground)" : undefined
                }
                markerEnd="url(#catalog-arrow-end-foreground)"
                className="text-primary drop-shadow-sm"
              />
            )
          })}
          {draftLinkStart !== null &&
            linkPreviewPoint !== null &&
            (() => {
              const source = positionById.get(draftLinkStart.nodeId)
              if (!source) {
                return null
              }

              const sourcePoint = anchorPoint(source, draftLinkStart.anchor)
              const targetAnchor =
                linkPreviewPoint.y < sourcePoint.y ? "BOTTOM" : "TOP"
              const dependency = draftLinkType === "DEPENDENCY"

              return (
                <path
                  key="link-preview"
                  data-catalog-link-preview="true"
                  d={pathD(
                    sourcePoint,
                    linkPreviewPoint,
                    draftLinkStart.anchor,
                    targetAnchor
                  )}
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={3.5}
                  strokeLinecap="round"
                  strokeDasharray={dependency ? undefined : "8 8"}
                  markerStart={
                    dependency
                      ? "url(#catalog-arrow-end-foreground)"
                      : undefined
                  }
                  markerEnd="url(#catalog-arrow-end-foreground)"
                  className="text-primary drop-shadow-sm"
                />
              )
            })()}
        </svg>
      )}

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
            className="group/delete-link absolute z-30 -translate-x-1/2 -translate-y-1/2 border-border bg-background/95 text-muted-foreground shadow-md hover:border-foreground/70 hover:bg-muted/50 hover:text-foreground/80"
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
            <HugeiconsIcon
              icon={Delete01Icon}
              data-icon="inline-start"
              className="text-muted-foreground transition-colors group-hover/delete-link:text-destructive"
            />
            <span>Удалить</span>
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
            minHeight: CANVAS_NODE_MIN_HEIGHT,
          }}
          ref={(element) => {
            if (element === null) {
              return
            }

            const height = Math.ceil(element.getBoundingClientRect().height)
            setNodeHeights((current) =>
              current[node.id] === height
                ? current
                : { ...current, [node.id]: height }
            )
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
            setActiveDragNodeId(node.id)
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
                setActiveDragNodeId(null)
                return
              }
              setActiveDragNodeId(null)
            }

            if (draftLinkStart === null || draftLinkStart.nodeId === node.id) {
              return
            }

            const rect = event.currentTarget.getBoundingClientRect()
            const targetAnchor =
              event.clientY <= rect.top + rect.height / 2 ? "TOP" : "BOTTOM"
            setLinkPreviewPoint(null)
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
              setActiveDragNodeId(null)
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
                  setLinkPreviewPoint(anchorPoint(position, anchor))
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
                  setLinkPreviewPoint(null)
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
            <span>Единица: {node.unit ?? ""}</span>
            <span>Активно: {node.active ? "Да" : "Нет"}</span>
            {node.commonItem && <span>Общий</span>}
            {node.comment && (
              <span className="break-words whitespace-pre-wrap">
                {node.comment}
              </span>
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

function CatalogCanvasCategoryEditor({
  request,
  categoryId,
}: {
  request: RepairEstimateCatalogRequest
  categoryId: string
}) {
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
  const [error, setError] = useState<string | null>(null)

  const canvasQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      request.warehouseId,
      request.catalogVersionId,
      "canvas",
    ],
    queryFn: () => getRepairEstimateCatalogCanvasSettingsData(request),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({
      queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    })
  }

  const deleteLinkMutation = useMutation({
    mutationFn: (id: string) =>
      deleteRepairEstimateCatalogCanvasLink(request, id),
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
    mutationFn: (input: RepairEstimateCatalogLinkMutation) =>
      saveRepairEstimateCatalogCanvasLink(request, input),
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
      nodeId,
      position,
    }: {
      nodeId: string
      position: CanvasPosition
    }) => moveRepairEstimateCatalogCanvasNode(request, nodeId, position),
    onSuccess: (_position, variables) => {
      setError(null)
      setSelectedNodeId(variables.nodeId)
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

  if (canvasQuery.isLoading) {
    return <SectionSkeleton />
  }

  if (canvasQuery.error) {
    return (
      <ErrorBox>
        {canvasQuery.error instanceof Error
          ? canvasQuery.error.message
          : "Не удалось загрузить граф каталога."}
      </ErrorBox>
    )
  }

  if (!canvasQuery.data) {
    return null
  }

  const data = canvasQuery.data
  const category =
    data.categories.find((item) => item.id === categoryId) ?? null
  const canvasNodes = getCanvasNodes(data, categoryId)
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
      sortOrder: canvasLinks.length * 10 + 10,
      canvasAnchors: {
        source: draftLinkStart.anchor,
        target: targetAnchor,
      },
    })
  }

  const openNodeEditor = (node: RepairEstimateCatalogNodeDto) => {
    setNodeDialogState({
      title: "Редактировать блок",
      description: node.name,
      submitLabel: "Сохранить",
      allowTypeSelect: node.nodeType !== "CATEGORY",
      value: createNodeMutation(node),
      save: (input) => saveRepairEstimateCatalogCanvasNode(request, input),
    })
  }

  if (category === null) {
    return <ErrorBox>Категория не найдена</ErrorBox>
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-col gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-lg font-semibold">{category.name}</h2>
        </div>
        <div className="grid gap-2 sm:grid-cols-[repeat(auto-fit,minmax(13rem,13rem))]">
          <Button
            type="button"
            className="h-9 w-52 justify-start md:h-9"
            onClick={() =>
              setNodeDialogState({
                title: "Добавить блок",
                description: category.name,
                submitLabel: "Сохранить",
                allowTypeSelect: true,
                value: createBlankNodeMutation({
                  nodeType: "WORK",
                  parentId: category.id,
                }),
                save: (input) =>
                  saveRepairEstimateCatalogCanvasNode(request, input),
              })
            }
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Блок
          </Button>
          <div className="w-52">
            <NativeSelect
              className="h-9 md:h-9"
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
              className="h-9 w-52 justify-start md:h-9"
              onClick={() => setDraftLinkStart(null)}
            >
              Отменить точку
            </Button>
          )}
        </div>
      </div>

      <div className="min-h-[28rem] flex-1 overflow-auto rounded-lg border bg-background">
        <CatalogCanvas
          nodes={canvasNodes}
          links={canvasLinks}
          selectedNodeId={selectedNodeId}
          selectedLinkId={selectedLinkId}
          draftLinkType={draftLinkType}
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
            moveNodeMutation.mutate({ nodeId: node.id, position })
          }
          onEditNode={openNodeEditor}
          onDeleteLink={(link) => deleteLinkMutation.mutate(link.id)}
        />
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
                <div className="text-xs break-words whitespace-pre-wrap text-muted-foreground">
                  {item.comment}
                </div>
              )}
            </td>
            <td className="px-3 py-2">{item.unit ?? ""}</td>
            <td className="px-3 py-2">{item.unitPrice ?? ""}</td>
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
  request,
  action,
  categoryId,
}: {
  request: RepairEstimateCatalogRequest
  action: EstimateCatalogSettingsActionDto
  categoryId: string
}) {
  const kind = getSectionKind(action)
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const sectionQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      request.warehouseId,
      request.catalogVersionId,
      "section",
      kind,
    ],
    queryFn: () =>
      kind === null
        ? getRepairEstimateWorkCatalog(request)
        : getEstimateActionData(request, action),
    enabled: kind !== null,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({
      queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    })
  }

  const deleteMutation = useMutation({
    mutationFn: (id: string) => {
      switch (kind) {
        case "works":
          return deleteRepairEstimateWorkCatalogItem(request, id)
        case "materials":
          return deleteRepairEstimateMaterialCatalogItem(request, id)
        case null:
          return deleteRepairEstimateWorkCatalogItem(request, id)
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

  if (sectionQuery.error) {
    return (
      <ErrorBox>
        {sectionQuery.error instanceof Error
          ? sectionQuery.error.message
          : "Не удалось загрузить раздел каталога."}
      </ErrorBox>
    )
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
        return saveRepairEstimateWorkCatalogItem(request, input)
      case "materials":
        return saveRepairEstimateMaterialCatalogItem(request, input)
      case null:
        return saveRepairEstimateWorkCatalogItem(request, input)
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
              description: node.name,
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
  request,
  action,
  categoryId,
}: {
  request: RepairEstimateCatalogRequest
  action: EstimateCatalogSettingsActionDto
  categoryId: string
}) {
  if (action.id === "repair-estimate-catalog-canvas") {
    return (
      <CatalogCanvasCategoryEditor request={request} categoryId={categoryId} />
    )
  }

  return (
    <CatalogSectionCategoryEditor
      request={request}
      action={action}
      categoryId={categoryId}
    />
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
  request,
  screen,
  onBack,
  onOpenCategory,
}: {
  request: RepairEstimateCatalogRequest
  screen: Exclude<EstimateScreen, { level: "root" }>
  onBack: () => void
  onOpenCategory: (categoryId: string) => void
}) {
  const categoryNameQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      request.warehouseId,
      request.catalogVersionId,
      "category-title",
      screen.action.id,
      screen.level === "category" ? screen.categoryId : null,
    ],
    queryFn: () => getEstimateActionData(request, screen.action),
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
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-4">
        {screen.level === "action" ? (
          <EstimateActionCategoryMenu
            request={request}
            action={screen.action}
            onOpenCategory={onOpenCategory}
          />
        ) : (
          <EstimateCategoryEditor
            request={request}
            action={screen.action}
            categoryId={screen.categoryId}
          />
        )}
      </div>
    </section>
  )
}

export function EstimatesRepairsSettingsPage() {
  const { accessToken } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const [estimateScreen, setEstimateScreen] = useState<EstimateScreen>({
    level: "root",
  })
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(
    null
  )
  const [commandError, setCommandError] = useState<string | null>(null)
  const importInputRef = useRef<HTMLInputElement>(null)

  const versionsQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      selectedWarehouseId,
      "versions",
    ],
    queryFn: () =>
      listRepairEstimateCatalogVersions(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
  })

  const selectedVersion = useMemo(() => {
    const versions = versionsQuery.data ?? []
    return (
      versions.find((version) => version.id === selectedVersionId) ??
      versions.find((version) => version.lifecycle === "DRAFT") ??
      versions.find((version) => version.lifecycle === "ACTIVE") ??
      versions[0] ??
      null
    )
  }, [selectedVersionId, versionsQuery.data])

  const catalogRequest = useMemo<RepairEstimateCatalogRequest | null>(() => {
    if (!accessToken || !selectedWarehouseId || !selectedVersion) return null
    return {
      accessToken,
      warehouseId: selectedWarehouseId,
      catalogVersionId: selectedVersion.id,
    }
  }, [accessToken, selectedVersion, selectedWarehouseId])

  const refreshCatalog = () => {
    void queryClient.invalidateQueries({
      queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    })
  }

  const importMutation = useMutation({
    mutationFn: async (file: File) => {
      if (!accessToken || !selectedWarehouseId) {
        throw new Error("Не выбран склад или отсутствует токен доступа.")
      }
      return importRepairEstimateCatalog(
        accessToken,
        selectedWarehouseId,
        await file.text()
      )
    },
    onSuccess: (version) => {
      setCommandError(null)
      setSelectedVersionId(version.id)
      setEstimateScreen({ level: "root" })
      refreshCatalog()
    },
    onError: (error) => {
      setCommandError(
        error instanceof Error
          ? error.message
          : "Не удалось импортировать каталог."
      )
    },
  })

  const activationMutation = useMutation({
    mutationFn: () => {
      if (!catalogRequest || !selectedVersion) {
        throw new Error("Версия каталога не выбрана.")
      }
      return activateRepairEstimateCatalog(
        catalogRequest,
        selectedVersion.version
      )
    },
    onSuccess: (version) => {
      setCommandError(null)
      setSelectedVersionId(version.id)
      setEstimateScreen({ level: "root" })
      refreshCatalog()
    },
    onError: (error) => {
      setCommandError(
        error instanceof Error
          ? error.message
          : "Не удалось активировать каталог."
      )
    },
  })

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

  const estimateActions = useMemo(() => {
    return [
      catalogCanvasQuery.data,
      workCatalogQuery.data,
      materialCatalogQuery.data,
    ]
      .filter((action): action is EstimateCatalogSettingsActionDto =>
        Boolean(action)
      )
      .sort((left, right) => left.order - right.order)
      .map((action) => ({ ...action, group: "estimate" as const }))
  }, [
    catalogCanvasQuery.data,
    materialCatalogQuery.data,
    workCatalogQuery.data,
  ])

  const estimateLoading =
    catalogCanvasQuery.isLoading ||
    workCatalogQuery.isLoading ||
    materialCatalogQuery.isLoading

  if (estimateScreen.level !== "root") {
    if (!catalogRequest || selectedVersion?.lifecycle !== "DRAFT") {
      return <ErrorBox>Выберите черновую версию каталога.</ErrorBox>
    }
    return (
      <EstimateDrilldownView
        request={catalogRequest}
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
      <section className="rounded-lg border bg-card p-4">
        <div className="flex flex-wrap items-end gap-3">
          <Field className="min-w-72 flex-1">
            <FieldLabel htmlFor="maintenance-catalog-version">
              Версия каталога
            </FieldLabel>
            <NativeSelect
              id="maintenance-catalog-version"
              value={selectedVersion?.id ?? ""}
              disabled={!versionsQuery.data?.length}
              onChange={(value) => {
                setSelectedVersionId(value)
                setEstimateScreen({ level: "root" })
                setCommandError(null)
              }}
            >
              {!versionsQuery.data?.length && (
                <option value="">Версий пока нет</option>
              )}
              {(versionsQuery.data ?? []).map((version) => (
                <option key={version.id} value={version.id}>
                  {version.lifecycle} · v{version.version} · {version.nodeCount}{" "}
                  узлов
                </option>
              ))}
            </NativeSelect>
          </Field>

          <Button
            type="button"
            variant="outline"
            onClick={() => refreshCatalog()}
            disabled={versionsQuery.isFetching}
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Обновить
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => importInputRef.current?.click()}
            disabled={
              !accessToken || !selectedWarehouseId || importMutation.isPending
            }
          >
            Импортировать JSON
          </Button>
          <input
            ref={importInputRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) importMutation.mutate(file)
              event.currentTarget.value = ""
            }}
          />
          <Button
            type="button"
            onClick={() => activationMutation.mutate()}
            disabled={
              selectedVersion?.lifecycle !== "DRAFT" ||
              activationMutation.isPending
            }
          >
            Активировать
          </Button>
        </div>

        {selectedVersion?.lifecycle === "ACTIVE" && (
          <p className="mt-3 text-sm text-muted-foreground">
            Активная версия доступна рабочим экранам только для чтения. Для
            изменений импортируйте или выберите черновик.
          </p>
        )}
        {!accessToken && (
          <ErrorBox>Не получен токен доступа к maintenance-service.</ErrorBox>
        )}
        {accessToken && !selectedWarehouseId && (
          <ErrorBox>Выберите склад для каталога ремонта.</ErrorBox>
        )}
        {versionsQuery.error && (
          <ErrorBox>
            {versionsQuery.error instanceof Error
              ? versionsQuery.error.message
              : "Не удалось загрузить версии каталога."}
          </ErrorBox>
        )}
        {commandError && <ErrorBox>{commandError}</ErrorBox>}
      </section>

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
                disabled={selectedVersion?.lifecycle !== "DRAFT"}
                onClick={(selected) => {
                  if (selectedVersion?.lifecycle !== "DRAFT") return
                  setEstimateScreen({ level: "action", action: selected })
                }}
              />
            ))}
          </div>
        )}
      </SettingsSection>
    </div>
  )
}
