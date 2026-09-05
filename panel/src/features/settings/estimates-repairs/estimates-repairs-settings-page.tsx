import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { useSearchParams } from "react-router-dom"
import { toast } from "sonner"
import { ApiError } from "@/lib/api-client"
import {
  Add01Icon,
  AlertCircleIcon,
  CanvasIcon,
  ColorPickerIcon,
  DatabaseAddIcon,
  Delete01Icon,
  FloppyDiskIcon,
  Loading03Icon,
  PackageIcon,
  PencilEdit01Icon,
  Refresh01Icon,
  Sofa01Icon,
  WorkIcon,
} from "@hugeicons/core-free-icons"

import {
  getRepairEstimateCatalogCanvasSettingsData,
  getRepairEstimateCatalogCanvasSettings,
  saveRepairEstimateCatalogCanvasNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api"
import {
  deleteRepairEstimateFurnitureCatalogItem,
  getRepairEstimateFurnitureCatalog,
  getRepairEstimateFurnitureCatalogSettings,
  saveRepairEstimateFurnitureCatalogItem,
} from "@/features/settings/estimates-repairs/api/repair-estimate-furniture-catalog-settings-api"
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
  createRepairEstimateCatalog,
  getCurrentRepairEstimateCatalog,
  getItemsForCategory,
  getRepairEstimateCatalogSnapshot,
  getRepairEstimateCatalogSectionItems,
  REPAIR_ESTIMATE_CATALOG_DISPLAY_COLOR_GROUPS,
  saveRepairEstimateCatalogDisplayColors,
  saveRepairEstimateCatalogCanvasChanges,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import {
  getRepairComplexityColors,
  saveRepairComplexityColors,
  type RepairComplexityColorsDto,
} from "@/features/settings/estimates-repairs/api/repair-complexity-colors-api"
import type {
  RepairEstimateCatalogDisplayColorGroup,
  RepairEstimateCatalogDisplayColors,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import {
  taskBoardSettingsClient,
  taskBoardSettingsKeys,
} from "@/features/settings/task-board/api/task-board-settings-api"
import {
  queueTypeLabels,
  type QueueDefinitionDto,
} from "@/features/settings/task-board/model/task-board-settings"
import { getCabinSettings } from "@/features/rental-items/api/asset-rental-items-api"
import { REPAIR_ESTIMATE_CATALOG_QUERY_KEY } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import { formatMoneyDecimal } from "@/features/repair-estimates/domain/repair-estimate-domain"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import { cn } from "@/lib/utils"
import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogCanvasChangeSet,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogLinkType,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogNodeType,
  RepairEstimateCatalogRequest,
  RepairEstimateCatalogRouteQueueKind,
  RepairEstimateCatalogSectionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"
import {
  repairEstimateCatalogLinkTypeLabel,
  repairEstimateCatalogNodeTypeLabel,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

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

type CommonCatalogKind = "works" | "materials"

type CatalogLinkAnchor = "TOP" | "BOTTOM"

type CatalogLinkStart = {
  nodeId: string
  anchor: CatalogLinkAnchor
}

type PendingCatalogLink = {
  draftId: string
  input: RepairEstimateCatalogLinkMutation
}

type NodeDialogState = {
  title: string
  description: string
  submitLabel: string
  allowTypeSelect: boolean
  value: RepairEstimateCatalogNodeMutation
  save: (
    input: RepairEstimateCatalogNodeMutation
  ) => Promise<RepairEstimateCatalogNodeDto>
  request: RepairEstimateCatalogRequest
  furnitureTree: boolean
}

const CATALOG_TABLE_SORT_STORAGE_PREFIX =
  "rwms:repair-estimate-catalog-table-sort:v1:"
const CANVAS_NODE_WIDTH = 256
const CANVAS_NODE_MIN_HEIGHT = 208
const CANVAS_ANCHOR_INSET = 1
const CATALOG_COMMENT_MAX_LENGTH = 2000
const NO_ROUTE_QUEUE_VALUE = "__NO_ROUTE_QUEUE__"
const DISPLAY_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/
const DISPLAY_COLOR_PICKER_FALLBACK = "#ABB6B9"
const REPAIR_COMPLEXITY_COLOR_FIELDS = [
  { key: "lightColor", label: "Лёгкий ремонт" },
  { key: "mediumColor", label: "Средний ремонт" },
  { key: "complexColor", label: "Тяжёлый ремонт" },
  { key: "capitalColor", label: "Капитальный ремонт" },
] as const

const REPAIR_ESTIMATE_CATALOG_COLOR_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-colors",
    title: "Цветовая индикация кнопок",
    sectionType: "COLORS",
    categoryScope: "ALL",
    order: 5,
  }

const REPAIR_ESTIMATE_CATALOG_COMMON_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-common",
    title: "Общее",
    sectionType: "WORK",
    categoryScope: "NON_FURNITURE",
    order: 50,
  }

const DISPLAY_COLOR_GROUP_COPY: Record<
  RepairEstimateCatalogDisplayColorGroup,
  { title: string; description: string }
> = {
  CATEGORY: {
    title: "Категории",
    description: "Верхний уровень каталога, кроме ветки мебели.",
  },
  SUBCATEGORY: {
    title: "Подкатегории",
    description: "Вложенные разделы каталога.",
  },
  WORK: {
    title: "Работы",
    description: "Кнопки работ в каталоге.",
  },
  MATERIAL: {
    title: "Материалы",
    description: "Кнопки материалов в каталоге.",
  },
  FURNITURE: {
    title: "Мебель",
    description: "Вся ветка мебели, включая её категории и позиции.",
  },
  OPTION: {
    title: "Опции",
    description: "Дополнительные варианты и переходы каталога.",
  },
  LOCATION: {
    title: "Расположение",
    description: "Кнопки местоположения материалов.",
  },
}

function getEstimateActionIcon(id: EstimateCatalogSettingsActionDto["id"]) {
  switch (id) {
    case "repair-estimate-catalog-colors":
      return ColorPickerIcon
    case "repair-estimate-catalog-canvas":
      return CanvasIcon
    case "repair-estimate-catalog-works":
      return WorkIcon
    case "repair-estimate-catalog-materials":
      return PackageIcon
    case "repair-estimate-catalog-furniture":
      return Sofa01Icon
    case "repair-estimate-catalog-common":
      return PackageIcon
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
    case "repair-estimate-catalog-common":
      return null
    case "repair-estimate-catalog-canvas":
    case "repair-estimate-catalog-colors":
      return null
  }
}

async function getEstimateActionData(
  request: RepairEstimateCatalogRequest,
  action: EstimateCatalogSettingsActionDto
) {
  switch (action.id) {
    case "repair-estimate-catalog-colors":
      return getRepairEstimateCatalogCanvasSettingsData(request)
    case "repair-estimate-catalog-canvas":
      return getRepairEstimateCatalogCanvasSettingsData(request)
    case "repair-estimate-catalog-works":
      return getRepairEstimateWorkCatalog(request)
    case "repair-estimate-catalog-materials":
      return getRepairEstimateMaterialCatalog(request)
    case "repair-estimate-catalog-furniture":
      return getRepairEstimateFurnitureCatalog(request)
    case "repair-estimate-catalog-common":
      return getRepairEstimateWorkCatalog(request)
  }
}

function isSectionData(
  data: EstimateActionData
): data is RepairEstimateCatalogSectionDto {
  return "kind" in data
}

function EstimateActionNavigation({
  actions,
  activeActionId,
  onSelect,
}: {
  actions: EstimateCatalogSettingsActionDto[]
  activeActionId: EstimateCatalogSettingsActionDto["id"] | null
  onSelect: (action: EstimateCatalogSettingsActionDto) => void
}) {
  return (
    <nav aria-label="Разделы каталога смет">
      <Tabs
        value={activeActionId ?? ""}
        onValueChange={(actionId) => {
          if (!actionId) {
            return
          }

          const action = actions.find((candidate) => candidate.id === actionId)
          if (action) {
            onSelect(action)
          }
        }}
      >
        <div className="max-w-full overflow-x-auto pb-1">
          <TabsList className="min-w-max bg-muted/75 shadow-xs backdrop-blur-md">
            {actions.map((action) => {
              const Icon = getEstimateActionIcon(action.id)

              return (
                <TabsTrigger key={action.id} value={action.id}>
                  <HugeiconsIcon icon={Icon} data-icon="inline-start" />
                  <span className="truncate">{action.title}</span>
                </TabsTrigger>
              )
            })}
          </TabsList>
        </div>
      </Tabs>
    </nav>
  )
}

function SectionSkeleton({
  label = "Загружаем данные каталога…",
}: {
  label?: string
}) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">{label}</p>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <Skeleton key={index} className="h-16 rounded-md" />
        ))}
      </div>
    </div>
  )
}

function CatalogNavigationState({
  kind,
  title,
  description,
  children,
}: {
  kind: "empty" | "error"
  title: string
  description: ReactNode
  children?: ReactNode
}) {
  const Icon = kind === "error" ? AlertCircleIcon : DatabaseAddIcon

  return (
    <Card
      size="sm"
      role={kind === "error" ? "alert" : "status"}
      className="mx-auto w-full max-w-2xl"
    >
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <HugeiconsIcon icon={Icon} />
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      {children && (
        <CardFooter className="flex-wrap gap-2">{children}</CardFooter>
      )}
    </Card>
  )
}

