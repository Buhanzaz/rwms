import { useMemo, useState, type ReactNode } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowRight01Icon,
  CanvasIcon,
  Delete01Icon,
  HammerIcon,
  Link01Icon,
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
  RepairEstimateCatalogSectionKind,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"
import {
  repairEstimateCatalogLinkTypeLabel,
  repairEstimateCatalogNodeTypeLabel,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

type SettingsAction =
  | (EstimateCatalogSettingsActionDto & { group: "estimate" })
  | (RepairSettingsActionDto & { group: "repair" })

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
  className,
}: {
  title: string
  count: number
  children: ReactNode
  className?: string
}) {
  return (
    <section
      className={cn(
        "flex min-h-0 flex-col overflow-hidden rounded-lg border bg-card",
        className
      )}
    >
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
          <Field>
            <FieldLabel>Тип</FieldLabel>
            <NativeSelect
              value={draft.nodeType}
              disabled={!state.allowTypeSelect}
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
                  "CATEGORY",
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

          <div className="grid gap-3 md:grid-cols-3">
            <Field>
              <FieldLabel>Сортировка</FieldLabel>
              <Input
                value={draft.sortOrder ?? ""}
                inputMode="numeric"
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    sortOrder: toNumberOrNull(event.target.value),
                  }))
                }
              />
            </Field>

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

            <Field>
              <FieldLabel>Длительность, мин</FieldLabel>
              <Input
                value={draft.durationMinutes ?? ""}
                disabled={draft.nodeType !== "WORK"}
                inputMode="numeric"
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    durationMinutes: toNumberOrNull(event.target.value),
                  }))
                }
              />
            </Field>
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
            <BooleanField
              title="Мебельная категория"
              checked={draft.furnitureCategory}
              disabled={draft.nodeType !== "CATEGORY"}
              onCheckedChange={(checked) =>
                setDraft((current) => ({
                  ...current,
                  furnitureCategory: checked,
                }))
              }
            />
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

          {error !== null && (
            <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
              {error}
            </div>
          )}
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

          <BooleanField
            title="Активно"
            checked={draft.active}
            onCheckedChange={(checked) =>
              setDraft((current) => ({ ...current, active: checked }))
            }
          />

          {error !== null && (
            <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
              {error}
            </div>
          )}
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
            Сохранить связь
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
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

function CategoryList({
  categories,
  selectedCategoryId,
  onSelect,
}: {
  categories: RepairEstimateCatalogNodeDto[]
  selectedCategoryId: string | null
  onSelect: (id: string) => void
}) {
  return (
    <div className="flex min-h-0 flex-col gap-2 overflow-auto">
      {categories.map((category) => (
        <Button
          key={category.id}
          type="button"
          variant={category.id === selectedCategoryId ? "secondary" : "outline"}
          className="h-auto justify-start px-3 py-2 text-left"
          onClick={() => onSelect(category.id)}
        >
          <span className="min-w-0 truncate">{category.name}</span>
        </Button>
      ))}
    </div>
  )
}