function NativeSelect({
  id,
  value,
  onChange,
  children,
  disabled = false,
  className,
  ariaInvalid,
}: {
  id?: string
  value: string
  onChange: (value: string) => void
  children: ReactNode
  disabled?: boolean
  className?: string
  ariaInvalid?: boolean
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
      aria-invalid={ariaInvalid || undefined}
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
    name: node.name,
    displayColor: node.displayColor ?? null,
    nodeType: node.nodeType,
    parentId: node.parentId,
    active: node.active,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes,
    showInMainMenu: node.showInMainMenu,
    routeQueueKind: node.routeQueueKind,
    routing: node.routing,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    furnitureCategory: node.furnitureCategory,
    furnitureEquipment: node.furnitureEquipment,
    forcesCapitalRepair: node.forcesCapitalRepair,
    characteristicId: node.characteristic?.characteristicId ?? null,
    canvasX: node.canvasX,
    canvasY: node.canvasY,
    comment: node.nodeType === "MATERIAL" ? null : node.comment,
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
    name: "",
    displayColor: null,
    nodeType,
    parentId,
    active: true,
    unit: priced ? "шт." : null,
    unitPrice: priced ? "0.00" : null,
    durationMinutes: nodeType === "WORK" ? 60 : null,
    showInMainMenu: false,
    routeQueueKind: null,
    includeInEstimate: priced,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    forcesCapitalRepair: false,
    characteristicId: null,
    canvasX: null,
    canvasY: null,
    comment: null,
  }
}

function createCommonCatalogNodeCopy(
  node: RepairEstimateCatalogNodeDto,
  parentId: string
): RepairEstimateCatalogNodeMutation {
  return {
    ...createNodeMutation(node),
    id: undefined,
    parentId,
    // The source remains available in the common catalog. Its copy belongs to
    // the selected category and therefore must be available in that branch.
    commonItem: false,
    // Let the constructor place a copied block among the destination category
    // nodes instead of overlapping the source block on the canvas.
    canvasX: null,
    canvasY: null,
  }
}

function BooleanField({
  title,
  checked,
  disabled = false,
  onCheckedChange,
  className,
  checkboxClassName,
}: {
  title: string
  checked: boolean
  disabled?: boolean
  onCheckedChange: (checked: boolean) => void
  className?: string
  checkboxClassName?: string
}) {
  const fieldId = useId()

  return (
    <Field
      orientation="horizontal"
      data-disabled={disabled}
      className={className}
    >
      <Checkbox
        id={fieldId}
        aria-label={title}
        checked={checked}
        disabled={disabled}
        className={checkboxClassName}
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
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState(state.value)
  const [characteristicLinked, setCharacteristicLinked] = useState(
    state.value.nodeType === "MATERIAL" && state.value.characteristicId !== null
  )
  const [error, setError] = useState<string | null>(null)
  const priced = draft.nodeType === "WORK" || draft.nodeType === "MATERIAL"
  const furnitureMaterial = state.furnitureTree && draft.nodeType === "MATERIAL"
  const categoryRouting = draft.nodeType === "CATEGORY"
  const materialCharacteristic = draft.nodeType === "MATERIAL"
  const commentLength = (draft.comment ?? "").length
  const commentTooLong = commentLength > CATALOG_COMMENT_MAX_LENGTH
  const routingQueuesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.queueDefinitions,
    queryFn: () =>
      taskBoardSettingsClient.listQueueDefinitions(state.request.accessToken),
    enabled: categoryRouting,
  })
  const routingQueues = useMemo(
    () =>
      (routingQueuesQuery.data ?? [])
        .filter(
          (
            queue
          ): queue is QueueDefinitionDto & {
            type: RepairEstimateCatalogRouteQueueKind
          } => queue.type !== "FURNITURE_MOVEMENT"
        )
        .sort(
          (left, right) =>
            left.name.localeCompare(right.name, "ru") ||
            left.id.localeCompare(right.id)
        ),
    [routingQueuesQuery.data]
  )
  const characteristicsQuery = useQuery({
    queryKey: ["asset", "cabin-settings", "characteristics"],
    queryFn: () => getCabinSettings(state.request.accessToken),
    enabled: materialCharacteristic,
  })
  const characteristics = useMemo(
    () =>
      (characteristicsQuery.data?.characteristics ?? [])
        .filter(
          (characteristic) =>
            characteristic.active ||
            characteristic.id === draft.characteristicId
        )
        .sort(
          (left, right) =>
            left.name.localeCompare(right.name, "ru") ||
            left.id.localeCompare(right.id)
        ),
    [characteristicsQuery.data, draft.characteristicId]
  )
  const selectedRoutingQueue =
    draft.routing === null || draft.routing === undefined
      ? null
      : (routingQueues.find((queue) => queue.id === draft.routing?.queueId) ??
        null)
  const routingUnavailable =
    categoryRouting &&
    draft.routing !== null &&
    draft.routing !== undefined &&
    selectedRoutingQueue === null &&
    !routingQueuesQuery.isFetching &&
    !routingQueuesQuery.isError
  const routingSaveBlocked =
    categoryRouting &&
    (routingQueuesQuery.isFetching ||
      routingQueuesQuery.isError ||
      routingUnavailable)
  const characteristicSaveBlocked =
    materialCharacteristic &&
    characteristicLinked &&
    (characteristicsQuery.isFetching ||
      characteristicsQuery.isError ||
      !draft.characteristicId ||
      !characteristics.some(
        (characteristic) => characteristic.id === draft.characteristicId
      ))
  const maximumPerCabin = draft.furnitureEquipment?.maximumPerCabin ?? null
  const maximumPerCabinSaveBlocked =
    furnitureMaterial &&
    maximumPerCabin !== null &&
    (!Number.isInteger(maximumPerCabin) || maximumPerCabin < 1)
  const draftForSave =
    categoryRouting && selectedRoutingQueue !== null
      ? {
          ...draft,
          routeQueueKind: selectedRoutingQueue.type,
          routing: {
            queueId: selectedRoutingQueue.id,
            queueName: selectedRoutingQueue.name,
            queueType: selectedRoutingQueue.type,
          },
        }
      : draft

  function notifySaved() {
    if (furnitureMaterial) {
      void queryClient.invalidateQueries({ queryKey: ["equipment-items"] })
    }
    onSaved()
  }

  const mutation = useMutation({
    mutationFn: state.save,
    onSuccess: () => {
      notifySaved()
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
            if (
              !mutation.isPending &&
              !commentTooLong &&
              !routingSaveBlocked &&
              !characteristicSaveBlocked &&
              !maximumPerCabinSaveBlocked
            ) {
              mutation.mutate(draftForSave)
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
                  onChange={(value) => {
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
                      furnitureEquipment:
                        value === "MATERIAL"
                          ? current.furnitureEquipment
                          : null,
                      forcesCapitalRepair:
                        value === "WORK" ? current.forcesCapitalRepair : false,
                      characteristicId:
                        value === "MATERIAL" ? current.characteristicId : null,
                      comment: value === "MATERIAL" ? null : current.comment,
                    }))
                    if (value !== "MATERIAL") {
                      setCharacteristicLinked(false)
                    }
                  }}
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
                <FieldLabel htmlFor={`${fieldIdPrefix}-name`}>
                  Название
                </FieldLabel>
                <Input
                  id={`${fieldIdPrefix}-name`}
                  value={draft.name}
                  onChange={(event) =>
                    setDraft((current) => {
                      const name = event.target.value
                      return {
                        ...current,
                        name,
                        furnitureEquipment:
                          current.furnitureEquipment?.equipmentId === null
                            ? {
                                ...current.furnitureEquipment,
                                equipmentName: name,
                              }
                            : current.furnitureEquipment,
                      }
                    })
                  }
                />
              </Field>
            </div>

            {categoryRouting && (
              <Field
                data-invalid={
                  routingQueuesQuery.isError || routingUnavailable || undefined
                }
                data-disabled={
                  routingQueuesQuery.isFetching || mutation.isPending
                }
              >
                <FieldLabel htmlFor={`${fieldIdPrefix}-routing-queue`}>
                  Очередь доски задач
                </FieldLabel>
                <Select
                  value={
                    selectedRoutingQueue?.id ??
                    draft.routing?.queueId ??
                    NO_ROUTE_QUEUE_VALUE
                  }
                  disabled={
                    routingQueuesQuery.isFetching ||
                    routingQueuesQuery.isError ||
                    mutation.isPending
                  }
                  onValueChange={(queueId) => {
                    if (queueId === NO_ROUTE_QUEUE_VALUE) {
                      setDraft((current) => ({
                        ...current,
                        routeQueueKind: null,
                        routing: null,
                      }))
                      return
                    }

                    const queue = routingQueues.find(
                      (candidate) => candidate.id === queueId
                    )
                    if (!queue) {
                      return
                    }

                    setDraft((current) => ({
                      ...current,
                      routeQueueKind: queue.type,
                      routing: {
                        queueId: queue.id,
                        queueName: queue.name,
                        queueType: queue.type,
                      },
                    }))
                  }}
                >
                  <SelectTrigger
                    id={`${fieldIdPrefix}-routing-queue`}
                    className="w-full"
                    aria-invalid={
                      routingQueuesQuery.isError ||
                      routingUnavailable ||
                      undefined
                    }
                  >
                    <SelectValue placeholder="Выберите очередь" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem value={NO_ROUTE_QUEUE_VALUE}>
                        Без очереди (очистить привязку)
                      </SelectItem>
                      {routingQueues.map((queue) => (
                        <SelectItem key={queue.id} value={queue.id}>
                          {queue.name} · {queueTypeLabels[queue.type]}
                        </SelectItem>
                      ))}
                      {routingUnavailable && draft.routing && (
                        <SelectItem value={draft.routing.queueId} disabled>
                          {draft.routing.queueName} ·{" "}
                          {queueTypeLabels[draft.routing.queueType]}{" "}
                          (недоступна)
                        </SelectItem>
                      )}
                    </SelectGroup>
                  </SelectContent>
                </Select>
                <FieldDescription>
                  Категория связывается с единым определением очереди. На каждом
                  складе должна быть подключена соответствующая очередь.
                </FieldDescription>
                {routingQueuesQuery.isError && (
                  <FieldError>
                    Не удалось проверить очереди доски задач. Повторно откройте
                    редактор после восстановления сервиса.
                  </FieldError>
                )}
                {routingUnavailable && (
                  <FieldError>
                    Текущее определение очереди удалено. Выберите существующее
                    определение либо очистите привязку.
                  </FieldError>
                )}
              </Field>
            )}

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

            {furnitureMaterial && (
              <Field>
                <FieldLabel>Дополнительное оборудование</FieldLabel>
                {draft.furnitureEquipment ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary">Связано автоматически</Badge>
                    <span className="text-sm">
                      {draft.furnitureEquipment.equipmentName}
                    </span>
                  </div>
                ) : (
                  <Badge variant="outline">Будет создано автоматически</Badge>
                )}
                <FieldDescription>
                  После сохранения название мебели автоматически создаст и
                  привяжет строку в «Доп. оборудовании» настроек склада. При
                  завершении сметы количество будет снято с оборудования
                  бытовки.
                </FieldDescription>
              </Field>
            )}

            {materialCharacteristic ? (
              <FieldGroup>
                <BooleanField
                  title="Сопоставить с характеристикой бытовки"
                  checked={characteristicLinked}
                  disabled={mutation.isPending}
                  className="rounded-md border border-primary/50 bg-primary/5 px-3 py-2"
                  checkboxClassName="size-5 border-2 border-primary bg-background shadow-sm"
                  onCheckedChange={(checked) => {
                    setCharacteristicLinked(checked)
                    setDraft((current) => ({
                      ...current,
                      characteristicId: checked
                        ? (current.characteristicId ??
                          characteristics[0]?.id ??
                          "")
                        : null,
                    }))
                  }}
                />
                {characteristicLinked ? (
                  <Field
                    data-invalid={
                      characteristicsQuery.isError ||
                      (!characteristicsQuery.isFetching &&
                        !draft.characteristicId) ||
                      undefined
                    }
                    data-disabled={
                      characteristicsQuery.isFetching || mutation.isPending
                    }
                  >
                    <FieldLabel
                      htmlFor={`${fieldIdPrefix}-cabin-characteristic`}
                    >
                      Характеристика бытовки
                    </FieldLabel>
                    <Select
                      value={draft.characteristicId || undefined}
                      disabled={
                        characteristicsQuery.isFetching ||
                        characteristicsQuery.isError ||
                        mutation.isPending
                      }
                      onValueChange={(characteristicId) =>
                        setDraft((current) => ({
                          ...current,
                          characteristicId,
                        }))
                      }
                    >
                      <SelectTrigger
                        id={`${fieldIdPrefix}-cabin-characteristic`}
                        className="w-full"
                      >
                        <SelectValue placeholder="Выберите характеристику" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          {characteristics.map((characteristic) => (
                            <SelectItem
                              key={characteristic.id}
                              value={characteristic.id}
                            >
                              {characteristic.name}
                              {!characteristic.active ? " (отключена)" : ""}
                            </SelectItem>
                          ))}
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                    <FieldDescription>
                      После успешной приёмки результата эта характеристика будет
                      добавлена бытовке.
                    </FieldDescription>
                    {characteristicsQuery.isError ? (
                      <FieldError>
                        Не удалось загрузить характеристики из asset-service.
                      </FieldError>
                    ) : !characteristicsQuery.isFetching &&
                      characteristics.length === 0 ? (
                      <FieldError>
                        В справочнике бытовок нет доступных характеристик.
                      </FieldError>
                    ) : null}
                  </Field>
                ) : null}
                <FieldDescription>
                  К одной характеристике можно привязать несколько материалов.
                </FieldDescription>
              </FieldGroup>
            ) : null}

            {furnitureMaterial ? (
              <Field data-invalid={maximumPerCabinSaveBlocked || undefined}>
                <FieldLabel htmlFor={`${fieldIdPrefix}-maximum-per-cabin`}>
                  Максимальное количество в одной бытовке
                </FieldLabel>
                <Input
                  id={`${fieldIdPrefix}-maximum-per-cabin`}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  step={1}
                  value={maximumPerCabin ?? ""}
                  disabled={mutation.isPending}
                  aria-invalid={maximumPerCabinSaveBlocked || undefined}
                  onChange={(event) => {
                    const nextMaximum = toNumberOrNull(event.target.value)
                    setDraft((current) => {
                      if (
                        current.furnitureEquipment === null &&
                        nextMaximum === null
                      ) {
                        return current
                      }
                      return {
                        ...current,
                        furnitureEquipment: {
                          equipmentId:
                            current.furnitureEquipment?.equipmentId ?? null,
                          equipmentName:
                            current.furnitureEquipment?.equipmentName ??
                            current.name,
                          equipmentVersion:
                            current.furnitureEquipment?.equipmentVersion ??
                            null,
                          maximumPerCabin: nextMaximum,
                        },
                      }
                    })
                  }}
                />
                <FieldDescription>
                  Оставьте поле пустым, если ограничение для этой позиции не
                  требуется.
                </FieldDescription>
                {maximumPerCabinSaveBlocked ? (
                  <FieldError>
                    Укажите целое положительное количество.
                  </FieldError>
                ) : null}
              </Field>
            ) : null}

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
              {draft.nodeType === "WORK" ? (
                <BooleanField
                  title="Автоматически переводит бытовку в капитальный ремонт"
                  checked={draft.forcesCapitalRepair}
                  onCheckedChange={(checked) =>
                    setDraft((current) => ({
                      ...current,
                      forcesCapitalRepair: checked,
                    }))
                  }
                />
              ) : null}
            </FieldGroup>

            {draft.nodeType !== "MATERIAL" && (
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
                    Максимальная длина комментария —{" "}
                    {CATALOG_COMMENT_MAX_LENGTH} символов. Сократите текст перед
                    сохранением.
                  </FieldError>
                )}
              </Field>
            )}

            {error !== null && <ErrorBox>{error}</ErrorBox>}
          </FieldGroup>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Отмена
            </Button>
            <Button
              type="submit"
              disabled={
                mutation.isPending ||
                commentTooLong ||
                routingSaveBlocked ||
                characteristicSaveBlocked
              }
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
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  action: EstimateCatalogSettingsActionDto
  onOpenCategory: (categoryId: string) => void
  readOnly: boolean
}) {
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const dataQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
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

  const canCreateCategory =
    !readOnly && action.id === "repair-estimate-catalog-canvas"
  const nodeCount = isSectionData(dataQuery.data)
    ? getRepairEstimateCatalogSectionItems(dataQuery.data).length
    : dataQuery.data.nodes.length
  const linkCount = dataQuery.data.links.length

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <CatalogMeta nodeCount={nodeCount} linkCount={linkCount} />
        </div>
        {canCreateCategory && (
          <Button
            type="button"
            onClick={() =>
              setDialogState({
                title: "Создать категорию",
                description: "Категория верхнего уровня каталога смет.",
                submitLabel: "Создать",
                allowTypeSelect: false,
                request,
                furnitureTree: false,
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

      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
        {dataQuery.data.categories.map((category) => (
          <Button
            key={category.id}
            type="button"
            variant="outline"
            size="default"
            className="w-full justify-between"
            onClick={() => onOpenCategory(category.id)}
          >
            <span className="min-w-0 truncate">{category.name}</span>
            <Badge variant="secondary" className="shrink-0">
              {categoryItemCount(dataQuery.data, category.id)} записей
            </Badge>
          </Button>
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

function catalogQueueIdKey(queueId: string) {
  return `id:${queueId}`
}

function getCatalogNodeQueueLabel(
  node: RepairEstimateCatalogNodeDto,
  queueLabels: ReadonlyMap<string, string>
) {
  const queueId = node.routing?.queueId ?? node.queueDefinitionId

  if (queueId !== null) {
    const queueLabel = queueLabels.get(catalogQueueIdKey(queueId))
    if (queueLabel) {
      return queueLabel
    }
  }

  return node.routing?.queueName ?? "Не привязана"
}

function CatalogCanvasNodeMetric({
  label,
  value,
  numeric = false,
}: {
  label: string
  value: string
  numeric?: boolean
}) {
  return (
    <div className="contents">
      <dt className="min-w-0 border-r bg-muted/30 px-2 py-1.5 text-xs leading-snug text-foreground">
        {label}
      </dt>
      <dd
        className={cn(
          "min-w-0 px-2 py-1.5 text-xs leading-snug font-medium break-words text-foreground",
          numeric && "tabular-nums"
        )}
      >
        {value}
      </dd>
    </div>
  )
}

function CatalogCanvasNodeMetrics({ children }: { children: ReactNode }) {
  return (
    <dl className="grid grid-cols-2 overflow-hidden rounded-md border [&>div:not(:last-child)>*]:border-b">
      {children}
    </dl>
  )
}

function CatalogCanvasNodeMetadata({
  node,
  queueLabels,
}: {
  node: RepairEstimateCatalogNodeDto
  queueLabels: ReadonlyMap<string, string>
}) {
  const statusMetric = (
    <CatalogCanvasNodeMetric
      label="Активно"
      value={node.active ? "Да" : "Нет"}
    />
  )
  const estimateMetric = (
    <CatalogCanvasNodeMetric
      label="Учёт в смете"
      value={node.includeInEstimate ? "Да" : "Нет"}
    />
  )
  const unitMetric = (
    <CatalogCanvasNodeMetric label="Единица" value={node.unit || "—"} />
  )
  const priceMetric = (
    <CatalogCanvasNodeMetric
      label="Цена"
      value={
        node.unitPrice === null
          ? "—"
          : `${formatMoneyDecimal(node.unitPrice)} ₽`
      }
      numeric
    />
  )

  return (
    <div className="flex flex-1 flex-col gap-2">
      {node.nodeType === "CATEGORY" && (
        <CatalogCanvasNodeMetrics>
          {statusMetric}
          <CatalogCanvasNodeMetric
            label="Очередь"
            value={getCatalogNodeQueueLabel(node, queueLabels)}
          />
        </CatalogCanvasNodeMetrics>
      )}

      {node.nodeType === "SUBCATEGORY" && (
        <CatalogCanvasNodeMetrics>{statusMetric}</CatalogCanvasNodeMetrics>
      )}

      {node.nodeType === "WORK" && (
        <CatalogCanvasNodeMetrics>
          {unitMetric}
          {priceMetric}
          <CatalogCanvasNodeMetric
            label="Длительность"
            value={
              node.durationMinutes === null
                ? "—"
                : `${node.durationMinutes} мин`
            }
            numeric
          />
          {statusMetric}
          {estimateMetric}
        </CatalogCanvasNodeMetrics>
      )}

      {node.nodeType === "MATERIAL" && (
        <CatalogCanvasNodeMetrics>
          {unitMetric}
          {priceMetric}
          {statusMetric}
          {estimateMetric}
        </CatalogCanvasNodeMetrics>
      )}

      {(node.nodeType === "LOCATION" || node.nodeType === "OPTION") && (
        <CatalogCanvasNodeMetrics>
          {unitMetric}
          {statusMetric}
        </CatalogCanvasNodeMetrics>
      )}

      {(node.commonItem || (node.nodeType !== "MATERIAL" && node.comment)) && (
        <div className="flex flex-col gap-1 text-xs text-muted-foreground">
          {node.commonItem && <span>Общий элемент</span>}
          {node.nodeType !== "MATERIAL" && node.comment && (
            <span className="break-words whitespace-pre-wrap">
              {node.comment}
            </span>
          )}
        </div>
      )}
    </div>
  )
}

function CatalogCanvas({
  nodes,
  links,
  queueLabels,
  readOnly,
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
  onClearCanvas,
}: {
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
  queueLabels: ReadonlyMap<string, string>
  readOnly: boolean
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
  onClearCanvas: () => void
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
    y:
      anchor === "TOP"
        ? position.y + CANVAS_ANCHOR_INSET
        : position.y + position.height - CANVAS_ANCHOR_INSET,
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
      data-catalog-canvas="true"
      style={{ width, height }}
      onClick={() => {
        setLinkPreviewPoint(null)
        onClearCanvas()
      }}
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

      {!readOnly &&
        links.map((link) => {
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
              variant="destructive"
              size="icon"
              className="absolute z-30 -translate-x-1/2 -translate-y-1/2 shadow-md"
              aria-label="Удалить связь"
              title="Удалить связь"
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
              <HugeiconsIcon icon={Delete01Icon} aria-hidden="true" />
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
            "absolute flex flex-col items-stretch gap-3 overflow-visible rounded-lg border bg-card px-3 py-3 text-left text-sm shadow-sm transition-colors hover:bg-accent",
            readOnly
              ? "cursor-default"
              : "cursor-grab touch-none select-none active:cursor-grabbing",
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

            const height = element.getBoundingClientRect().height
            setNodeHeights((current) =>
              current[node.id] === height
                ? current
                : { ...current, [node.id]: height }
            )
          }}
          onClick={(event) => {
            event.stopPropagation()
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
            if (readOnly) {
              return
            }
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
              drag === null &&
              draftLinkStart !== null &&
              draftLinkStart.nodeId !== node.id
            ) {
              const rect = event.currentTarget.getBoundingClientRect()
              const targetAnchor =
                event.clientY <= rect.top + rect.height / 2 ? "TOP" : "BOTTOM"
              event.stopPropagation()
              setLinkPreviewPoint(anchorPoint(position, targetAnchor))
              return
            }
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
            if (readOnly) {
              return
            }
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
          {!readOnly &&
            (["TOP", "BOTTOM"] satisfies CatalogLinkAnchor[]).map((anchor) => (
              <button
                key={anchor}
                type="button"
                aria-label={`${anchor === "TOP" ? "Верхняя" : "Нижняя"} точка связи: ${node.name}`}
                data-catalog-link-anchor={anchor}
                data-catalog-node-id={node.id}
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
                onPointerMove={(event) => {
                  if (
                    draftLinkStart !== null &&
                    draftLinkStart.nodeId !== node.id
                  ) {
                    event.stopPropagation()
                    setLinkPreviewPoint(anchorPoint(position, anchor))
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
          <CatalogCanvasNodeMetadata node={node} queueLabels={queueLabels} />
          {!readOnly && (
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
          )}
        </div>
      ))}
    </div>
  )
}

function CommonCatalogNodePickerDialog({
  open,
  kind,
  categoryName,
  items,
  isPending,
  error,
  onKindChange,
  onClose,
  onSelect,
}: {
  open: boolean
  kind: CommonCatalogKind
  categoryName: string
  items: RepairEstimateCatalogNodeDto[]
  isPending: boolean
  error: string | null
  onKindChange: (kind: CommonCatalogKind) => void
  onClose: () => void
  onSelect: (node: RepairEstimateCatalogNodeDto) => void
}) {
  if (!open) {
    return null
  }

  return (
    <Dialog
      open
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !isPending) {
          onClose()
        }
      }}
    >
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Добавить общее</DialogTitle>
          <DialogDescription>
            Выберите общую позицию для категории «{categoryName}». Будет создан
            отдельный блок без стрелок.
          </DialogDescription>
        </DialogHeader>

        <div
          role="group"
          aria-label="Тип общего блока"
          className="flex flex-wrap items-center gap-1"
        >
          {(["works", "materials"] satisfies CommonCatalogKind[]).map(
            (candidate) => (
              <Button
                key={candidate}
                type="button"
                variant={kind === candidate ? "default" : "outline"}
                size="sm"
                disabled={isPending}
                onClick={() => onKindChange(candidate)}
              >
                {commonCatalogKindTitle(candidate)}
              </Button>
            )
          )}
        </div>

        {items.length === 0 ? (
          <p className="rounded-lg border px-3 py-2 text-sm text-muted-foreground">
            В разделе «{commonCatalogKindTitle(kind)}» нет активных общих
            позиций.
          </p>
        ) : (
          <div className="grid gap-2">
            {items.map((item) => (
              <Button
                key={item.id}
                type="button"
                variant="outline"
                className="h-auto min-h-12 justify-between gap-3 px-3 py-2 text-left"
                disabled={isPending}
                aria-label={`Добавить: ${item.name}`}
                onClick={() => onSelect(item)}
              >
                <span className="min-w-0">
                  <span className="block truncate font-medium">
                    {item.name}
                  </span>
                  <span className="block text-xs font-normal text-muted-foreground">
                    {item.unit ?? "Без единицы"}
                    {item.unitPrice ? ` · ${item.unitPrice}` : ""}
                  </span>
                </span>
                <Badge variant="secondary" className="shrink-0">
                  {repairEstimateCatalogNodeTypeLabel(item.nodeType)}
                </Badge>
              </Button>
            ))}
          </div>
        )}

        {isPending ? (
          <p role="status" className="text-sm text-muted-foreground">
            Добавляем общий блок…
          </p>
        ) : null}
        {error !== null ? <ErrorBox>{error}</ErrorBox> : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={isPending}
            onClick={onClose}
          >
            Отмена
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CatalogCanvasCategoryEditor({
  request,
  categoryId,
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  categoryId: string
  readOnly: boolean
}) {
  const queryClient = useQueryClient()
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedLinkId, setSelectedLinkId] = useState<string | null>(null)
  const [draftLinkType, setDraftLinkType] =
    useState<RepairEstimateCatalogLinkType>("FOLLOW_UP")
  const [draftLinkStart, setDraftLinkStart] = useState<CatalogLinkStart | null>(
    null
  )
  const draftLinkSequenceRef = useRef(0)
  const [pendingNodePositions, setPendingNodePositions] = useState<
    Record<string, CanvasPosition>
  >({})
  const [pendingLinks, setPendingLinks] = useState<PendingCatalogLink[]>([])
  const [deletedLinkIds, setDeletedLinkIds] = useState<string[]>([])
  const [nodeDialogState, setNodeDialogState] =
    useState<NodeDialogState | null>(null)
  const [commonPickerOpen, setCommonPickerOpen] = useState(false)
  const [commonPickerKind, setCommonPickerKind] =
    useState<CommonCatalogKind>("works")
  const [error, setError] = useState<string | null>(null)

  const canvasQueryKey = [
    ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    request.catalogVersionId,
    "canvas",
  ] as const
  const canvasQuery = useQuery({
    queryKey: canvasQueryKey,
    queryFn: () => getRepairEstimateCatalogCanvasSettingsData(request),
  })
  const hasRoutedCategory =
    canvasQuery.data?.nodes.some(
      (node) =>
        node.nodeType === "CATEGORY" &&
        (node.routing !== null || node.queueDefinitionId !== null)
    ) ?? false
  const routingQueuesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.queueDefinitions,
    queryFn: () =>
      taskBoardSettingsClient.listQueueDefinitions(request.accessToken),
    enabled: hasRoutedCategory,
  })
  const routingQueueLabels = useMemo(() => {
    const labels = new Map<string, string>()

    for (const queue of routingQueuesQuery.data ?? []) {
      if (queue.type === "FURNITURE_MOVEMENT") {
        continue
      }

      labels.set(catalogQueueIdKey(queue.id), queue.name)
    }

    return labels
  }, [routingQueuesQuery.data])

  const invalidate = () => {
    void queryClient.invalidateQueries({
      queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    })
  }

  const saveCanvasMutation = useMutation({
    mutationFn: (changes: RepairEstimateCatalogCanvasChangeSet) =>
      saveRepairEstimateCatalogCanvasChanges(request, changes),
    onSuccess: (savedCanvas) => {
      queryClient.setQueryData(canvasQueryKey, savedCanvas)
      setPendingNodePositions({})
      setPendingLinks([])
      setDeletedLinkIds([])
      setDraftLinkStart(null)
      setError(null)
      setSelectedLinkId(null)
      toast.success("Каталог сохранён.")
      invalidate()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось сохранить каталог"
      )
    },
  })

  const addCommonNodeMutation = useMutation({
    mutationFn: (node: RepairEstimateCatalogNodeDto) =>
      saveRepairEstimateCatalogCanvasNode(
        request,
        createCommonCatalogNodeCopy(node, categoryId)
      ),
    onSuccess: () => {
      setCommonPickerOpen(false)
      setError(null)
      invalidate()
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось добавить общий блок"
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
  if (category === null) {
    return <ErrorBox>Категория не найдена</ErrorBox>
  }

  const commonNodesById = new Map(data.nodes.map((node) => [node.id, node]))
  const commonItems = data.nodes
    .filter(
      (node) =>
        node.active &&
        node.commonItem &&
        node.nodeType ===
          (commonPickerKind === "works" ? "WORK" : "MATERIAL") &&
        !isCatalogFurnitureTreeNode(node, commonNodesById)
    )
    .sort(
      (left, right) =>
        left.name.localeCompare(right.name, "ru") ||
        left.id.localeCompare(right.id)
    )
  const canvasNodes = getCanvasNodes(data, categoryId)
  const nodeIds = new Set(canvasNodes.map((node) => node.id))
  const canvasLinks = [
    ...data.links.filter(
      (link) =>
        nodeIds.has(link.sourceNodeId) &&
        nodeIds.has(link.targetNodeId) &&
        !deletedLinkIds.includes(link.id)
    ),
    ...pendingLinks.map(({ draftId, input }): RepairEstimateCatalogLinkDto => ({
      id: draftId,
      catalogVersionId: data.catalogVersion.id,
      sourceNodeId: input.sourceNodeId,
      targetNodeId: input.targetNodeId,
      linkType: input.linkType,
      sortOrder: input.sortOrder,
      canvasAnchors: input.canvasAnchors,
    })),
  ]
  const hasPendingCanvasChanges =
    Object.keys(pendingNodePositions).length > 0 ||
    pendingLinks.length > 0 ||
    deletedLinkIds.length > 0
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

    const input: RepairEstimateCatalogLinkMutation = {
      sourceNodeId: sourceNode.id,
      targetNodeId: target.id,
      linkType: draftLinkType,
      sortOrder: canvasLinks.length * 10 + 10,
      canvasAnchors: {
        source: draftLinkStart.anchor,
        target: targetAnchor,
      },
    }
    const draftId = `draft-catalog-link-${++draftLinkSequenceRef.current}`
    setPendingLinks((current) => [...current, { draftId, input }])
    setError(null)
    setDraftLinkStart(null)
    setSelectedNodeId(null)
    setSelectedLinkId(draftId)
  }

  const openNodeEditor = (node: RepairEstimateCatalogNodeDto) => {
    setNodeDialogState({
      title: "Редактировать блок",
      description: node.name,
      submitLabel: "Сохранить",
      allowTypeSelect: node.nodeType !== "CATEGORY",
      request,
      furnitureTree: category.furnitureCategory,
      value: createNodeMutation(node),
      save: (input) => saveRepairEstimateCatalogCanvasNode(request, input),
    })
  }
  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <CatalogMeta
            nodeCount={canvasNodes.length}
            linkCount={canvasLinks.length}
          />
          {!readOnly && (
            <fieldset className="flex w-fit items-center gap-3 rounded-lg border bg-muted/30 p-1.5">
              <legend className="sr-only">Тип стрелки</legend>
              <span className="pl-1 text-sm font-medium text-foreground">
                Тип стрелки
              </span>
              <ToggleGroup
                type="single"
                variant="outline"
                size="sm"
                spacing={0}
                className="bg-background shadow-xs"
                value={draftLinkType}
                aria-label="Тип стрелки"
                onValueChange={(value) => {
                  if (value) {
                    setDraftLinkType(value as RepairEstimateCatalogLinkType)
                  }
                }}
              >
                {(
                  [
                    "FOLLOW_UP",
                    "DEPENDENCY",
                  ] satisfies RepairEstimateCatalogLinkType[]
                ).map((type) => (
                  <ToggleGroupItem key={type} value={type} className="min-w-24">
                    {repairEstimateCatalogLinkTypeLabel(type)}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </fieldset>
          )}
        </div>
        {readOnly ? (
          <span className="text-sm text-muted-foreground">Только просмотр</span>
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            {draftLinkStart !== null && (
              <Button
                type="button"
                variant="outline"
                onClick={() => setDraftLinkStart(null)}
              >
                Отменить стрелку
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              disabled={addCommonNodeMutation.isPending}
              onClick={() =>
                setNodeDialogState({
                  title: "Добавить блок",
                  description: category.name,
                  submitLabel: "Сохранить",
                  allowTypeSelect: true,
                  request,
                  furnitureTree: category.furnitureCategory,
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
            <Button
              type="button"
              variant="outline"
              disabled={
                addCommonNodeMutation.isPending || saveCanvasMutation.isPending
              }
              onClick={() => {
                setError(null)
                setCommonPickerOpen(true)
              }}
            >
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Общее
            </Button>
            <Button
              type="button"
              disabled={
                !hasPendingCanvasChanges ||
                saveCanvasMutation.isPending ||
                addCommonNodeMutation.isPending
              }
              onClick={() =>
                saveCanvasMutation.mutate({
                  nodePositions: Object.entries(pendingNodePositions).map(
                    ([nodeId, position]) => ({ nodeId, ...position })
                  ),
                  addedLinks: pendingLinks.map(({ input }) => input),
                  deletedLinkIds,
                })
              }
            >
              <HugeiconsIcon
                icon={
                  saveCanvasMutation.isPending ? Loading03Icon : FloppyDiskIcon
                }
                data-icon="inline-start"
                className={
                  saveCanvasMutation.isPending ? "animate-spin" : undefined
                }
              />
              {saveCanvasMutation.isPending ? "Сохраняем…" : "Сохранить"}
            </Button>
          </div>
        )}
      </div>

      <div className="min-h-[28rem] flex-1 overflow-auto rounded-lg border bg-background">
        <CatalogCanvas
          nodes={canvasNodes}
          links={canvasLinks}
          queueLabels={routingQueueLabels}
          readOnly={readOnly}
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
            if (readOnly) return
            setError(null)
            setSelectedNodeId(start.nodeId)
            setSelectedLinkId(null)
            setDraftLinkStart(start)
          }}
          onCompleteLink={(target, anchor) => {
            if (!readOnly) createLinkFromAnchors(target, anchor)
          }}
          onMoveNode={(node, position) => {
            if (readOnly) return
            setPendingNodePositions((current) => ({
              ...current,
              [node.id]: position,
            }))
            setError(null)
            setSelectedNodeId(node.id)
            setSelectedLinkId(null)
          }}
          onEditNode={openNodeEditor}
          onDeleteLink={(link) => {
            if (readOnly) return
            const pendingLink = pendingLinks.find(
              ({ draftId }) => draftId === link.id
            )
            if (pendingLink) {
              setPendingLinks((current) =>
                current.filter(({ draftId }) => draftId !== link.id)
              )
            } else {
              setDeletedLinkIds((current) =>
                current.includes(link.id) ? current : [...current, link.id]
              )
            }
            setError(null)
            setSelectedLinkId(null)
          }}
          onClearCanvas={() => {
            setDraftLinkStart(null)
            setSelectedNodeId(null)
            setSelectedLinkId(null)
          }}
        />
      </div>

      {error !== null && <ErrorBox>{error}</ErrorBox>}

      <CommonCatalogNodePickerDialog
        open={commonPickerOpen}
        kind={commonPickerKind}
        categoryName={category.name}
        items={commonItems}
        isPending={addCommonNodeMutation.isPending}
        error={error}
        onKindChange={(kind) => {
          setCommonPickerKind(kind)
          setError(null)
        }}
        onClose={() => {
          setCommonPickerOpen(false)
          setError(null)
        }}
        onSelect={(node) => {
          setError(null)
          addCommonNodeMutation.mutate(node)
        }}
      />

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
  readOnly,
}: {
  section: RepairEstimateCatalogSectionDto
  categoryId: string
  items: RepairEstimateCatalogNodeDto[]
  onEdit: (node: RepairEstimateCatalogNodeDto) => void
  onDelete: (node: RepairEstimateCatalogNodeDto) => void
  readOnly: boolean
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
      <thead className="sticky top-0 bg-muted/85 text-muted-foreground">
        <tr>
          {renderSortableHeader("name", "Название")}
          {renderSortableHeader("unit", "Ед.")}
          {renderSortableHeader("unitPrice", "Цена")}
          {section.sectionType === "WORK" &&
            renderSortableHeader("durationMinutes", "Мин.")}
          {section.kind === "furniture" && (
            <th className="px-3 py-2 text-left font-medium">
              Доп. оборудование
            </th>
          )}
          {renderSortableHeader("includeInEstimate", "Смета")}
          {renderSortableHeader("commonItem", "Общий")}
          {!readOnly && (
            <th
              aria-hidden="true"
              className="px-3 py-2 text-right font-medium"
            />
          )}
        </tr>
      </thead>
      <tbody>
        {sortedItems.map((item) => (
          <tr key={item.id} className="border-t">
            <td className="max-w-72 px-3 py-2">
              <div className="truncate font-medium">{item.name}</div>
              {section.sectionType === "WORK" && item.comment && (
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
            {section.kind === "furniture" && (
              <td className="px-3 py-2">
                {item.furnitureEquipment
                  ? item.furnitureEquipment.equipmentName
                  : "Не привязано"}
              </td>
            )}
            {!readOnly && (
              <td className="px-3 py-2">
                {item.includeInEstimate ? "Да" : "Нет"}
              </td>
            )}
            <td className="px-3 py-2">{item.commonItem ? "Да" : "Нет"}</td>
            {!readOnly && (
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
                    variant="destructive"
                    size="icon-sm"
                    title="Удалить"
                    onClick={() => onDelete(item)}
                  >
                    <HugeiconsIcon icon={Delete01Icon} />
                    <span className="sr-only">Удалить</span>
                  </Button>
                </div>
              </td>
            )}
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
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  action: EstimateCatalogSettingsActionDto
  categoryId: string
  readOnly: boolean
}) {
  const kind = getSectionKind(action)
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const sectionQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
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
        case "furniture":
          return deleteRepairEstimateFurnitureCatalogItem(request, id)
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
  const nodeCount = getRepairEstimateCatalogSectionItems(section).length
  const linkCount = section.links.length
  const save = (input: RepairEstimateCatalogNodeMutation) => {
    switch (kind) {
      case "works":
        return saveRepairEstimateWorkCatalogItem(request, input)
      case "materials":
        return saveRepairEstimateMaterialCatalogItem(request, input)
      case "furniture":
        return saveRepairEstimateFurnitureCatalogItem(request, input)
      case null:
        return saveRepairEstimateWorkCatalogItem(request, input)
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <CatalogMeta nodeCount={nodeCount} linkCount={linkCount} />
        </div>
        {!readOnly ? (
          <Button
            type="button"
            onClick={() =>
              setDialogState({
                title: `Добавить: ${section.title}`,
                description: category.name,
                submitLabel: "Сохранить",
                allowTypeSelect: false,
                request,
                furnitureTree: kind === "furniture",
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
        ) : (
          <span className="text-sm text-muted-foreground">Только просмотр</span>
        )}
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
              request,
              furnitureTree: kind === "furniture",
              value: createNodeMutation(node),
              save,
            })
          }
          onDelete={(node) => deleteMutation.mutate(node.id)}
          readOnly={readOnly}
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

function commonCatalogKindTitle(kind: CommonCatalogKind) {
  return kind === "works" ? "Работы" : "Материалы"
}

function CommonCatalogItemsView({
  request,
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  readOnly: boolean
}) {
  const queryClient = useQueryClient()
  const [kind, setKind] = useState<CommonCatalogKind>("works")
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const sectionQuery = useQuery({
    queryKey: [
      ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      request.catalogVersionId,
      "common-section",
      kind,
    ],
    queryFn: () =>
      kind === "works"
        ? getRepairEstimateWorkCatalog(request)
        : getRepairEstimateMaterialCatalog(request),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({
      queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    })
  }

  const save = (input: RepairEstimateCatalogNodeMutation) =>
    kind === "works"
      ? saveRepairEstimateWorkCatalogItem(request, input)
      : saveRepairEstimateMaterialCatalogItem(request, input)

  const deleteMutation = useMutation({
    mutationFn: (id: string) =>
      kind === "works"
        ? deleteRepairEstimateWorkCatalogItem(request, id)
        : deleteRepairEstimateMaterialCatalogItem(request, id),
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
          : "Не удалось загрузить общие позиции каталога."}
      </ErrorBox>
    )
  }

  const section = sectionQuery.data
  if (!section || !isSectionData(section)) {
    return null
  }

  const items = getRepairEstimateCatalogSectionItems(section).filter(
    (item) => item.commonItem
  )

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <CatalogMeta
            nodeCount={items.length}
            linkCount={section.links.length}
          />
          <div
            role="group"
            aria-label="Тип общих записей каталога"
            className="flex flex-wrap items-center gap-1"
          >
            {(["works", "materials"] satisfies CommonCatalogKind[]).map(
              (candidate) => (
                <Button
                  key={candidate}
                  type="button"
                  variant={kind === candidate ? "default" : "outline"}
                  size="sm"
                  onClick={() => {
                    setKind(candidate)
                    setDialogState(null)
                    setError(null)
                  }}
                >
                  {commonCatalogKindTitle(candidate)}
                </Button>
              )
            )}
          </div>
        </div>
        {readOnly ? (
          <span className="text-sm text-muted-foreground">Только просмотр</span>
        ) : (
          <span className="text-sm text-muted-foreground">
            Добавляйте новые общие позиции в их категории и отмечайте «Общий».
          </span>
        )}
      </div>

      {items.length === 0 ? (
        <p className="rounded-lg border px-3 py-2 text-sm text-muted-foreground">
          В разделе «{commonCatalogKindTitle(kind)}» нет позиций с отметкой
          «Общий».
        </p>
      ) : (
        <div className="min-h-0 flex-1 overflow-auto rounded-lg border">
          <CatalogItemsTable
            key={`common:${kind}`}
            section={section}
            categoryId={`common:${kind}`}
            items={items}
            onEdit={(node) =>
              setDialogState({
                title: `Редактировать: Общее — ${commonCatalogKindTitle(kind)}`,
                description: node.name,
                submitLabel: "Сохранить",
                allowTypeSelect: false,
                request,
                furnitureTree: false,
                value: createNodeMutation(node),
                save,
              })
            }
            onDelete={(node) => deleteMutation.mutate(node.id)}
            readOnly={readOnly}
          />
        </div>
      )}

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
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  action: EstimateCatalogSettingsActionDto
  categoryId: string
  readOnly: boolean
}) {
  if (action.id === "repair-estimate-catalog-canvas") {
    return (
      <CatalogCanvasCategoryEditor
        request={request}
        categoryId={categoryId}
        readOnly={readOnly}
      />
    )
  }

  return (
    <CatalogSectionCategoryEditor
      request={request}
      action={action}
      categoryId={categoryId}
      readOnly={readOnly}
    />
  )
}

function normalizeDisplayColor(value: string | null | undefined) {
  if (value === null || value === undefined || value.trim() === "") {
    return null
  }

  const normalized = value.trim().toUpperCase()
  return DISPLAY_COLOR_PATTERN.test(normalized) ? normalized : null
}

function isCatalogFurnitureTreeNode(
  node: RepairEstimateCatalogNodeDto,
  nodesById: ReadonlyMap<string, RepairEstimateCatalogNodeDto>
) {
  const visited = new Set<string>()
  let current: RepairEstimateCatalogNodeDto | undefined = node

  while (current) {
    if (current.furnitureCategory) return true
    if (!current.parentId || visited.has(current.id)) return false
    visited.add(current.id)
    current = nodesById.get(current.parentId)
  }

  return false
}

function catalogDisplayColorGroup(
  node: RepairEstimateCatalogNodeDto,
  nodesById: ReadonlyMap<string, RepairEstimateCatalogNodeDto>
): RepairEstimateCatalogDisplayColorGroup {
  if (isCatalogFurnitureTreeNode(node, nodesById)) {
    return "FURNITURE"
  }

  return node.nodeType
}

function catalogDisplayColors(
  nodes: RepairEstimateCatalogNodeDto[]
): RepairEstimateCatalogDisplayColors {
  const colors = {} as RepairEstimateCatalogDisplayColors
  const nodesById = new Map(nodes.map((node) => [node.id, node]))

  for (const group of REPAIR_ESTIMATE_CATALOG_DISPLAY_COLOR_GROUPS) {
    const values = new Set(
      nodes
        .filter((node) => catalogDisplayColorGroup(node, nodesById) === group)
        .map((node) => normalizeDisplayColor(node.displayColor))
        .filter((color): color is string => color !== null)
    )
    colors[group] = values.size === 1 ? [...values][0] : null
  }

  return colors
}

function CatalogDisplayColorSettings({
  request,
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  readOnly: boolean
}) {
  const snapshotQueryKey = [
    ...REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    request.catalogVersionId,
    "display-colors",
  ] as const
  const snapshotQuery = useQuery({
    queryKey: snapshotQueryKey,
    queryFn: () => getRepairEstimateCatalogSnapshot(request),
  })

  if (snapshotQuery.isLoading) {
    return <SectionSkeleton label="Загружаем цвета кнопок каталога…" />
  }

  if (snapshotQuery.error) {
    return (
      <ErrorBox>
        {snapshotQuery.error instanceof Error
          ? snapshotQuery.error.message
          : "Не удалось загрузить цвета кнопок каталога."}
      </ErrorBox>
    )
  }

  if (!snapshotQuery.data) {
    return <SectionSkeleton label="Загружаем цвета кнопок каталога…" />
  }

  return (
    <CatalogDisplayColorForm
      key={`${request.catalogVersionId}:${snapshotQuery.dataUpdatedAt}`}
      request={request}
      readOnly={readOnly}
      snapshotQueryKey={snapshotQueryKey}
      initialColors={catalogDisplayColors(snapshotQuery.data.nodes)}
    />
  )
}

function CatalogDisplayColorForm({
  request,
  readOnly,
  snapshotQueryKey,
  initialColors,
}: {
  request: RepairEstimateCatalogRequest
  readOnly: boolean
  snapshotQueryKey: readonly unknown[]
  initialColors: RepairEstimateCatalogDisplayColors
}) {
  const queryClient = useQueryClient()
  const fieldIdPrefix = useId()
  const [draftColors, setDraftColors] =
    useState<RepairEstimateCatalogDisplayColors>(initialColors)
  const [error, setError] = useState<string | null>(null)
  const invalidGroups = REPAIR_ESTIMATE_CATALOG_DISPLAY_COLOR_GROUPS.filter(
    (group) => {
      const value = draftColors[group]
      return value !== null && normalizeDisplayColor(value) === null
    }
  )
  const saveMutation = useMutation({
    mutationFn: () =>
      saveRepairEstimateCatalogDisplayColors(request, draftColors),
    onSuccess: (snapshot) => {
      queryClient.setQueryData(snapshotQueryKey, snapshot)
      void queryClient.invalidateQueries({
        queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
      })
      setError(null)
      toast.success("Цветовая индикация каталога сохранена.")
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось сохранить цвета кнопок."
      )
    },
  })

  return (
    <form
      className="flex min-h-0 flex-1 flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault()
        if (
          !readOnly &&
          invalidGroups.length === 0 &&
          !saveMutation.isPending
        ) {
          saveMutation.mutate()
        }
      }}
    >
      <p className="text-sm text-muted-foreground">
        Цвет применяется ко всем текущим кнопкам соответствующего типа в
        каталоге. Оставьте значение пустым, чтобы вернуть стандартное
        оформление.
      </p>

      <FieldGroup>
        {REPAIR_ESTIMATE_CATALOG_DISPLAY_COLOR_GROUPS.map((group) => {
          const copy = DISPLAY_COLOR_GROUP_COPY[group]
          const value = draftColors[group]
          const invalid =
            value !== null && normalizeDisplayColor(value) === null
          const id = `${fieldIdPrefix}-${group.toLowerCase()}`

          return (
            <Field
              key={group}
              data-invalid={invalid || undefined}
              data-disabled={readOnly || saveMutation.isPending || undefined}
              className="rounded-lg border p-3"
            >
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <FieldLabel htmlFor={id}>{copy.title}</FieldLabel>
                  <FieldDescription>{copy.description}</FieldDescription>
                </div>
                <div className="flex items-center gap-2">
                  <Input
                    aria-label={`Выбрать цвет: ${copy.title}`}
                    type="color"
                    value={
                      normalizeDisplayColor(value) ??
                      DISPLAY_COLOR_PICKER_FALLBACK
                    }
                    disabled={readOnly || saveMutation.isPending}
                    onChange={(event) =>
                      setDraftColors((current) => ({
                        ...current,
                        [group]: event.target.value.toUpperCase(),
                      }))
                    }
                    className="rwms-color-picker size-9 shrink-0 cursor-pointer"
                  />
                  <Input
                    id={id}
                    aria-label={`HTML-цвет: ${copy.title}`}
                    value={value ?? ""}
                    placeholder="#RRGGBB"
                    disabled={readOnly || saveMutation.isPending}
                    aria-invalid={invalid || undefined}
                    onChange={(event) =>
                      setDraftColors((current) => ({
                        ...current,
                        [group]: event.target.value,
                      }))
                    }
                    className="w-28"
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={
                      readOnly || saveMutation.isPending || value === null
                    }
                    onClick={() =>
                      setDraftColors((current) => ({
                        ...current,
                        [group]: null,
                      }))
                    }
                  >
                    Сбросить
                  </Button>
                </div>
              </div>
              {invalid && (
                <FieldError>Укажите цвет в формате #RRGGBB.</FieldError>
              )}
            </Field>
          )
        })}
      </FieldGroup>

      {error && <ErrorBox>{error}</ErrorBox>}

      {!readOnly ? (
        <div className="flex justify-end">
          <Button
            type="submit"
            disabled={invalidGroups.length > 0 || saveMutation.isPending}
          >
            <HugeiconsIcon icon={FloppyDiskIcon} data-icon="inline-start" />
            Сохранить цвета
          </Button>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Только просмотр</p>
      )}
    </form>
  )
}

function RepairComplexityColorSettings({
  request,
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  readOnly: boolean
}) {
  const queryKey = ["maintenance", "repair-complexity-colors"] as const
  const colorsQuery = useQuery({
    queryKey,
    queryFn: () => getRepairComplexityColors(request.accessToken),
    refetchInterval: 30_000,
  })

  if (colorsQuery.error && !colorsQuery.data) {
    return (
      <ErrorBox>
        {colorsQuery.error instanceof Error
          ? colorsQuery.error.message
          : "Не удалось загрузить цвета типов ремонта."}
      </ErrorBox>
    )
  }
  if (colorsQuery.isLoading || !colorsQuery.data) {
    return <SectionSkeleton label="Загружаем цвета типов ремонта…" />
  }

  return (
    <>
      {colorsQuery.error ? (
        <ErrorBox>
          Не удалось обновить палитру: {colorsQuery.error.message}
        </ErrorBox>
      ) : null}
      <RepairComplexityColorForm
        request={request}
        readOnly={readOnly}
        queryKey={queryKey}
        initialColors={colorsQuery.data}
      />
    </>
  )
}

function RepairComplexityColorForm({
  request,
  readOnly,
  queryKey,
  initialColors,
}: {
  request: RepairEstimateCatalogRequest
  readOnly: boolean
  queryKey: readonly string[]
  initialColors: RepairComplexityColorsDto
}) {
  const queryClient = useQueryClient()
  const fieldIdPrefix = useId()
  const [edited, setDraft] = useState<RepairComplexityColorsDto | null>(null)
  const draft = edited ?? initialColors
  const stale = Boolean(edited && edited.version !== initialColors.version)
  const [error, setError] = useState<string | null>(null)
  const invalid = REPAIR_COMPLEXITY_COLOR_FIELDS.some(
    ({ key }) => !DISPLAY_COLOR_PATTERN.test(draft[key])
  )
  const mutation = useMutation({
    mutationFn: () =>
      saveRepairComplexityColors(request.accessToken, {
        version: draft.version,
        lightColor: draft.lightColor,
        mediumColor: draft.mediumColor,
        complexColor: draft.complexColor,
        capitalColor: draft.capitalColor,
      }),
    onSuccess: async (saved) => {
      await queryClient.cancelQueries({ queryKey })
      queryClient.setQueryData(queryKey, saved)
      setDraft(null)
      setError(null)
      toast.success("Цвета типов ремонта сохранены.")
      await queryClient.invalidateQueries({
        queryKey: ["maintenance", "repairs"],
      })
    },
    onError: async (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось сохранить цвета типов ремонта."
      )
      if (mutationError instanceof ApiError && mutationError.status === 409)
        await queryClient.invalidateQueries({ queryKey })
    },
  })

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault()
        if (!readOnly && edited && !stale && !invalid && !mutation.isPending) {
          mutation.mutate()
        }
      }}
    >
      <div>
        <h3 className="font-heading text-base font-medium">
          Цвета типов ремонта
        </h3>
        <p className="text-sm text-muted-foreground">
          Единая палитра используется во всех плашках сложности ремонта.
        </p>
      </div>
      <FieldGroup>
        {REPAIR_COMPLEXITY_COLOR_FIELDS.map(({ key, label }) => {
          const value = draft[key]
          const fieldInvalid = !DISPLAY_COLOR_PATTERN.test(value)
          const id = `${fieldIdPrefix}-${key}`
          return (
            <Field
              key={key}
              data-invalid={fieldInvalid || undefined}
              data-disabled={readOnly || mutation.isPending || undefined}
              className="rounded-lg border p-3"
            >
              <div className="flex flex-wrap items-center justify-between gap-3">
                <FieldLabel htmlFor={id}>{label}</FieldLabel>
                <div className="flex items-center gap-2">
                  <Input
                    type="color"
                    aria-label={`Выбрать цвет: ${label}`}
                    value={
                      DISPLAY_COLOR_PATTERN.test(value)
                        ? value
                        : DISPLAY_COLOR_PICKER_FALLBACK
                    }
                    disabled={readOnly || mutation.isPending}
                    className="rwms-color-picker size-9 shrink-0 cursor-pointer"
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...(current ?? initialColors),
                        [key]: event.target.value.toUpperCase(),
                      }))
                    }
                  />
                  <Input
                    id={id}
                    value={value}
                    aria-invalid={fieldInvalid || undefined}
                    disabled={readOnly || mutation.isPending}
                    className="w-28"
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...(current ?? initialColors),
                        [key]: event.target.value,
                      }))
                    }
                  />
                </div>
              </div>
              {fieldInvalid ? (
                <FieldError>Укажите цвет в формате #RRGGBB.</FieldError>
              ) : null}
            </Field>
          )
        })}
      </FieldGroup>
      {stale ? (
        <ErrorBox>
          Палитра изменена в другом окне. Черновик сохранён; загрузите
          актуальные цвета перед редактированием.
        </ErrorBox>
      ) : null}
      {error ? <ErrorBox>{error}</ErrorBox> : null}
      {!readOnly ? (
        <div className="flex justify-end gap-2">
          {edited ? (
            <Button
              type="button"
              variant="outline"
              disabled={mutation.isPending}
              onClick={() => {
                setDraft(null)
                setError(null)
              }}
            >
              {stale ? "Загрузить актуальные цвета" : "Отменить изменения"}
            </Button>
          ) : null}
          <Button
            type="submit"
            disabled={!edited || stale || invalid || mutation.isPending}
          >
            <HugeiconsIcon icon={FloppyDiskIcon} data-icon="inline-start" />
            Сохранить цвета типов ремонта
          </Button>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Только просмотр</p>
      )}
    </form>
  )
}

function EstimateDrilldownView({
  request,
  screen,
  onOpenCategory,
  readOnly,
}: {
  request: RepairEstimateCatalogRequest
  screen: Exclude<EstimateScreen, { level: "root" }>
  onOpenCategory: (categoryId: string) => void
  readOnly: boolean
}) {
  return (
    <section className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg border bg-muted/60">
      <div className="min-h-0 flex-1 overflow-auto p-4">
        {screen.action.id === "repair-estimate-catalog-colors" ? (
          <div className="flex flex-col gap-8">
            <RepairComplexityColorSettings
              request={request}
              readOnly={readOnly}
            />
            <CatalogDisplayColorSettings
              request={request}
              readOnly={readOnly}
            />
          </div>
        ) : screen.action.id === "repair-estimate-catalog-common" ? (
          <CommonCatalogItemsView request={request} readOnly={readOnly} />
        ) : screen.level === "action" ? (
          <EstimateActionCategoryMenu
            request={request}
            action={screen.action}
            onOpenCategory={onOpenCategory}
            readOnly={readOnly}
          />
        ) : (
          <EstimateCategoryEditor
            request={request}
            action={screen.action}
            categoryId={screen.categoryId}
            readOnly={readOnly}
          />
        )}
      </div>
    </section>
  )
}

export function EstimatesRepairsSettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const [estimateScreen, setEstimateScreen] = useState<EstimateScreen>({
    level: "root",
  })
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandIdempotencyKeys = useRef(new Map<string, string>())
  const initialCatalogActionId = useRef(searchParams.get("catalog"))
  const hasSelectedInitialEstimateAction = useRef(false)

  useEffect(() => {
    if (
      estimateScreen.level === "root" &&
      !hasSelectedInitialEstimateAction.current
    ) {
      return
    }

    const activeCatalogAction =
      estimateScreen.level === "root" ? null : estimateScreen.action.id
    if (searchParams.get("catalog") === activeCatalogAction) {
      return
    }

    const nextSearchParams = new URLSearchParams(searchParams)
    if (activeCatalogAction === null) {
      nextSearchParams.delete("catalog")
    } else {
      nextSearchParams.set("catalog", activeCatalogAction)
    }
    setSearchParams(nextSearchParams, { replace: true })
  }, [estimateScreen, searchParams, setSearchParams])

  function commandIdempotencyKey(signature: string) {
    const existing = commandIdempotencyKeys.current.get(signature)
    if (existing) return existing
    const created = crypto.randomUUID()
    commandIdempotencyKeys.current.set(signature, created)
    return created
  }
  const canManage = Boolean(
    currentUser && isGlobalAdministrator(currentUser.globalRole)
  )
  const canEdit = canManage

  const catalogQuery = useQuery({
    queryKey: [...REPAIR_ESTIMATE_CATALOG_QUERY_KEY, "current"],
    queryFn: () => getCurrentRepairEstimateCatalog(accessToken!),
    enabled: Boolean(accessToken),
  })

  const currentCatalog = catalogQuery.data ?? null

  const catalogRequest = useMemo<RepairEstimateCatalogRequest | null>(() => {
    if (!accessToken || !currentCatalog) return null
    return {
      accessToken,
      catalogVersionId: currentCatalog.id,
    }
  }, [accessToken, currentCatalog])

  const refreshCatalog = () => {
    void queryClient.invalidateQueries({
      queryKey: REPAIR_ESTIMATE_CATALOG_QUERY_KEY,
    })
  }

  const createCatalogMutation = useMutation({
    mutationFn: () => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к maintenance-service.")
      }
      if (!canManage) {
        throw new Error("Недостаточно прав для создания единого каталога.")
      }
      const signature = "create-global-catalog"
      return createRepairEstimateCatalog(
        accessToken,
        commandIdempotencyKey(signature)
      ).then(() => ({ signature }))
    },
    onSuccess: ({ signature }) => {
      commandIdempotencyKeys.current.delete(signature)
      setCommandError(null)
      setEstimateScreen({ level: "root" })
      refreshCatalog()
    },
    onError: (error) => {
      setCommandError(
        error instanceof Error ? error.message : "Не удалось создать каталог."
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

  const furnitureCatalogQuery = useQuery({
    queryKey: ["estimate-settings", "repair-estimate-catalog-furniture"],
    queryFn: getRepairEstimateFurnitureCatalogSettings,
  })

  const estimateActions = useMemo(() => {
    return [
      REPAIR_ESTIMATE_CATALOG_COLOR_SETTINGS,
      catalogCanvasQuery.data,
      workCatalogQuery.data,
      materialCatalogQuery.data,
      furnitureCatalogQuery.data,
      REPAIR_ESTIMATE_CATALOG_COMMON_SETTINGS,
    ]
      .filter((action): action is EstimateCatalogSettingsActionDto =>
        Boolean(action)
      )
      .sort((left, right) => left.order - right.order)
  }, [
    catalogCanvasQuery.data,
    furnitureCatalogQuery.data,
    materialCatalogQuery.data,
    workCatalogQuery.data,
  ])

  useEffect(() => {
    if (
      hasSelectedInitialEstimateAction.current ||
      estimateScreen.level !== "root" ||
      !catalogRequest ||
      !currentCatalog ||
      estimateActions.length !== 6
    ) {
      return
    }

    const requestedAction = estimateActions.find(
      (action) => action.id === initialCatalogActionId.current
    )
    const defaultAction = estimateActions.find(
      (action) => action.id === "repair-estimate-catalog-canvas"
    )
    const action = requestedAction ?? defaultAction
    if (!action) {
      return
    }

    hasSelectedInitialEstimateAction.current = true
    setEstimateScreen({ level: "action", action })
  }, [catalogRequest, currentCatalog, estimateActions, estimateScreen.level])

  const estimateLoading =
    catalogCanvasQuery.isLoading ||
    workCatalogQuery.isLoading ||
    materialCatalogQuery.isLoading ||
    furnitureCatalogQuery.isLoading
  const estimateError =
    catalogCanvasQuery.error ??
    workCatalogQuery.error ??
    materialCatalogQuery.error ??
    furnitureCatalogQuery.error
  const estimateRefreshing =
    catalogCanvasQuery.isFetching ||
    workCatalogQuery.isFetching ||
    materialCatalogQuery.isFetching ||
    furnitureCatalogQuery.isFetching

  const refreshEstimateActions = () => {
    void queryClient.invalidateQueries({ queryKey: ["estimate-settings"] })
  }

  const activeEstimateActionId =
    estimateScreen.level === "root" ? null : estimateScreen.action.id
  const openEstimateAction = (action: EstimateCatalogSettingsActionDto) => {
    hasSelectedInitialEstimateAction.current = true
    setEstimateScreen({ level: "action", action })
  }
  const estimateActionNavigation =
    estimateActions.length === 6 ? (
      <EstimateActionNavigation
        actions={estimateActions}
        activeActionId={activeEstimateActionId}
        onSelect={openEstimateAction}
      />
    ) : null
  if (estimateScreen.level !== "root") {
    if (!catalogRequest || !currentCatalog) {
      return (
        <ErrorBox>
          Единый каталог смет временно недоступен.
        </ErrorBox>
      )
    }
    return (
      <div className="flex h-full min-h-0 flex-col gap-3">
        {estimateActionNavigation}
        <EstimateDrilldownView
          request={catalogRequest}
          screen={estimateScreen}
          readOnly={
            estimateScreen.action.id === "repair-estimate-catalog-colors"
              ? !canManage
              : !canEdit
          }
          onOpenCategory={(categoryId) =>
            setEstimateScreen({
              level: "category",
              action: estimateScreen.action,
              categoryId,
            })
          }
        />
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      {!accessToken ? (
        <CatalogNavigationState
          kind="error"
          title="Разделы каталога недоступны"
          description="Не получен токен доступа к maintenance-service."
        />
      ) : catalogQuery.isLoading ? (
        <SectionSkeleton label="Загружаем каталог смет…" />
      ) : catalogQuery.error ? (
        <CatalogNavigationState
          kind="error"
          title="Не удалось загрузить каталог смет"
          description={
            catalogQuery.error instanceof Error
              ? `Причина: ${catalogQuery.error.message}`
              : "Сервис каталога временно недоступен."
          }
        >
          <Button
            type="button"
            variant="outline"
            disabled={catalogQuery.isFetching}
            onClick={() => void catalogQuery.refetch()}
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Повторить загрузку
          </Button>
        </CatalogNavigationState>
      ) : !catalogRequest || !currentCatalog ? (
        <CatalogNavigationState
          kind="empty"
          title="Каталог смет ещё не создан"
          description={
            canManage
              ? "Создайте единый каталог — категории, работы, материалы и связи будут общими для всех складов."
              : "Единый каталог ещё не создан. Обратитесь к пользователю с правом управления или проверьте снова."
          }
        >
          {canManage && (
            <Button
              type="button"
              disabled={createCatalogMutation.isPending}
              onClick={() => createCatalogMutation.mutate()}
            >
              <HugeiconsIcon icon={DatabaseAddIcon} data-icon="inline-start" />
              Создать единый каталог
            </Button>
          )}
          <Button
            type="button"
            variant="outline"
            disabled={catalogQuery.isFetching}
            onClick={() => refreshCatalog()}
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Проверить снова
          </Button>
          {commandError && <ErrorBox>{commandError}</ErrorBox>}
        </CatalogNavigationState>
      ) : estimateLoading ? (
        <SectionSkeleton label="Подготавливаем разделы каталога смет…" />
      ) : estimateError || estimateActions.length !== 6 ? (
        <CatalogNavigationState
          kind="error"
          title="Не удалось загрузить разделы каталога"
          description={
            estimateError instanceof Error
              ? `Причина: ${estimateError.message}`
              : "Не все разделы каталога доступны. Повторите загрузку."
          }
        >
          <Button
            type="button"
            variant="outline"
            disabled={estimateRefreshing}
            onClick={refreshEstimateActions}
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Повторить загрузку
          </Button>
        </CatalogNavigationState>
      ) : (
        estimateActionNavigation
      )}
    </div>
  )
}