function CatalogSectionEditor({
  kind,
}: {
  kind: RepairEstimateCatalogSectionKind
}) {
  const queryClient = useQueryClient()
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(
    null
  )
  const [dialogState, setDialogState] = useState<NodeDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const sectionQuery = useQuery({
    queryKey: [...ESTIMATE_CATALOG_QUERY_KEY, "section", kind],
    queryFn: () => {
      switch (kind) {
        case "works":
          return getRepairEstimateWorkCatalogMock()
        case "materials":
          return getRepairEstimateMaterialCatalogMock()
        case "furniture":
          return getRepairEstimateFurnitureCatalogMock()
      }
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => {
      switch (kind) {
        case "works":
          return deleteRepairEstimateWorkCatalogItem(id)
        case "materials":
          return deleteRepairEstimateMaterialCatalogItem(id)
        case "furniture":
          return deleteRepairEstimateFurnitureCatalogItem(id)
      }
    },
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({
        queryKey: ESTIMATE_CATALOG_QUERY_KEY,
      })
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

  if (!sectionQuery.data) {
    return null
  }

  const section = sectionQuery.data
  const effectiveCategoryId = section.categories.some(
    (category) => category.id === selectedCategoryId
  )
    ? selectedCategoryId
    : (section.categories[0]?.id ?? null)
  const selectedCategory =
    section.categories.find(
      (category) => category.id === effectiveCategoryId
    ) ?? null
  const items =
    effectiveCategoryId === null
      ? []
      : getItemsForCategory(section, effectiveCategoryId)

  const save = (input: RepairEstimateCatalogNodeMutation) => {
    switch (kind) {
      case "works":
        return saveRepairEstimateWorkCatalogItem(input)
      case "materials":
        return saveRepairEstimateMaterialCatalogItem(input)
      case "furniture":
        return saveRepairEstimateFurnitureCatalogItem(input)
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CatalogMeta
          nodeCount={section.items.length}
          linkCount={section.links.length}
        />
        <Button
          type="button"
          disabled={selectedCategory === null}
          onClick={() => {
            if (selectedCategory === null) {
              return
            }

            setDialogState({
              title: `Добавить: ${section.title}`,
              description: selectedCategory.name,
              submitLabel: "Сохранить",
              allowTypeSelect: false,
              value: createBlankNodeMutation({
                nodeType: section.sectionType,
                parentId: selectedCategory.id,
                furnitureCategory:
                  kind === "furniture" && selectedCategory.furnitureCategory,
                sortOrder: items.length * 10 + 10,
              }),
              save,
            })
          }}
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Добавить
        </Button>
      </div>

      <div className="grid min-h-0 flex-1 gap-3 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <CategoryList
          categories={section.categories}
          selectedCategoryId={effectiveCategoryId}
          onSelect={setSelectedCategoryId}
        />

        <div className="min-h-0 overflow-auto rounded-lg border">
          <CatalogItemsTable
            section={section}
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
      </div>

      {error !== null && (
        <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      )}

      <NodeEditorDialog
        state={dialogState}
        onClose={() => setDialogState(null)}
        onSaved={() => {
          setError(null)
          void queryClient.invalidateQueries({
            queryKey: ESTIMATE_CATALOG_QUERY_KEY,
          })
        }}
      />
    </div>
  )
}

function CatalogItemsTable({
  section,
  items,
  onEdit,
  onDelete,
}: {
  section: RepairEstimateCatalogSectionDto
  items: RepairEstimateCatalogNodeDto[]
  onEdit: (node: RepairEstimateCatalogNodeDto) => void
  onDelete: (node: RepairEstimateCatalogNodeDto) => void
}) {
  return (
    <table className="w-full min-w-[56rem] border-collapse text-sm">
      <thead className="sticky top-0 bg-muted text-muted-foreground">
        <tr>
          <th className="px-3 py-2 text-left font-medium">Название</th>
          <th className="px-3 py-2 text-left font-medium">Код</th>
          <th className="px-3 py-2 text-left font-medium">Ед.</th>
          <th className="px-3 py-2 text-left font-medium">Цена</th>
          <th className="px-3 py-2 text-left font-medium">Кол.</th>
          {section.sectionType === "WORK" && (
            <th className="px-3 py-2 text-left font-medium">Мин.</th>
          )}
          <th className="px-3 py-2 text-left font-medium">Смета</th>
          <th className="px-3 py-2 text-left font-medium">Общий</th>
          <th className="px-3 py-2 text-right font-medium">Действия</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.id} className="border-t">
            <td className="max-w-72 px-3 py-2">
              <div className="truncate font-medium">{item.name}</div>
              {item.comment && (
                <div className="truncate text-xs text-muted-foreground">
                  {item.comment}
                </div>
              )}
            </td>
            <td className="px-3 py-2 font-mono text-xs">{item.code}</td>
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
    x: node.canvasX ?? 40 + (index % 3) * 320,
    y: node.canvasY ?? 210 + Math.floor(index / 3) * 160,
  }
}

function CatalogCanvasEditor() {
  const queryClient = useQueryClient()
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(
    null
  )
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedLinkId, setSelectedLinkId] = useState<string | null>(null)
  const [nodeDialogState, setNodeDialogState] =
    useState<NodeDialogState | null>(null)
  const [linkDialogState, setLinkDialogState] =
    useState<LinkDialogState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const canvasQuery = useQuery({
    queryKey: [...ESTIMATE_CATALOG_QUERY_KEY, "canvas"],
    queryFn: getRepairEstimateCatalogCanvasMock,
  })

  const deleteNodeMutation = useMutation({
    mutationFn: deleteRepairEstimateCatalogCanvasNode,
    onSuccess: () => {
      setError(null)
      setSelectedNodeId(null)
      void queryClient.invalidateQueries({
        queryKey: ESTIMATE_CATALOG_QUERY_KEY,
      })
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
      void queryClient.invalidateQueries({
        queryKey: ESTIMATE_CATALOG_QUERY_KEY,
      })
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось удалить связь"
      )
    },
  })

  const resetMutation = useMutation({
    mutationFn: resetRepairEstimateCatalogCanvasMock,
    onSuccess: () => {
      setError(null)
      setSelectedNodeId(null)
      setSelectedLinkId(null)
      void queryClient.invalidateQueries({
        queryKey: ESTIMATE_CATALOG_QUERY_KEY,
      })
    },
  })

  if (canvasQuery.isLoading) {
    return <SectionSkeleton />
  }

  if (!canvasQuery.data) {
    return null
  }

  const data = canvasQuery.data
  const effectiveCategoryId = data.categories.some(
    (category) => category.id === selectedCategoryId
  )
    ? selectedCategoryId
    : (data.categories[0]?.id ?? null)
  const canvasNodes = getCanvasNodes(data, effectiveCategoryId)
  const selectedCategory =
    data.categories.find((category) => category.id === effectiveCategoryId) ??
    null
  const selectedNode =
    data.nodes.find((node) => node.id === selectedNodeId) ?? null
  const selectedLink =
    data.links.find((link) => link.id === selectedLinkId) ?? null
  const nodeIds = new Set(canvasNodes.map((node) => node.id))
  const canvasLinks = data.links.filter(
    (link) => nodeIds.has(link.sourceNodeId) && nodeIds.has(link.targetNodeId)
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CatalogMeta
          nodeCount={data.nodes.length}
          linkCount={data.links.length}
        />
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={() =>
              setNodeDialogState({
                title: "Добавить категорию",
                description: "Корневой раздел каталога смет",
                submitLabel: "Сохранить",
                allowTypeSelect: false,
                value: createBlankNodeMutation({
                  nodeType: "CATEGORY",
                  parentId: null,
                  furnitureCategory: false,
                  sortOrder: data.categories.length * 10 + 10,
                }),
                save: saveRepairEstimateCatalogCanvasNode,
              })
            }
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Категория
          </Button>
          <Button
            type="button"
            disabled={selectedCategory === null}
            onClick={() => {
              if (selectedCategory === null) {
                return
              }

              setNodeDialogState({
                title: "Добавить блок",
                description: selectedCategory.name,
                submitLabel: "Сохранить",
                allowTypeSelect: true,
                value: createBlankNodeMutation({
                  nodeType: "WORK",
                  parentId: selectedCategory.id,
                  furnitureCategory: selectedCategory.furnitureCategory,
                  sortOrder: canvasNodes.length * 10 + 10,
                }),
                save: saveRepairEstimateCatalogCanvasNode,
              })
            }}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Блок
          </Button>
          <Button
            type="button"
            variant="outline"
            disabled={canvasNodes.length < 2}
            onClick={() => {
              const source = selectedNode ?? canvasNodes[0]
              const target =
                canvasNodes.find(
                  (node) =>
                    node.id !== source.id && node.nodeType !== "CATEGORY"
                ) ?? canvasNodes[1]

              setLinkDialogState({
                nodes: canvasNodes,
                value: {
                  sourceNodeId: source.id,
                  targetNodeId: target.id,
                  linkType: "FOLLOW_UP",
                  active: true,
                  sortOrder: canvasLinks.length * 10 + 10,
                  comment: null,
                },
              })
            }}
          >
            <HugeiconsIcon icon={Link01Icon} data-icon="inline-start" />
            Связь
          </Button>
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

      <div className="grid min-h-0 flex-1 gap-3 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <CategoryList
          categories={data.categories}
          selectedCategoryId={effectiveCategoryId}
          onSelect={(id) => {
            setSelectedCategoryId(id)
            setSelectedNodeId(null)
            setSelectedLinkId(null)
          }}
        />

        <div className="min-h-[28rem] overflow-auto rounded-lg border bg-background">
          <CatalogCanvas
            nodes={canvasNodes}
            links={canvasLinks}
            selectedNodeId={selectedNodeId}
            selectedLinkId={selectedLinkId}
            onSelectNode={(node) => {
              setSelectedNodeId(node.id)
              setSelectedLinkId(null)
            }}
            onSelectLink={(link) => {
              setSelectedLinkId(link.id)
              setSelectedNodeId(null)
            }}
          />
        </div>
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

            setNodeDialogState({
              title: "Редактировать блок",
              description: selectedNode.code,
              submitLabel: "Сохранить",
              allowTypeSelect: selectedNode.nodeType !== "CATEGORY",
              value: createNodeMutation(selectedNode),
              save: saveRepairEstimateCatalogCanvasNode,
            })
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

      {error !== null && (
        <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      )}

      <NodeEditorDialog
        state={nodeDialogState}
        onClose={() => setNodeDialogState(null)}
        onSaved={() => {
          setError(null)
          void queryClient.invalidateQueries({
            queryKey: ESTIMATE_CATALOG_QUERY_KEY,
          })
        }}
      />
      <LinkEditorDialog
        state={linkDialogState}
        onClose={() => setLinkDialogState(null)}
        onSaved={() => {
          setError(null)
          void queryClient.invalidateQueries({
            queryKey: ESTIMATE_CATALOG_QUERY_KEY,
          })
        }}
      />
    </div>
  )
}

function CatalogCanvas({
  nodes,
  links,
  selectedNodeId,
  selectedLinkId,
  onSelectNode,
  onSelectLink,
}: {
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
  selectedNodeId: string | null
  selectedLinkId: string | null
  onSelectNode: (node: RepairEstimateCatalogNodeDto) => void
  onSelectLink: (link: RepairEstimateCatalogLinkDto) => void
}) {
  const positionedNodes = nodes.map((node, index) => ({
    node,
    position: getCanvasPosition(node, index),
  }))
  const positionById = new Map(
    positionedNodes.map(({ node, position }) => [node.id, position])
  )
  const height = Math.max(
    520,
    ...positionedNodes.map(({ position }) => position.y + 150)
  )
  const width = Math.max(
    980,
    ...positionedNodes.map(({ position }) => position.x + 280)
  )

  return (
    <div className="relative" style={{ width, height }}>
      <svg className="absolute inset-0 h-full w-full" aria-hidden>
        {links.map((link) => {
          const source = positionById.get(link.sourceNodeId)
          const target = positionById.get(link.targetNodeId)
          if (!source || !target) {
            return null
          }

          return (
            <g key={link.id}>
              <line
                x1={source.x + 130}
                y1={source.y + 118}
                x2={target.x + 130}
                y2={target.y}
                stroke="currentColor"
                strokeWidth={link.id === selectedLinkId ? 3 : 2}
                className={cn(
                  link.linkType === "DEPENDENCY"
                    ? "text-primary"
                    : "text-muted-foreground"
                )}
              />
              <circle
                cx={(source.x + target.x) / 2 + 130}
                cy={(source.y + target.y) / 2 + 59}
                r={10}
                className="cursor-pointer fill-background stroke-muted-foreground"
                onClick={() => onSelectLink(link)}
              />
            </g>
          )
        })}
      </svg>

      {positionedNodes.map(({ node, position }) => (
        <button
          key={node.id}
          type="button"
          className={cn(
            "absolute flex min-h-28 w-64 flex-col items-start gap-2 rounded-lg border bg-card px-3 py-3 text-left text-sm shadow-sm transition-colors hover:bg-accent",
            node.id === selectedNodeId && "border-primary bg-accent"
          )}
          style={{ left: position.x, top: position.y }}
          onClick={() => onSelectNode(node)}
        >
          <span className="flex w-full items-center justify-between gap-2">
            <span className="truncate font-medium">{node.name}</span>
            <Badge variant="secondary">
              {repairEstimateCatalogNodeTypeLabel(node.nodeType)}
            </Badge>
          </span>
          <span className="font-mono text-xs text-muted-foreground">
            {node.code}
          </span>
          <span className="flex flex-wrap gap-1">
            {node.includeInEstimate && <Badge variant="outline">Смета</Badge>}
            {node.commonItem && <Badge variant="outline">Общий</Badge>}
            {node.furnitureCategory && <Badge variant="outline">Мебель</Badge>}
          </span>
        </button>
      ))}
    </div>
  )
}

function EstimateCatalogWorkspace({
  action,
}: {
  action: EstimateCatalogSettingsActionDto
}) {
  switch (action.id) {
    case "repair-estimate-catalog-canvas":
      return <CatalogCanvasEditor />
    case "repair-estimate-catalog-works":
      return <CatalogSectionEditor kind="works" />
    case "repair-estimate-catalog-materials":
      return <CatalogSectionEditor kind="materials" />
    case "repair-estimate-catalog-furniture":
      return <CatalogSectionEditor kind="furniture" />
  }
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
  const [selectedAction, setSelectedAction] = useState<SettingsAction | null>(
    null
  )

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

  const selectedEstimateAction =
    selectedAction?.group === "estimate" ? selectedAction : null
  const selectedRepairAction =
    selectedAction?.group === "repair" ? selectedAction : null
  const estimateLoading =
    catalogCanvasQuery.isLoading ||
    workCatalogQuery.isLoading ||
    materialCatalogQuery.isLoading ||
    furnitureCatalogQuery.isLoading

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <SettingsSection
        title="Настройка смет"
        count={estimateActions.length}
        className={selectedEstimateAction ? "flex-[3]" : "flex-1"}
      >
        <div className="flex min-h-0 flex-col gap-4">
          {estimateLoading ? (
            <SectionSkeleton />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {estimateActions.map((action) => (
                <SettingsActionButton
                  key={action.id}
                  action={action}
                  active={selectedAction?.id === action.id}
                  onClick={setSelectedAction}
                />
              ))}
            </div>
          )}

          {selectedEstimateAction && (
            <div className="min-h-[24rem] flex-1 overflow-hidden rounded-lg border bg-background p-3">
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <Badge variant="secondary">
                  {selectedEstimateAction.legacyViewId}
                </Badge>
                <Badge variant="outline">
                  {selectedEstimateAction.legacyRoute}
                </Badge>
              </div>
              <EstimateCatalogWorkspace action={selectedEstimateAction} />
            </div>
          )}
        </div>
      </SettingsSection>

      <SettingsSection
        title="Настройка ремонтов"
        count={repairActions.length}
        className={selectedEstimateAction ? "max-h-56" : "flex-1"}
      >
        {repairSettingsQuery.isLoading ? (
          <SectionSkeleton />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {repairActions.map((action) => (
                <SettingsActionButton
                  key={action.id}
                  action={action}
                  active={selectedAction?.id === action.id}
                  onClick={setSelectedAction}
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
