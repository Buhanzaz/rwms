import {
  type ChangeEvent,
  type ReactNode,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react"
import {
  DndContext,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core"
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  CheckmarkCircle02Icon,
  Delete02Icon,
  FileImportIcon,
  FileUploadIcon,
  InformationCircleIcon,
  Loading03Icon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
import { Separator } from "@/components/ui/separator"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import {
  cancelHtmlImport,
  commitHtmlImport,
  createHtmlImport,
  createIdempotencyKey,
  getHtmlImport,
  htmlImportsQueryKey,
  listAssetRentalItems,
  listHtmlImportRows,
  listHtmlImports,
  replaceHtmlImportMedia,
  retryHtmlImportMedia,
  saveHtmlImportPlan,
  skipHtmlImportMedia,
  type HtmlImport,
  type HtmlImportCabin,
  type HtmlImportFieldChoice,
  type HtmlImportMappedStatus,
  type HtmlImportMapping,
  type HtmlImportMappingAction,
  type HtmlImportRow,
  type HtmlImportRowAction,
  type HtmlImportState,
  type HtmlImportStatusCandidate,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  HtmlImportCabinPairingBoard,
  type ResolvedHtmlCabinRow,
} from "@/features/rental-items/html-import/html-import-cabin-pairing-board"
import {
  htmlImportRentalNumberError,
  normalizeHtmlImportRentalNumber,
} from "@/features/rental-items/html-import/html-import-number"
import { findHtmlImportRentalNumberConflicts } from "@/features/rental-items/html-import/html-import-number-conflicts"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"

type WizardStep = "source" | "diagnostics" | "mappings" | "cabins" | "summary"

type MappingDecision = {
  action: HtmlImportMappingAction
  targetId: string | null
  stagedName: string
}

type StatusMappingDecision = {
  targetStatus: HtmlImportMappedStatus | null
}

type RowDecision = {
  action: HtmlImportRowAction
  proposedNumber: string | null
  existingRentalItemId: string | null
  targetExpectedVersion: number | null
  rentalTypeId: string | null
  dimensionId: string | null
  finishingId: string | null
  fieldDecisions: Record<string, HtmlImportFieldChoice>
}

type PlanIssue = {
  id: string
  message: string
}

const TERMINAL_IMPORT_STATES = new Set<HtmlImportState>([
  "COMPLETED",
  "COMPLETED_WITH_WARNINGS",
])
const PROGRESSING_IMPORT_STATES = new Set<HtmlImportState>([
  "COMMITTING",
  "ASSETS_COMMITTED",
  "MEDIA_IMPORTING",
])
const SUMMARY_IMPORT_STATES = new Set<HtmlImportState>([
  "READY",
  "COMMITTING",
  "ASSETS_COMMITTED",
  "MEDIA_IMPORTING",
  "COMPLETED",
  "COMPLETED_WITH_WARNINGS",
  "FAILED",
])
const MEDIA_RETRY_IMPORT_STATES = new Set<HtmlImportState>([
  "ASSETS_COMMITTED",
  "FAILED",
])
const CANCELLABLE_IMPORT_STATES = new Set<HtmlImportState>([
  "DRAFT",
  "REVIEW_REQUIRED",
  "READY",
])
const HTML_FILE_ACCEPT = ".html,text/html"
const INVALID_NUMBER_CODE = "INVALID_RENTAL_NUMBER"
const SKIPPED_PHOTO_CODE = "INVALID_YANDEX_PUBLIC_URL"
const YANDEX_RESOURCE_REJECTED_CODE = "YANDEX_RESOURCE_REJECTED"
const YANDEX_DISK_PUBLIC_URL =
  /^https:\/\/disk\.yandex\.ru\/d\/[A-Za-z0-9_-]{14}$/
const CATALOG_CONFLICTS = [
  {
    code: "TYPE_REQUIRES_MAPPING",
    kind: "TYPE",
    decisionKey: "rentalTypeId",
    label: "Тип не распознан",
    selectLabel: "Выберите тип",
    aliases: ["rentaltype", "type"],
  },
  {
    code: "DIMENSION_REQUIRES_MAPPING",
    kind: "DIMENSION",
    decisionKey: "dimensionId",
    label: "Размер не распознан",
    selectLabel: "Выберите размер",
    aliases: ["dimension", "dimensions", "size"],
  },
  {
    code: "FINISHING_REQUIRES_MAPPING",
    kind: "FINISHING",
    decisionKey: "finishingId",
    label: "Отделка не распознана",
    selectLabel: "Выберите отделку",
    aliases: ["finishing", "finish"],
  },
] as const

type CatalogConflict = (typeof CATALOG_CONFLICTS)[number]
const HTML_IMPORT_STATUS_IDS = [
  "RENTED",
  "BOOKED",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
  "CAPITAL_REPAIR",
  "AFTER_RENT",
  "WAITING_ESTIMATE_CONFIRMATION",
  "SALE",
  "USED_SALE",
  "RESERVED",
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
] as const satisfies readonly HtmlImportMappedStatus[]
const HTML_IMPORT_STATUS_TARGETS: Array<{
  id: HtmlImportMappedStatus
  label: string
}> = HTML_IMPORT_STATUS_IDS.map((status) => ({
  id: status,
  label: RENTAL_ITEM_STATUS_LABEL[status],
}))
const HTML_IMPORT_STATUS_TARGET_IDS = new Set<HtmlImportMappedStatus>(
  HTML_IMPORT_STATUS_IDS
)

const CABIN_FIELD_LABELS: Record<string, string> = {
  number: "Номер",
  legacyNumber: "Номер",
  rentalType: "Тип",
  type: "Тип",
  dimensions: "Габариты",
  dimension: "Габариты",
  finishing: "Отделка",
  finish: "Отделка",
  category: "Категория",
  characteristics: "Характеристики",
  furniture: "Комплектация",
  linoleum: "Линолеум",
  comment: "Комментарий",
  generalComment: "Комментарий",
  "passport.storageState": "Место хранения",
  "passport.shipmentDate": "Дата отгрузки",
  "passport.tenant": "Арендатор",
  "passport.price": "Стоимость",
}

/**
 * HTML imports may contain a different cabin composition or equipment than an
 * already existing cabin. Those values are optional import enrichment, not a
 * reason to overwrite the warehouse record while the operator is resolving a
 * number match. Keep the names here aligned with the service merge fields.
 */
const OPTIONAL_MERGE_FIELD_ALIASES: Record<string, string> = {
  rentaltype: "rentalType",
  type: "rentalType",
  dimension: "dimension",
  dimensions: "dimension",
  size: "dimension",
  category: "category",
  characteristics: "characteristics",
  characteristic: "characteristics",
  furniture: "furniture",
  equipment: "furniture",
  contents: "furniture",
}

const OPTIONAL_IMPORT_MAPPING_KINDS = new Set([
  "TYPE",
  "DIMENSION",
  "CATEGORY",
  "CHARACTERISTIC",
])

function importStateTitle(state: HtmlImportState) {
  const labels: Record<HtmlImportState, string> = {
    DRAFT: "Черновик",
    REVIEW_REQUIRED: "Требуется проверка",
    READY: "Готов к запуску",
    COMMITTING: "Импорт запускается",
    ASSETS_COMMITTED: "Бытовки созданы",
    MEDIA_IMPORTING: "Импортируются медиа",
    COMPLETED: "Импорт завершён",
    COMPLETED_WITH_WARNINGS: "Импорт завершён с предупреждениями",
    FAILED: "Импорт завершился с ошибкой",
  }

  return labels[state]
}

function importStateBadgeVariant(state: HtmlImportState) {
  switch (state) {
    case "FAILED":
      return "destructive" as const
    case "REVIEW_REQUIRED":
    case "COMMITTING":
    case "ASSETS_COMMITTED":
    case "MEDIA_IMPORTING":
    case "COMPLETED_WITH_WARNINGS":
      return "secondary" as const
    case "DRAFT":
      return "outline" as const
    case "READY":
    case "COMPLETED":
      return "default" as const
  }
}

function importProgressValue(state: HtmlImportState) {
  switch (state) {
    case "DRAFT":
      return 10
    case "REVIEW_REQUIRED":
      return 20
    case "READY":
      return 40
    case "COMMITTING":
      return 60
    case "ASSETS_COMMITTED":
      return 75
    case "MEDIA_IMPORTING":
      return 90
    case "COMPLETED":
    case "COMPLETED_WITH_WARNINGS":
      return 100
    case "FAILED":
      return 75
  }
}

function importStep(importRecord: HtmlImport): WizardStep {
  if (SUMMARY_IMPORT_STATES.has(importRecord.state)) {
    return "summary"
  }

  return "diagnostics"
}

function isImportInProgress(importRecord: HtmlImport | null) {
  return (
    importRecord !== null && PROGRESSING_IMPORT_STATES.has(importRecord.state)
  )
}

function canRetryImportMedia(importRecord: HtmlImport) {
  return (
    importRecord.mediaJobId !== null &&
    MEDIA_RETRY_IMPORT_STATES.has(importRecord.state)
  )
}

function mediaFailureDescription(code: string) {
  switch (code) {
    case YANDEX_RESOURCE_REJECTED_CODE:
      return "Яндекс.Диск отклонил хотя бы одну ссылку на фото. Проверьте ссылку у нужной бытовки, замените её или завершите импорт без медиа."
    case "YANDEX_UNAVAILABLE":
      return "Яндекс.Диск временно недоступен. Повторите импорт медиа позже."
    default:
      return "Медиаэтап не завершился. Его можно повторить или, если фото сейчас не нужны, явно пропустить."
  }
}

function isYandexDiskPublicUrl(value: string) {
  return YANDEX_DISK_PUBLIC_URL.test(value.trim())
}

function commandError(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    return "Импорт уже изменён другим пользователем. Обновите данные и повторите действие."
  }
  if (error instanceof Error) return error.message
  return "Не удалось выполнить операцию импорта."
}

function mappingWords(value: string) {
  const normalized = value
    .toLocaleLowerCase("ru-RU")
    .replaceAll("ё", "е")
    .replace(/блок\s*[-–—]?\s*контейнер/giu, "бк")
    .replace(/сан\.?\s*блок/giu, "санблок")
    .replace(
      /(^|[^\p{L}\p{N}])эл\s*[-–—]?\s*ка(?=$|[^\p{L}\p{N}])/giu,
      "$1электрика кк"
    )
    .replace(/(?:2|двух)\s*[-–—]?\s*ярусн\p{L}*/giu, "двухъярусная")
    .replace(/(\p{L})(\d)/gu, "$1 $2")
    .replace(/(\d)(\p{L})/gu, "$1 $2")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
  return new Set(
    normalized
      .split(/\s+/)
      .filter(
        (word) =>
          word.length > 0 &&
          !["и", "в", "на", "не", "эконом"].includes(word) &&
          (word.length > 1 || /^\d+$/.test(word))
      )
  )
}

function mappingWordSimilarity(left: Set<string>, right: Set<string>) {
  if (left.size === 0 || right.size === 0) return 0
  const shared = Array.from(left).filter((word) => right.has(word)).length
  return (2 * shared) / (left.size + right.size)
}

function automaticMappingTargetId(mapping: HtmlImportMapping) {
  const sourceValue = mapping.suggestedValue ?? mapping.sourceValue
  const sourceKey = htmlImportSourceValueKey(sourceValue)
  const exact = mapping.targets.find(
    (target) => htmlImportSourceValueKey(target.label) === sourceKey
  )
  if (exact) return exact.id

  const sourceWords = mappingWords(sourceValue)
  const ranked = mapping.targets
    .map((target) => ({
      target,
      score: mappingWordSimilarity(sourceWords, mappingWords(target.label)),
    }))
    .sort((left, right) => right.score - left.score)
  const best = ranked[0]
  if (!best || best.score < 0.6) return null
  const runnerUp = ranked[1]
  if (
    runnerUp &&
    runnerUp.score >= 0.6 &&
    best.score - runnerUp.score < 0.1
  ) {
    return null
  }
  return best.target.id
}

function normalizedHtmlImportFieldKey(value: string) {
  return value.trim().toLocaleLowerCase("ru-RU")
}

function optionalMergeFieldFor(value: string) {
  return OPTIONAL_MERGE_FIELD_ALIASES[normalizedHtmlImportFieldKey(value)]
}

function isOptionalImportMapping(mapping: HtmlImportMapping) {
  return (
    mapping.group === "EQUIPMENT" ||
    OPTIONAL_IMPORT_MAPPING_KINDS.has(
      mapping.kind?.trim().toLocaleUpperCase("ru-RU") ?? ""
    )
  )
}

function mappingDecisionFor(mapping: HtmlImportMapping): MappingDecision {
  const automaticTargetId =
    mapping.plannedTargetId ??
    mapping.suggestedTargetId ??
    automaticMappingTargetId(mapping)
  const requestedAction =
    mapping.plannedAction ??
    (automaticTargetId ? "MAP" : "IGNORE")
  // An incomplete persisted mapping must not turn an optional import value
  // into a blocking plan error. A consciously selected MAP in local state is
  // still validated below, so an operator cannot accidentally save a partial
  // mapping they started editing.
  const action =
    isOptionalImportMapping(mapping) &&
    requestedAction === "MAP" &&
    automaticTargetId === null
      ? "IGNORE"
      : requestedAction
  return {
    action,
    targetId: automaticTargetId,
    stagedName: mapping.suggestedValue ?? mapping.sourceValue,
  }
}

function statusMappingDecisionFor(
  candidate: HtmlImportStatusCandidate
): StatusMappingDecision {
  return {
    targetStatus:
      candidate.plannedTargetStatus ?? candidate.suggestedTargetStatus,
  }
}

function htmlImportMappedStatus(
  value: string | null
): HtmlImportMappedStatus | null {
  return value !== null &&
    HTML_IMPORT_STATUS_TARGET_IDS.has(value as HtmlImportMappedStatus)
    ? (value as HtmlImportMappedStatus)
    : null
}

function htmlImportSourceValueKey(value: string) {
  return value.trim().toLocaleLowerCase("ru-RU")
}

function resolvedRowStatus(
  row: HtmlImportRow,
  statusCandidates: HtmlImportStatusCandidate[],
  decisions: Record<string, StatusMappingDecision>
) {
  const sourceStatus = cabinFieldValue(row.source, ["status"])
  const directStatus = htmlImportMappedStatus(sourceStatus)
  if (directStatus !== null) return directStatus
  if (sourceStatus === null) return null

  const candidate = statusCandidates.find(
    (value) =>
      htmlImportSourceValueKey(value.sourceValue) ===
      htmlImportSourceValueKey(sourceStatus)
  )
  if (!candidate) return null

  return (decisions[candidate.id] ?? statusMappingDecisionFor(candidate))
    .targetStatus
}

function autoFieldDecisions(row: HtmlImportRow) {
  if (row.existing === null) return { ...row.fieldDecisions }

  const decisions = { ...row.fieldDecisions }
  const keys = new Set([
    ...Object.keys(row.source.fields),
    ...Object.keys(row.existing.fields),
  ])

  keys.forEach((key) => {
    const optionalField = optionalMergeFieldFor(key)
    const priorDecision =
      decisions[key] ??
      (optionalField ? decisions[optionalField] : undefined)
    if (priorDecision) {
      // The service consumes canonical merge fields, while the row preview may
      // use a legacy alias such as `type` or `dimensions`. Preserve an explicit
      // persisted choice on both names rather than creating a contradictory
      // default choice.
      if (optionalField) {
        decisions[key] ??= priorDecision
        decisions[optionalField] ??= priorDecision
      }
      return
    }

    if (optionalField) {
      // The default for a matched cabin is deliberately conservative: do not
      // replace its type, dimensions, category, characteristics or equipment
      // just because HTML happened to contain another value.
      decisions[key] = "TARGET"
      decisions[optionalField] = "TARGET"
      return
    }

    const source = row.source.fields[key] ?? null
    const existing = row.existing?.fields[key] ?? null
    if (!source && existing) decisions[key] = "TARGET"
    if (source && !existing) decisions[key] = "SOURCE"
    if (source && existing && source === existing) decisions[key] = "TARGET"
  })

  return decisions
}

function rowDecisionFor(row: HtmlImportRow): RowDecision {
  const action =
    row.plannedAction ??
    row.suggestedAction ??
    (row.existing ? "MERGE" : "CREATE")
  const existingRentalItemId =
    row.plannedExistingRentalItemId ?? row.existing?.id ?? null
  return {
    action,
    proposedNumber: row.proposedNumber,
    existingRentalItemId,
    targetExpectedVersion:
      existingRentalItemId !== null &&
      row.existing?.id === existingRentalItemId &&
      row.existing.version !== null
        ? row.existing.version
        : null,
    rentalTypeId: row.plannedRentalTypeId,
    dimensionId: row.plannedDimensionId,
    finishingId: row.plannedFinishingId,
    fieldDecisions: autoFieldDecisions(row),
  }
}

function htmlWinsMergeChoices(row: HtmlImportRow) {
  const choices: Record<string, HtmlImportFieldChoice> = {
    rentalType: "TARGET",
    dimension: "TARGET",
    finishing: "SOURCE",
    status: "SOURCE",
  }
  const aliases: Record<string, string> = {
    rentaltype: "rentalType",
    type: "rentalType",
    dimension: "dimension",
    dimensions: "dimension",
    finishing: "finishing",
    finish: "finishing",
    category: "category",
    characteristics: "characteristics",
    characteristic: "characteristics",
    linoleum: "linoleum",
    status: "status",
    comment: "comment",
    generalcomment: "comment",
    furniture: "furniture",
  }

  Object.keys(row.source.fields).forEach((key) => {
    const normalized = normalizedHtmlImportFieldKey(key)
    if (normalized.startsWith("passport.")) {
      choices[`passport.${key.slice(key.indexOf(".") + 1)}`] = "SOURCE"
      return
    }
    const optionalField = optionalMergeFieldFor(key)
    const canonical = optionalField ?? aliases[normalized]
    if (canonical) {
      const winner: HtmlImportFieldChoice = optionalField
        ? "TARGET"
        : "SOURCE"
      choices[canonical] = winner
      choices[key] = winner
    }
  })

  return choices
}

function cabinFieldLabel(key: string) {
  if (CABIN_FIELD_LABELS[key]) return CABIN_FIELD_LABELS[key]
  return key.replace(/([a-z])([A-Z])/g, "$1 $2")
}

function fieldConflicts(row: HtmlImportRow) {
  if (row.existing === null) return []

  return Array.from(
    new Set([
      ...Object.keys(row.source.fields),
      ...Object.keys(row.existing.fields),
    ])
  ).filter((key) => {
    const source = row.source.fields[key] ?? null
    const existing = row.existing?.fields[key] ?? null
    if (key === "furniture") return Boolean(source)
    return Boolean(source && existing && source !== existing)
  })
}

function cabinFieldValue(cabin: HtmlImportCabin, acceptedKeys: string[]) {
  const found = Object.entries(cabin.fields).find(([key]) =>
    acceptedKeys.includes(key.toLowerCase())
  )
  return found?.[1] ?? null
}

function hasRowDiagnostic(row: HtmlImportRow, code: string) {
  return row.diagnostics.some((diagnostic) => diagnostic.code === code)
}

function catalogMappingForRow(
  row: HtmlImportRow,
  conflict: CatalogConflict,
  mappings: HtmlImportMapping[]
) {
  const sourceValue = cabinFieldValue(row.source, [...conflict.aliases])
  if (sourceValue === null) return null
  const sourceKey = htmlImportSourceValueKey(sourceValue)

  return (
    mappings.find(
      (mapping) =>
        mapping.kind?.toUpperCase() === conflict.kind &&
        htmlImportSourceValueKey(mapping.sourceValue) === sourceKey
    ) ?? null
  )
}

function catalogConflictResolved(
  row: HtmlImportRow,
  decision: RowDecision,
  conflict: CatalogConflict,
  mappings: HtmlImportMapping[],
  mappingDecisions: Record<string, MappingDecision>
) {
  if (decision[conflict.decisionKey] !== null) return true
  const mapping = catalogMappingForRow(row, conflict, mappings)
  if (mapping === null) return true
  const mappingDecision =
    mappingDecisions[mapping.id] ?? mappingDecisionFor(mapping)

  if (mappingDecision.action === "MAP") {
    return mappingDecision.targetId !== null
  }
  if (mappingDecision.action === "CREATE") {
    return Boolean(mappingDecision.stagedName.trim())
  }
  return true
}

function rowDiagnosticNeedsManualResolution(
  row: HtmlImportRow,
  decision: RowDecision,
  code: string,
  mappings: HtmlImportMapping[],
  mappingDecisions: Record<string, MappingDecision>,
  statusCandidates: HtmlImportStatusCandidate[],
  statusMappingDecisions: Record<string, StatusMappingDecision>
) {
  if (code === SKIPPED_PHOTO_CODE) return false
  const catalogConflict = CATALOG_CONFLICTS.find(
    (candidate) => candidate.code === code
  )
  if (catalogConflict) {
    return !catalogConflictResolved(
      row,
      decision,
      catalogConflict,
      mappings,
      mappingDecisions
    )
  }
  if (code === "STATUS_REQUIRES_MAPPING") {
    return (
      resolvedRowStatus(row, statusCandidates, statusMappingDecisions) === null
    )
  }
  return true
}

function effectiveRowDecisionFor(
  row: HtmlImportRow,
  explicitDecision: RowDecision | undefined,
  mappings: HtmlImportMapping[],
  mappingDecisions: Record<string, MappingDecision>,
  statusCandidates: HtmlImportStatusCandidate[],
  statusMappingDecisions: Record<string, StatusMappingDecision>
) {
  if (explicitDecision) return explicitDecision
  const decision = rowDecisionFor(row)
  if (decision.action !== "REVIEW" || row.existing !== null) return decision

  const unresolvedError = row.diagnostics.some(
    (diagnostic) =>
      diagnostic.severity === "ERROR" &&
      rowDiagnosticNeedsManualResolution(
        row,
        decision,
        diagnostic.code,
        mappings,
        mappingDecisions,
        statusCandidates,
        statusMappingDecisions
      )
  )
  if (unresolvedError || row.diagnostics.length === 0) return decision

  return {
    ...decision,
    action: "CREATE" as const,
    existingRentalItemId: null,
    targetExpectedVersion: null,
  }
}

function rowNeedsManualResolution(
  row: HtmlImportRow,
  mappings: HtmlImportMapping[],
  mappingDecisions: Record<string, MappingDecision>,
  statusCandidates: HtmlImportStatusCandidate[],
  statusMappingDecisions: Record<string, StatusMappingDecision>
) {
  if (row.existing !== null) return true
  const decision = rowDecisionFor(row)
  const unresolvedError = row.diagnostics.some(
    (diagnostic) =>
      diagnostic.severity === "ERROR" &&
      rowDiagnosticNeedsManualResolution(
        row,
        decision,
        diagnostic.code,
        mappings,
        mappingDecisions,
        statusCandidates,
        statusMappingDecisions
      )
  )
  const unexplainedReview =
    (row.suggestedAction === "REVIEW" || row.plannedAction === "REVIEW") &&
    row.diagnostics.length === 0
  return unresolvedError || unexplainedReview
}

function conflictSummary(
  rows: HtmlImportRow[],
  mappings: HtmlImportMapping[],
  mappingDecisions: Record<string, MappingDecision>,
  statusCandidates: HtmlImportStatusCandidate[],
  statusMappingDecisions: Record<string, StatusMappingDecision>,
  manualConflictRows: HtmlImportRow[],
  numberConflicts: ReadonlyMap<string, readonly string[]>
) {
  const knownErrorCodes = new Set([
    INVALID_NUMBER_CODE,
    ...CATALOG_CONFLICTS.map((conflict) => conflict.code),
    "STATUS_REQUIRES_MAPPING",
  ])
  const count = (code: string) =>
    rows.reduce(
      (total, row) =>
        total +
        row.diagnostics.filter(
          (diagnostic) =>
            diagnostic.code === code &&
            rowDiagnosticNeedsManualResolution(
              row,
              rowDecisionFor(row),
              diagnostic.code,
              mappings,
              mappingDecisions,
              statusCandidates,
              statusMappingDecisions
            )
        ).length,
      0
    )
  const rawCount = (code: string) =>
    rows.reduce(
      (total, row) =>
        total +
        row.diagnostics.filter((diagnostic) => diagnostic.code === code).length,
      0
    )

  return {
    rows: manualConflictRows.length,
    invalidNumbers: count(INVALID_NUMBER_CODE),
    duplicateNumbers: numberConflicts.size,
    types: count("TYPE_REQUIRES_MAPPING"),
    dimensions: count("DIMENSION_REQUIRES_MAPPING"),
    finishings: count("FINISHING_REQUIRES_MAPPING"),
    statuses: count("STATUS_REQUIRES_MAPPING"),
    skippedPhotos: rawCount(SKIPPED_PHOTO_CODE),
    existingMatches: rows.filter((row) => row.existing !== null).length,
    otherErrors: rows.reduce(
      (total, row) =>
        total +
        row.diagnostics.filter(
          (diagnostic) =>
            diagnostic.severity === "ERROR" &&
            !knownErrorCodes.has(diagnostic.code) &&
            rowDiagnosticNeedsManualResolution(
              row,
              rowDecisionFor(row),
              diagnostic.code,
              mappings,
              mappingDecisions,
              statusCandidates,
              statusMappingDecisions
            )
        ).length,
      0
    ),
  }
}

function planIssues(
  mappings: HtmlImportMapping[],
  mappingDecisions: Record<string, MappingDecision>,
  statusCandidates: HtmlImportStatusCandidate[],
  statusMappingDecisions: Record<string, StatusMappingDecision>,
  rows: HtmlImportRow[],
  rowDecisions: Record<string, RowDecision>,
  numberConflicts: ReadonlyMap<string, readonly string[]>,
  rowsComplete: boolean
): PlanIssue[] {
  const issues: PlanIssue[] = []

  mappings.forEach((mapping) => {
    const decision = mappingDecisions[mapping.id] ?? mappingDecisionFor(mapping)
    if (decision.action === "MAP" && !decision.targetId) {
      issues.push({
        id: `mapping-${mapping.id}`,
        message: `Для «${mapping.sourceLabel}» выберите цель сопоставления.`,
      })
    }
    if (decision.action === "CREATE" && !decision.stagedName.trim()) {
      issues.push({
        id: `staged-name-${mapping.id}`,
        message: `Для «${mapping.sourceLabel}» укажите имя создаваемого значения.`,
      })
    }
  })

  statusCandidates.forEach((candidate) => {
    const decision =
      statusMappingDecisions[candidate.id] ??
      statusMappingDecisionFor(candidate)
    if (candidate.required && decision.targetStatus === null) {
      issues.push({
        id: `status-${candidate.id}`,
        message: `Для статуса «${candidate.sourceLabel}» выберите разрешённую цель.`,
      })
    }
  })

  if (!rowsComplete) {
    issues.push({
      id: "rows-pending",
      message: "Загрузите все строки импорта перед сохранением плана.",
    })
  }

  rows.forEach((row) => {
    const decision = effectiveRowDecisionFor(
      row,
      rowDecisions[row.id],
      mappings,
      mappingDecisions,
      statusCandidates,
      statusMappingDecisions
    )
    if (decision.action === "REVIEW") {
      issues.push({
        id: `review-${row.id}`,
        message: `Для «${row.number}» выберите итоговое действие: добавить, объединить или не добавлять.`,
      })
      return
    }

    if (
      decision.action !== "EXCLUDE" &&
      hasRowDiagnostic(row, INVALID_NUMBER_CODE) &&
      htmlImportRentalNumberError(
        decision.proposedNumber ?? row.proposedNumber ?? ""
      ) !== null
    ) {
      issues.push({
        id: `invalid-number-${row.id}`,
        message: `У строки «${row.number}» некорректный номер. Измените номер или не добавляйте бытовку.`,
      })
    }

    if (decision.action === "EXCLUDE") return

    if (decision.action === "MERGE" && !decision.existingRentalItemId) {
      issues.push({
        id: `merge-target-${row.id}`,
        message: `Для «${row.number}» не выбрана существующая бытовка для объединения.`,
      })
    }
    if (
      decision.action === "MERGE" &&
      decision.existingRentalItemId &&
      decision.targetExpectedVersion === null
    ) {
      issues.push({
        id: `merge-version-${row.id}`,
        message: `Для «${row.number}» не удалось получить актуальную версию складской бытовки. Верните строку и выберите её заново.`,
      })
    }

    if (decision.action === "MERGE") {
      fieldConflicts(row).forEach((field) => {
        if (!decision.fieldDecisions[field]) {
          issues.push({
            id: `field-${row.id}-${field}`,
            message: `Для «${row.number}»: выберите источник поля «${cabinFieldLabel(field)}».`,
          })
        }
      })
    }

    CATALOG_CONFLICTS.forEach((conflict) => {
      if (
        hasRowDiagnostic(row, conflict.code) &&
        !catalogConflictResolved(
          row,
          decision,
          conflict,
          mappings,
          mappingDecisions
        )
      ) {
        issues.push({
          id: `${conflict.kind.toLowerCase()}-${row.id}`,
          message: `Для «${row.number}»: ${conflict.label.toLocaleLowerCase("ru-RU")}. Выберите значение или не добавляйте бытовку.`,
        })
      }
    })

    if (
      hasRowDiagnostic(row, "STATUS_REQUIRES_MAPPING") &&
      resolvedRowStatus(row, statusCandidates, statusMappingDecisions) === null
    ) {
      issues.push({
        id: `row-status-${row.id}`,
        message: `Для «${row.number}» не распознан статус. Сопоставьте его на предыдущем шаге.`,
      })
    }
  })

  numberConflicts.forEach((rowIds, numberKey) => {
    const conflictedRows = rowIds.flatMap((rowId) => {
      const row = rows.find((candidate) => candidate.id === rowId)
      if (!row) return []
      const decision = effectiveRowDecisionFor(
        row,
        rowDecisions[row.id],
        mappings,
        mappingDecisions,
        statusCandidates,
        statusMappingDecisions
      )
      return [
        {
          number: normalizeHtmlImportRentalNumber(
            decision.proposedNumber ?? row.proposedNumber ?? row.number
          ),
          rowNumber: row.number,
        },
      ]
    })
    const number = conflictedRows[0]?.number ?? numberKey
    const rowsLabel = conflictedRows
      .map((row) => `«${row.rowNumber}»`)
      .join(", ")
    issues.push({
      id: `duplicate-number-${numberKey}`,
      message: `Номер «${number}» выбран для нескольких новых бытовок${rowsLabel ? `: ${rowsLabel}` : ""}. Измените номер либо исключите или объедините одну строку.`,
    })
  })

  return issues
}

function summaryRows(
  rows: HtmlImportRow[],
  rowDecisions: Record<string, RowDecision>
) {
  return rows.reduce(
    (result, row) => {
      const action = rowDecisions[row.id]?.action ?? rowDecisionFor(row).action
      if (action === "CREATE") result.create += 1
      if (action === "MERGE") result.merge += 1
      if (action === "EXCLUDE") result.exclude += 1
      return result
    },
    { create: 0, merge: 0, exclude: 0 }
  )
}

function mappedTargetId(
  mappings: HtmlImportMapping[],
  decisions: Record<string, MappingDecision>,
  kind: string,
  sourceValue: string | null
) {
  if (!sourceValue) return null
  const mapping = mappings.find(
    (candidate) =>
      candidate.kind?.toUpperCase() === kind &&
      candidate.sourceValue.trim() === sourceValue.trim()
  )
  if (!mapping) return null
  const decision = decisions[mapping.id] ?? mappingDecisionFor(mapping)
  return decision.action === "MAP" ? decision.targetId : null
}

function sourceBoolean(cabin: HtmlImportCabin, aliases: string[]) {
  const value = cabinFieldValue(cabin, aliases)
  if (value === "true") return true
  if (value === "false") return false
  return null
}

function sourceCharacteristicValues(cabin: HtmlImportCabin) {
  const value = cabinFieldValue(cabin, ["characteristics", "characteristic"])
  return value
    ? value
        .split(",")
        .map((candidate) => candidate.trim())
        .filter(Boolean)
    : []
}

function isAcceptedHtmlFile(file: File) {
  return file.type === "text/html" || file.name.toLowerCase().endsWith(".html")
}

function MappingSource({
  mapping,
  selected,
  onSelect,
}: {
  mapping: HtmlImportMapping
  selected: boolean
  onSelect: (mapping: HtmlImportMapping) => void
}) {
  const { attributes, listeners, setNodeRef } = useDraggable({
    id: `html-import-source-${mapping.id}`,
    data: { mappingId: mapping.id },
  })

  return (
    <Button
      ref={setNodeRef}
      type="button"
      variant={selected ? "default" : "outline"}
      className="w-full justify-start"
      {...attributes}
      {...listeners}
      aria-pressed={selected}
      onClick={() => onSelect(mapping)}
    >
      {mapping.sourceLabel}
    </Button>
  )
}

function MappingTarget({
  target,
  selected,
  onSelect,
}: {
  target: { id: string; label: string }
  selected: boolean
  onSelect: (targetId: string) => void
}) {
  const { isOver, setNodeRef } = useDroppable({
    id: `html-import-target-${target.id}`,
    data: { targetId: target.id },
  })

  return (
    <Button
      ref={setNodeRef}
      type="button"
      variant={isOver || selected ? "default" : "outline"}
      className="w-full justify-start"
      aria-pressed={selected}
      onClick={() => onSelect(target.id)}
    >
      {target.label}
    </Button>
  )
}

function MappingPairingBoard({
  title,
  mappings,
  decisions,
  onDecisionChange,
}: {
  title: string
  mappings: HtmlImportMapping[]
  decisions: Record<string, MappingDecision>
  onDecisionChange: (mappingId: string, next: Partial<MappingDecision>) => void
}) {
  const [selectedMappingId, setSelectedMappingId] = useState<string | null>(
    null
  )
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  )
  const pendingMappings = useMemo(
    () =>
      mappings.filter((mapping) => {
        const decision =
          decisions[mapping.id] ?? mappingDecisionFor(mapping)
        return decision.action !== "MAP" || decision.targetId === null
      }),
    [decisions, mappings]
  )
  const targets = useMemo(() => {
    const unique = new Map<string, { id: string; label: string }>()
    pendingMappings.forEach((mapping) => {
      mapping.targets.forEach((target) => unique.set(target.id, target))
    })
    return Array.from(unique.values())
  }, [pendingMappings])

  function pair(mappingId: string, targetId: string) {
    onDecisionChange(mappingId, { action: "MAP", targetId })
    setSelectedMappingId(null)
  }

  function onDragEnd(event: DragEndEvent) {
    const mappingId = event.active.data.current?.mappingId
    const targetId = event.over?.data.current?.targetId
    if (typeof mappingId === "string" && typeof targetId === "string") {
      pair(mappingId, targetId)
    }
  }

  if (mappings.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          <CardDescription>
            Сервис не вернул значений, которым требуется сопоставление.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  if (pendingMappings.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          <CardDescription>
            Все {mappings.length} значений уже сопоставлены. Совпадающие пары
            не требуют ручного решения и скрыты.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>
          Ничего выбирать не обязательно: нераспознанные тип, размер и отделка
          получат «—», а необязательные значения будут пропущены. При желании
          сопоставьте значение или создайте новое. Показаны только{" "}
          {pendingMappings.length} значений без точного совпадения.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <DndContext sensors={sensors} onDragEnd={onDragEnd}>
          <div className="grid grid-cols-1 gap-3 xl:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]">
            <section
              className="flex flex-col gap-2"
              aria-label={`${title}: источник`}
            >
              <p className="text-sm font-medium">Из HTML</p>
              {pendingMappings.map((mapping) => (
                <MappingSource
                  key={mapping.id}
                  mapping={mapping}
                  selected={selectedMappingId === mapping.id}
                  onSelect={(selectedMapping) =>
                    setSelectedMappingId(selectedMapping.id)
                  }
                />
              ))}
            </section>

            <div
              className="hidden items-center gap-2 xl:flex"
              aria-hidden="true"
            >
              <Separator orientation="vertical" className="h-full" />
              <HugeiconsIcon icon={ArrowRight01Icon} />
              <Separator orientation="vertical" className="h-full" />
            </div>

            <section
              className="flex flex-col gap-2"
              aria-label={`${title}: цель`}
            >
              <p className="text-sm font-medium">В справочнике</p>
              {targets.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  Сервис не предложил существующих целей.
                </p>
              ) : (
                targets.map((target) => (
                  <MappingTarget
                    key={target.id}
                    target={target}
                  selected={pendingMappings.some(
                      (mapping) =>
                        (decisions[mapping.id] ?? mappingDecisionFor(mapping))
                          .targetId === target.id
                    )}
                    onSelect={(targetId) => {
                      if (selectedMappingId) pair(selectedMappingId, targetId)
                    }}
                  />
                ))
              )}
            </section>
          </div>
        </DndContext>

        <FieldGroup className="gap-4">
          {pendingMappings.map((mapping) => {
            const decision =
              decisions[mapping.id] ?? mappingDecisionFor(mapping)
            const mapInvalid = decision.action === "MAP" && !decision.targetId
            const stagedNameInvalid =
              decision.action === "CREATE" && !decision.stagedName.trim()
            return (
              <Field
                key={mapping.id}
                data-invalid={mapInvalid || stagedNameInvalid || undefined}
              >
                <FieldLabel>{mapping.sourceLabel}</FieldLabel>
                <div className="flex flex-wrap items-center gap-3">
                  <ToggleGroup
                    type="single"
                    value={decision.action}
                    onValueChange={(value) => {
                      if (!value) return
                      onDecisionChange(mapping.id, {
                        action: value as HtmlImportMappingAction,
                        targetId: value === "MAP" ? decision.targetId : null,
                      })
                    }}
                    spacing={2}
                    aria-label={`Действие для ${mapping.sourceLabel}`}
                  >
                    <ToggleGroupItem
                      value="MAP"
                      disabled={mapping.targets.length === 0}
                    >
                      Сопоставить
                    </ToggleGroupItem>
                    <ToggleGroupItem value="IGNORE">
                      {mapping.required ? "Оставить —" : "Пропустить"}
                    </ToggleGroupItem>
                    <ToggleGroupItem value="CREATE">Создать</ToggleGroupItem>
                  </ToggleGroup>

                  {decision.action === "MAP" ? (
                    <Select
                      value={decision.targetId ?? undefined}
                      onValueChange={(targetId) =>
                        onDecisionChange(mapping.id, {
                          action: "MAP",
                          targetId,
                        })
                      }
                    >
                      <SelectTrigger
                        aria-invalid={mapInvalid}
                        className="min-w-52"
                      >
                        <SelectValue placeholder="Выберите цель" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          {mapping.targets.map((target) => (
                            <SelectItem key={target.id} value={target.id}>
                              {target.label}
                            </SelectItem>
                          ))}
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  ) : null}
                  {decision.action === "CREATE" ? (
                    <Input
                      value={decision.stagedName}
                      onChange={(event) =>
                        onDecisionChange(mapping.id, {
                          stagedName: event.target.value,
                        })
                      }
                      aria-label={`Имя нового значения для ${mapping.sourceLabel}`}
                      aria-invalid={stagedNameInvalid}
                      className="min-w-52"
                    />
                  ) : null}
                </div>
                {mapInvalid ? (
                  <FieldError>Выберите цель или другое действие.</FieldError>
                ) : null}
                {stagedNameInvalid ? (
                  <FieldError>
                    Укажите непустое имя для нового значения.
                  </FieldError>
                ) : null}
              </Field>
            )
          })}
        </FieldGroup>
      </CardContent>
    </Card>
  )
}

function StatusMappingSource({
  candidate,
  selected,
  onSelect,
}: {
  candidate: HtmlImportStatusCandidate
  selected: boolean
  onSelect: (candidate: HtmlImportStatusCandidate) => void
}) {
  const { attributes, listeners, setNodeRef } = useDraggable({
    id: `html-import-status-source-${candidate.id}`,
    data: { statusCandidateId: candidate.id },
  })

  return (
    <Button
      ref={setNodeRef}
      type="button"
      variant={selected ? "default" : "outline"}
      className="w-full justify-start"
      {...attributes}
      {...listeners}
      aria-pressed={selected}
      onClick={() => onSelect(candidate)}
    >
      {candidate.sourceLabel}
    </Button>
  )
}

function StatusMappingTarget({
  target,
  selected,
  onSelect,
}: {
  target: { id: HtmlImportMappedStatus; label: string }
  selected: boolean
  onSelect: (targetStatus: HtmlImportMappedStatus) => void
}) {
  const { isOver, setNodeRef } = useDroppable({
    id: `html-import-status-target-${target.id}`,
    data: { targetStatus: target.id },
  })

  return (
    <Button
      ref={setNodeRef}
      type="button"
      variant={isOver || selected ? "default" : "outline"}
      className="w-full justify-start"
      aria-pressed={selected}
      onClick={() => onSelect(target.id)}
    >
      {target.label}
    </Button>
  )
}

function StatusMappingPairingBoard({
  candidates,
  decisions,
  onDecisionChange,
}: {
  candidates: HtmlImportStatusCandidate[]
  decisions: Record<string, StatusMappingDecision>
  onDecisionChange: (candidateId: string, next: StatusMappingDecision) => void
}) {
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | null>(
    null
  )
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  )
  const pendingCandidates = useMemo(
    () =>
      candidates.filter(
        (candidate) =>
          (decisions[candidate.id] ?? statusMappingDecisionFor(candidate))
            .targetStatus === null
      ),
    [candidates, decisions]
  )

  function pair(candidateId: string, targetStatus: HtmlImportMappedStatus) {
    onDecisionChange(candidateId, { targetStatus })
    setSelectedCandidateId(null)
  }

  function onDragEnd(event: DragEndEvent) {
    const candidateId = event.active.data.current?.statusCandidateId
    const targetStatus = event.over?.data.current?.targetStatus
    const mappedTargetStatus = htmlImportMappedStatus(
      typeof targetStatus === "string" ? targetStatus : null
    )
    if (typeof candidateId === "string" && mappedTargetStatus !== null) {
      pair(candidateId, mappedTargetStatus)
    }
  }

  if (pendingCandidates.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Статусы бытовок</CardTitle>
          <CardDescription>
            Все {candidates.length} статусов уже распознаны и сопоставлены.
            Совпадающие пары скрыты.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Статусы бытовок</CardTitle>
        <CardDescription>
          Показаны только {pendingCandidates.length} нераспознанных статусов.
          Перетащите статус из HTML на разрешённый статус системы или выберите
          обе стороны кнопками.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <DndContext sensors={sensors} onDragEnd={onDragEnd}>
          <div className="grid grid-cols-1 gap-3 xl:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]">
            <section
              className="flex flex-col gap-2"
              aria-label="Статусы бытовок: источник"
            >
              <p className="text-sm font-medium">Из HTML</p>
              {pendingCandidates.map((candidate) => (
                <StatusMappingSource
                  key={candidate.id}
                  candidate={candidate}
                  selected={selectedCandidateId === candidate.id}
                  onSelect={(selectedCandidate) =>
                    setSelectedCandidateId(selectedCandidate.id)
                  }
                />
              ))}
            </section>

            <div
              className="hidden items-center gap-2 xl:flex"
              aria-hidden="true"
            >
              <Separator orientation="vertical" className="h-full" />
              <HugeiconsIcon icon={ArrowRight01Icon} />
              <Separator orientation="vertical" className="h-full" />
            </div>

            <section
              className="flex flex-col gap-2"
              aria-label="Статусы бытовок: цель"
            >
              <p className="text-sm font-medium">В системе</p>
              {HTML_IMPORT_STATUS_TARGETS.map((target) => (
                <StatusMappingTarget
                  key={target.id}
                  target={target}
                  selected={pendingCandidates.some(
                    (candidate) =>
                      (
                        decisions[candidate.id] ??
                        statusMappingDecisionFor(candidate)
                      ).targetStatus === target.id
                  )}
                  onSelect={(targetStatus) => {
                    if (selectedCandidateId) {
                      pair(selectedCandidateId, targetStatus)
                    }
                  }}
                />
              ))}
            </section>
          </div>
        </DndContext>

        <FieldGroup className="gap-3">
          {pendingCandidates.map((candidate) => {
            const decision =
              decisions[candidate.id] ?? statusMappingDecisionFor(candidate)
            const invalid = candidate.required && decision.targetStatus === null
            const target = HTML_IMPORT_STATUS_TARGETS.find(
              (value) => value.id === decision.targetStatus
            )
            return (
              <Field key={candidate.id} data-invalid={invalid || undefined}>
                <FieldLabel>{candidate.sourceLabel}</FieldLabel>
                <FieldDescription>
                  {target
                    ? `Будет сопоставлен со статусом «${target.label}».`
                    : "Выберите разрешённый статус системы."}
                </FieldDescription>
                {invalid ? (
                  <FieldError>
                    Выберите статус для обязательного значения.
                  </FieldError>
                ) : null}
              </Field>
            )
          })}
        </FieldGroup>
      </CardContent>
    </Card>
  )
}

function RecognizedJson({
  label,
  value,
}: {
  label: string
  value: unknown
}) {
  return (
    <details className="rounded-md border p-3">
      <summary className="cursor-pointer text-sm font-medium">{label}</summary>
      <pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-3 text-xs">
        {JSON.stringify(value, null, 2)}
      </pre>
    </details>
  )
}

function SkippedHtmlRecord({ row }: { row: HtmlImportRow }) {
  const fields = Object.entries(row.source.fields)
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="destructive">Пропущена из HTML</Badge>
          {hasRowDiagnostic(row, INVALID_NUMBER_CODE) ? (
            <Badge variant="outline">Некорректный номер</Badge>
          ) : null}
        </div>
        <CardTitle>{row.number}</CardTitle>
        <CardDescription>Строка {row.sourceRowId}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted-foreground">Распознанный номер</dt>
            <dd>{row.proposedNumber ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Медиа</dt>
            <dd>{row.source.mediaCount ?? "—"}</dd>
          </div>
          {fields.map(([key, value]) => (
            <div key={key}>
              <dt className="text-muted-foreground">
                {cabinFieldLabel(key)}
              </dt>
              <dd className="break-words">{value ?? "—"}</dd>
            </div>
          ))}
        </dl>
        {row.diagnostics.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {row.diagnostics.map((diagnostic, index) => (
              <Badge
                key={`${diagnostic.code}-${diagnostic.field ?? "row"}-${index}`}
                variant={
                  diagnostic.severity === "ERROR"
                    ? "destructive"
                    : "outline"
                }
              >
                {diagnostic.code}
                {diagnostic.field ? ` · ${diagnostic.field}` : ""}
              </Badge>
            ))}
          </div>
        ) : null}
        <RecognizedJson
          label="Все распознанные данные (JSON)"
          value={{
            sourceRowId: row.sourceRowId,
            number: row.number,
            proposedNumber: row.proposedNumber,
            source: row.source,
            diagnostics: row.diagnostics,
          }}
        />
      </CardContent>
    </Card>
  )
}

function SkippedSystemRecord({ item }: { item: RentalItemDto }) {
  return (
    <Card>
      <CardHeader>
        <Badge variant="destructive">Пропущена в системе</Badge>
        <CardTitle>{item.number}</CardTitle>
        <CardDescription>
          Убрана только из кандидатов сопоставления; бытовка в базе не удалена.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
          {[
            ["UUID", item.id],
            ["Версия", String(item.version)],
            ["Тип", item.type],
            ["Размер", item.dimensions],
            ["Отделка", item.finishing],
            ["Категория", item.category],
            ["Статус", RENTAL_ITEM_STATUS_LABEL[item.status]],
            ["Комментарий", item.comment],
            ["Содержимое", item.contents],
          ].map(([label, value]) => (
            <div key={label}>
              <dt className="text-muted-foreground">{label}</dt>
              <dd className="break-words">{value || "—"}</dd>
            </div>
          ))}
        </dl>
        <RecognizedJson
          label="Все данные бытовки (JSON)"
          value={item}
        />
      </CardContent>
    </Card>
  )
}

function SkippedRecordsSummary({
  htmlRows,
  systemCabins,
}: {
  htmlRows: HtmlImportRow[]
  systemCabins: RentalItemDto[]
}) {
  if (htmlRows.length === 0 && systemCabins.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>Пропущенные записи</CardTitle>
        <CardDescription>
          HTML: {htmlRows.length}. Из списка складских кандидатов:{" "}
          {systemCabins.length}. Исходный HTML-код не хранится; ниже показан
          безопасный JSON-снимок всех распознанных данных.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {htmlRows.map((row) => (
          <SkippedHtmlRecord key={row.id} row={row} />
        ))}
        {systemCabins.map((item) => (
          <SkippedSystemRecord key={item.id} item={item} />
        ))}
      </CardContent>
    </Card>
  )
}

function StepHeader({ activeStep }: { activeStep: WizardStep }) {
  const steps: Array<{ id: WizardStep; label: string }> = [
    { id: "diagnostics", label: "HTML обработан" },
    { id: "mappings", label: "Справочники" },
    { id: "cabins", label: "Конфликты" },
    { id: "summary", label: "Итог" },
  ]

  return (
    <ol className="flex flex-wrap gap-2" aria-label="Шаги импорта">
      {steps.map((step) => (
        <li key={step.id}>
          <Badge variant={activeStep === step.id ? "default" : "outline"}>
            {step.label}
          </Badge>
        </li>
      ))}
    </ol>
  )
}

function WizardShell({
  children,
  footer,
  activeStep,
}: {
  children: ReactNode
  footer: ReactNode
  activeStep: WizardStep
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {activeStep === "source" ? null : (
        <div className="border-b px-5 py-3 sm:px-6">
          <StepHeader activeStep={activeStep} />
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
        {children}
      </div>
      <div className="border-t px-5 py-4 sm:px-6">{footer}</div>
    </div>
  )
}

export function HtmlImportWorkspace({
  open,
  warehouseId,
  warehouseName,
  onOpenChange,
}: {
  open: boolean
  warehouseId: string
  warehouseName: string
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const canExecuteImport = Boolean(
    currentUser && isGlobalAdministrator(currentUser.globalRole)
  )
  const fileInputRef = useRef<HTMLInputElement>(null)
  const sourceIdempotencyKeyRef = useRef<string | null>(null)
  const commitIdempotencyKeyRef = useRef<string | null>(null)
  const retryMediaIdempotencyKeyRef = useRef<string | null>(null)
  const replaceMediaIdempotencyKeyRef = useRef<string | null>(null)
  const skipMediaIdempotencyKeyRef = useRef<string | null>(null)
  const finalizedImportRef = useRef<string | null>(null)
  const [step, setStep] = useState<WizardStep>("source")
  const [html, setHtml] = useState("")
  const [sourceName, setSourceName] = useState<string | null>(null)
  const [sourceError, setSourceError] = useState<string | null>(null)
  const [activeImport, setActiveImport] = useState<HtmlImport | null>(null)
  const [mappingDecisions, setMappingDecisions] = useState<
    Record<string, MappingDecision>
  >({})
  const [statusMappingDecisions, setStatusMappingDecisions] = useState<
    Record<string, StatusMappingDecision>
  >({})
  const [rowDecisions, setRowDecisions] = useState<Record<string, RowDecision>>(
    {}
  )
  const [resolvedCabinRowIds, setResolvedCabinRowIds] = useState<string[]>([])
  const [skippedSystemCabinIds, setSkippedSystemCabinIds] = useState<string[]>(
    []
  )
  const [planDirty, setPlanDirty] = useState(false)
  const [cancelConfirmationOpen, setCancelConfirmationOpen] = useState(false)
  const [mediaReplacementLinks, setMediaReplacementLinks] = useState<
    Record<string, string>
  >({})

  const importsQuery = useQuery({
    queryKey: [
      ...htmlImportsQueryKey(warehouseId),
      currentUser?.id ?? "anonymous",
    ],
    queryFn: () => listHtmlImports(accessToken, warehouseId),
    enabled: open && Boolean(accessToken),
  })

  const resumableImport = useMemo(() => {
    if (!open || activeImport !== null || !importsQuery.data) return null

    return (
      [...importsQuery.data]
        .filter((candidate) => !TERMINAL_IMPORT_STATES.has(candidate.state))
        .sort((left, right) => {
          const leftTime = left.updatedAt ? Date.parse(left.updatedAt) : 0
          const rightTime = right.updatedAt ? Date.parse(right.updatedAt) : 0
          return rightTime - leftTime
        })[0] ?? null
    )
  }, [activeImport, importsQuery.data, open])

  const selectedImport = activeImport ?? resumableImport

  const importQuery = useQuery({
    queryKey: ["html-import", selectedImport?.id ?? "none"],
    queryFn: () => getHtmlImport(accessToken, selectedImport!.id),
    enabled: open && selectedImport !== null && Boolean(accessToken),
    refetchInterval: (query) => {
      const imported = query.state.data
      return imported && isImportInProgress(imported) ? 2_000 : false
    },
  })

  const currentImport = importQuery.data ?? selectedImport
  const activeStep =
    currentImport !== null && SUMMARY_IMPORT_STATES.has(currentImport.state)
      ? "summary"
      : currentImport !== null && activeImport === null && step === "source"
        ? importStep(currentImport)
        : step
  const shouldLoadRows =
    open &&
    currentImport !== null &&
    Boolean(accessToken) &&
    (activeStep === "diagnostics" ||
      activeStep === "cabins" ||
      activeStep === "summary")
  const rowsQuery = useInfiniteQuery({
    queryKey: ["html-import-rows", currentImport?.id ?? "none"],
    queryFn: ({ pageParam }) =>
      listHtmlImportRows({
        accessToken,
        importId: currentImport!.id,
        page: pageParam,
        size: 200,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    enabled: shouldLoadRows,
  })
  const rows = useMemo(
    () => rowsQuery.data?.pages.flatMap((page) => page.content) ?? [],
    [rowsQuery.data]
  )
  const mediaLinkedRows = useMemo(
    () =>
      rows.filter(
        (row) =>
          row.plannedAction !== "EXCLUDE" &&
          (row.source.mediaCount ?? 0) > 0
      ),
    [rows]
  )
  const mediaReplacementItems = useMemo(
    () =>
      mediaLinkedRows.flatMap((row) => {
        const publicUrl = mediaReplacementLinks[row.id]?.trim() ?? ""
        return publicUrl ? [{ rowId: row.id, publicUrl }] : []
      }),
    [mediaLinkedRows, mediaReplacementLinks]
  )
  const invalidMediaReplacementRowIds = useMemo(
    () =>
      new Set(
        mediaReplacementItems
          .filter((replacement) => !isYandexDiskPublicUrl(replacement.publicUrl))
          .map((replacement) => replacement.rowId)
      ),
    [mediaReplacementItems]
  )
  const yandexMediaRecoveryAvailable =
    currentImport?.state === "FAILED" &&
    currentImport.failureCode === YANDEX_RESOURCE_REJECTED_CODE &&
    currentImport.mediaJobId !== null
  const effectiveRowDecisions = useMemo(
    () =>
      Object.fromEntries(
        rows.map((row) => [
          row.id,
          effectiveRowDecisionFor(
            row,
            rowDecisions[row.id],
            currentImport?.catalogMappings ?? [],
            mappingDecisions,
            currentImport?.statusCandidates ?? [],
            statusMappingDecisions
          ),
        ])
      ) as Record<string, RowDecision>,
    [
      currentImport?.catalogMappings,
      currentImport?.statusCandidates,
      mappingDecisions,
      rowDecisions,
      rows,
      statusMappingDecisions,
    ]
  )
  const numberConflicts = useMemo(
    () =>
      findHtmlImportRentalNumberConflicts(
        rows.map((row) => {
          const decision = effectiveRowDecisions[row.id] ?? rowDecisionFor(row)
          return {
            id: row.id,
            action: decision.action,
            number: row.number,
            proposedNumber: decision.proposedNumber ?? row.proposedNumber,
          }
        })
      ),
    [effectiveRowDecisions, rows]
  )
  const duplicateNumberRowIds = useMemo(
    () =>
      new Set(
        Array.from(numberConflicts.values()).flatMap((rowIds) => rowIds)
      ),
    [numberConflicts]
  )
  const manualConflictRows = useMemo(
    () =>
      rows.filter(
        (row) =>
          duplicateNumberRowIds.has(row.id) ||
          rowNeedsManualResolution(
            row,
            currentImport?.catalogMappings ?? [],
            mappingDecisions,
            currentImport?.statusCandidates ?? [],
            statusMappingDecisions
          )
      ),
    [
      currentImport?.catalogMappings,
      currentImport?.statusCandidates,
      duplicateNumberRowIds,
      mappingDecisions,
      rows,
      statusMappingDecisions,
    ]
  )
  const conflicts = useMemo(
    () =>
      conflictSummary(
        rows,
        currentImport?.catalogMappings ?? [],
        mappingDecisions,
        currentImport?.statusCandidates ?? [],
        statusMappingDecisions,
        manualConflictRows,
        numberConflicts
      ),
    [
      currentImport?.catalogMappings,
      currentImport?.statusCandidates,
      manualConflictRows,
      mappingDecisions,
      numberConflicts,
      rows,
      statusMappingDecisions,
    ]
  )
  const rowsComplete =
    shouldLoadRows &&
    !rowsQuery.isLoading &&
    !rowsQuery.isError &&
    !rowsQuery.hasNextPage &&
    !rowsQuery.isFetchingNextPage

  const {
    fetchNextPage,
    hasNextPage: hasNextRowsPage,
    isFetchingNextPage: isFetchingNextRowsPage,
  } = rowsQuery

  useEffect(() => {
    if (
      !shouldLoadRows ||
      !hasNextRowsPage ||
      isFetchingNextRowsPage
    ) {
      return
    }

    void fetchNextPage()
  }, [
    fetchNextPage,
    hasNextRowsPage,
    isFetchingNextRowsPage,
    shouldLoadRows,
  ])

  const systemCabinsQuery = useInfiniteQuery({
    queryKey: ["html-import-system-cabins", warehouseId],
    queryFn: ({ pageParam }) =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: pageParam,
        size: 200,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    enabled:
      open &&
      Boolean(accessToken) &&
      (activeStep === "cabins" ||
        (activeStep === "summary" && skippedSystemCabinIds.length > 0)),
  })
  const systemCabins = useMemo(
    () =>
      systemCabinsQuery.data?.pages.flatMap((page) => page.content) ?? [],
    [systemCabinsQuery.data]
  )
  const {
    fetchNextPage: fetchNextSystemCabinsPage,
    hasNextPage: hasNextSystemCabinsPage,
    isFetchingNextPage: isFetchingNextSystemCabinsPage,
  } = systemCabinsQuery

  useEffect(() => {
    if (
      activeStep !== "cabins" ||
      !hasNextSystemCabinsPage ||
      isFetchingNextSystemCabinsPage
    ) {
      return
    }
    void fetchNextSystemCabinsPage()
  }, [
    activeStep,
    fetchNextSystemCabinsPage,
    hasNextSystemCabinsPage,
    isFetchingNextSystemCabinsPage,
  ])

  const resolvedCabinRows = useMemo<ResolvedHtmlCabinRow[]>(
    () =>
      resolvedCabinRowIds.flatMap((rowId) => {
        const row = rows.find((candidate) => candidate.id === rowId)
        if (!row) return []
        const decision =
          effectiveRowDecisions[row.id] ?? rowDecisionFor(row)
        if (decision.action === "REVIEW") return []
        const target =
          decision.action === "MERGE"
            ? (systemCabins.find(
                (candidate) =>
                  candidate.id === decision.existingRentalItemId
              ) ?? null)
            : null
        return [
          {
            row,
            action: decision.action,
            target,
            proposedNumber: decision.proposedNumber ?? row.proposedNumber,
          },
        ]
      }),
    [effectiveRowDecisions, resolvedCabinRowIds, rows, systemCabins]
  )
  const skippedSystemCabins = useMemo(
    () =>
      skippedSystemCabinIds.flatMap((itemId) => {
        const item = systemCabins.find((candidate) => candidate.id === itemId)
        return item ? [item] : []
      }),
    [skippedSystemCabinIds, systemCabins]
  )

  const createMutation = useMutation({
    mutationFn: () => {
      if (sourceIdempotencyKeyRef.current === null) {
        sourceIdempotencyKeyRef.current = createIdempotencyKey()
      }
      return createHtmlImport({
        accessToken,
        warehouseId,
        html: new Blob([html], { type: "text/html" }),
        idempotencyKey: sourceIdempotencyKeyRef.current,
      })
    },
    onSuccess: (importRecord) => {
      setActiveImport(importRecord)
      setStep(importStep(importRecord))
      setSourceError(null)
      queryClient.setQueryData(["html-import", importRecord.id], importRecord)
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () => {
      if (currentImport === null) throw new Error("Импорт не выбран.")
      return cancelHtmlImport({
        accessToken,
        importId: currentImport.id,
        expectedVersion: currentImport.version,
      })
    },
    onSuccess: () => {
      if (currentImport === null) return
      const cancelledImportId = currentImport.id
      queryClient.setQueriesData<HtmlImport[]>(
        { queryKey: htmlImportsQueryKey(warehouseId) },
        (imports) =>
          imports?.filter((candidate) => candidate.id !== cancelledImportId)
      )
      queryClient.removeQueries({
        queryKey: ["html-import", cancelledImportId],
        exact: true,
      })
      queryClient.removeQueries({
        queryKey: ["html-import-rows", cancelledImportId],
        exact: true,
      })
      setCancelConfirmationOpen(false)
      resetWorkspace()
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
    },
  })

  const savePlanMutation = useMutation({
    mutationFn: () => {
      if (currentImport === null) throw new Error("Импорт не выбран.")
      return saveHtmlImportPlan({
        accessToken,
        importId: currentImport.id,
        expectedVersion: currentImport.version,
        catalogMappings: currentImport.catalogMappings.map((mapping) => {
          const decision =
            mappingDecisions[mapping.id] ?? mappingDecisionFor(mapping)
          return {
            ...(mapping.kind ? { kind: mapping.kind } : {}),
            sourceValue: mapping.sourceValue,
            action: decision.action,
            ...(decision.action === "MAP" && decision.targetId
              ? { targetId: decision.targetId }
              : {}),
            ...(decision.action === "CREATE"
              ? { stagedName: decision.stagedName.trim() }
              : {}),
          }
        }),
        equipmentMappings: currentImport.equipmentMappings.map((mapping) => {
          const decision =
            mappingDecisions[mapping.id] ?? mappingDecisionFor(mapping)
          return {
            sourceValue: mapping.sourceValue,
            action: decision.action,
            ...(decision.action === "MAP" && decision.targetId
              ? { targetId: decision.targetId }
              : {}),
            ...(decision.action === "CREATE"
              ? { stagedName: decision.stagedName.trim() }
              : {}),
          }
        }),
        statusMappings: currentImport.statusCandidates.flatMap((candidate) => {
          const targetStatus = (
            statusMappingDecisions[candidate.id] ??
            statusMappingDecisionFor(candidate)
          ).targetStatus
          return targetStatus === null
            ? []
            : [{ sourceValue: candidate.sourceValue, targetStatus }]
        }),
        rows: rows.map((row) => {
          const decision =
            effectiveRowDecisions[row.id] ?? rowDecisionFor(row)
          const rentalType = cabinFieldValue(row.source, ["rentaltype", "type"])
          const dimension = cabinFieldValue(row.source, [
            "dimension",
            "dimensions",
          ])
          const finishing = cabinFieldValue(row.source, ["finishing", "finish"])
          const category = cabinFieldValue(row.source, ["category"])
          const characteristicIds = sourceCharacteristicValues(row.source)
            .map((sourceValue) =>
              mappedTargetId(
                currentImport.catalogMappings,
                mappingDecisions,
                "CHARACTERISTIC",
                sourceValue
              )
            )
            .filter((id): id is string => id !== null)
          const linoleum = sourceBoolean(row.source, ["linoleum"])
          const comment = cabinFieldValue(row.source, [
            "comment",
            "generalcomment",
          ])
          const status = resolvedRowStatus(
            row,
            currentImport.statusCandidates,
            statusMappingDecisions
          )
          const rentalTypeId =
            decision.rentalTypeId ??
            mappedTargetId(
              currentImport.catalogMappings,
              mappingDecisions,
              "TYPE",
              rentalType
            )
          const dimensionId =
            decision.dimensionId ??
            mappedTargetId(
              currentImport.catalogMappings,
              mappingDecisions,
              "DIMENSION",
              dimension
            )
          const finishingId =
            decision.finishingId ??
            mappedTargetId(
              currentImport.catalogMappings,
              mappingDecisions,
              "FINISHING",
              finishing
            )
          const categoryId = mappedTargetId(
            currentImport.catalogMappings,
            mappingDecisions,
            "CATEGORY",
            category
          )
          return {
            sourceRowId: row.sourceRowId,
            action: decision.action,
            ...(decision.proposedNumber
              ? { proposedNumber: decision.proposedNumber }
              : {}),
            ...(decision.action === "MERGE" && decision.existingRentalItemId
              ? { targetRentalItemId: decision.existingRentalItemId }
              : {}),
            ...(decision.action === "MERGE" &&
            decision.targetExpectedVersion !== null
              ? { targetExpectedVersion: decision.targetExpectedVersion }
              : {}),
            ...(rentalTypeId ? { rentalTypeId } : {}),
            ...(dimensionId ? { dimensionId } : {}),
            ...(finishingId ? { finishingId } : {}),
            ...(categoryId ? { categoryId } : {}),
            ...(characteristicIds.length ? { characteristicIds } : {}),
            ...(status ? { status } : {}),
            ...(linoleum !== null ? { linoleum } : {}),
            ...(comment !== null ? { comment } : {}),
            ...(decision.action === "MERGE"
              ? { mergeChoices: decision.fieldDecisions }
              : {}),
          }
        }),
      })
    },
    onSuccess: (importRecord) => {
      setActiveImport(importRecord)
      setPlanDirty(false)
      queryClient.setQueryData(["html-import", importRecord.id], importRecord)
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
    },
  })

  const commitMutation = useMutation({
    mutationFn: () => {
      if (currentImport === null) throw new Error("Импорт не выбран.")
      if (commitIdempotencyKeyRef.current === null) {
        commitIdempotencyKeyRef.current = createIdempotencyKey()
      }
      return commitHtmlImport({
        accessToken,
        importId: currentImport.id,
        expectedVersion: currentImport.version,
        idempotencyKey: commitIdempotencyKeyRef.current,
      })
    },
    onSuccess: (importRecord) => {
      setActiveImport(importRecord)
      queryClient.setQueryData(["html-import", importRecord.id], importRecord)
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
      if (TERMINAL_IMPORT_STATES.has(importRecord.state)) {
        void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      }
    },
  })

  const retryMediaMutation = useMutation({
    mutationFn: () => {
      if (currentImport === null) throw new Error("Импорт не выбран.")
      if (retryMediaIdempotencyKeyRef.current === null) {
        retryMediaIdempotencyKeyRef.current = createIdempotencyKey()
      }
      return retryHtmlImportMedia({
        accessToken,
        importId: currentImport.id,
        expectedVersion: currentImport.version,
        idempotencyKey: retryMediaIdempotencyKeyRef.current,
      })
    },
    onSuccess: (importRecord) => {
      setActiveImport(importRecord)
      retryMediaIdempotencyKeyRef.current = null
      queryClient.setQueryData(["html-import", importRecord.id], importRecord)
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
    },
  })

  const replaceMediaMutation = useMutation({
    mutationFn: () => {
      if (currentImport === null) throw new Error("Импорт не выбран.")
      if (mediaReplacementItems.length === 0) {
        throw new Error("Укажите хотя бы одну новую ссылку Яндекс.Диска.")
      }
      if (invalidMediaReplacementRowIds.size > 0) {
        throw new Error("Исправьте формат новых ссылок Яндекс.Диска.")
      }
      if (replaceMediaIdempotencyKeyRef.current === null) {
        replaceMediaIdempotencyKeyRef.current = createIdempotencyKey()
      }
      return replaceHtmlImportMedia({
        accessToken,
        importId: currentImport.id,
        expectedVersion: currentImport.version,
        idempotencyKey: replaceMediaIdempotencyKeyRef.current,
        replacements: mediaReplacementItems,
      })
    },
    onSuccess: (importRecord) => {
      setActiveImport(importRecord)
      setMediaReplacementLinks({})
      replaceMediaIdempotencyKeyRef.current = null
      queryClient.setQueryData(["html-import", importRecord.id], importRecord)
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
    },
  })

  const skipMediaMutation = useMutation({
    mutationFn: () => {
      if (currentImport === null) throw new Error("Импорт не выбран.")
      if (skipMediaIdempotencyKeyRef.current === null) {
        skipMediaIdempotencyKeyRef.current = createIdempotencyKey()
      }
      return skipHtmlImportMedia({
        accessToken,
        importId: currentImport.id,
        expectedVersion: currentImport.version,
        idempotencyKey: skipMediaIdempotencyKeyRef.current,
      })
    },
    onSuccess: (importRecord) => {
      setActiveImport(importRecord)
      queryClient.setQueryData(["html-import", importRecord.id], importRecord)
      void queryClient.invalidateQueries({
        queryKey: htmlImportsQueryKey(warehouseId),
      })
    },
  })

  useEffect(() => {
    if (
      currentImport === null ||
      !TERMINAL_IMPORT_STATES.has(currentImport.state)
    ) {
      return
    }

    const finalizedImport = `${currentImport.id}:${currentImport.version}`
    if (finalizedImportRef.current === finalizedImport) return

    finalizedImportRef.current = finalizedImport
    void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
    void queryClient.invalidateQueries({
      queryKey: htmlImportsQueryKey(warehouseId),
    })
  }, [currentImport, queryClient, warehouseId])

  const allMappings = currentImport
    ? [...currentImport.catalogMappings, ...currentImport.equipmentMappings]
    : []
  const planSaved = currentImport?.state === "READY" && !planDirty
  const issues = planIssues(
    allMappings,
    mappingDecisions,
    currentImport?.statusCandidates ?? [],
    statusMappingDecisions,
    rows,
    rowDecisions,
    numberConflicts,
    rowsComplete
  )
  const isPending =
    createMutation.isPending ||
    cancelMutation.isPending ||
    savePlanMutation.isPending ||
    commitMutation.isPending ||
    retryMediaMutation.isPending ||
    replaceMediaMutation.isPending ||
    skipMediaMutation.isPending
  const canCancelCurrentImport =
    currentImport !== null &&
    CANCELLABLE_IMPORT_STATES.has(currentImport.state)

  function resetWorkspace() {
    sourceIdempotencyKeyRef.current = null
    commitIdempotencyKeyRef.current = null
    retryMediaIdempotencyKeyRef.current = null
    replaceMediaIdempotencyKeyRef.current = null
    skipMediaIdempotencyKeyRef.current = null
    finalizedImportRef.current = null
    setStep("source")
    setHtml("")
    setSourceName(null)
    setSourceError(null)
    setActiveImport(null)
    setMappingDecisions({})
    setStatusMappingDecisions({})
    setRowDecisions({})
    setResolvedCabinRowIds([])
    setSkippedSystemCabinIds([])
    setPlanDirty(false)
    setCancelConfirmationOpen(false)
    setMediaReplacementLinks({})
  }

  function changeOpen(nextOpen: boolean) {
    if (!nextOpen && isPending) return
    if (!nextOpen) resetWorkspace()
    onOpenChange(nextOpen)
  }

  function updateSourceHtml(
    nextHtml: string,
    nextName: string | null = sourceName
  ) {
    sourceIdempotencyKeyRef.current = null
    setHtml(nextHtml)
    setSourceName(nextName)
    setSourceError(null)
  }

  function updateMediaReplacementLink(rowId: string, publicUrl: string) {
    replaceMediaIdempotencyKeyRef.current = null
    setMediaReplacementLinks((current) => ({
      ...current,
      [rowId]: publicUrl,
    }))
  }

  async function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0]
    event.currentTarget.value = ""
    if (!file) return
    if (!isAcceptedHtmlFile(file)) {
      setSourceError("Выберите HTML-файл с расширением .html.")
      return
    }

    try {
      updateSourceHtml(await file.text(), file.name)
    } catch {
      setSourceError("Не удалось прочитать выбранный HTML-файл.")
    }
  }

  function submitSource() {
    if (!html.trim()) {
      setSourceError("Вставьте HTML или добавьте файл перед продолжением.")
      return
    }
    createMutation.mutate()
  }

  function updateMappingDecision(
    mappingId: string,
    next: Partial<MappingDecision>
  ) {
    setPlanDirty(true)
    commitIdempotencyKeyRef.current = null
    const mapping = allMappings.find((candidate) => candidate.id === mappingId)
    setMappingDecisions((current) => ({
      ...current,
      [mappingId]: {
        ...(current[mappingId] ??
          (mapping
            ? mappingDecisionFor(mapping)
            : { action: "IGNORE", targetId: null, stagedName: "" })),
        ...next,
      },
    }))
  }

  function updateStatusMappingDecision(
    candidateId: string,
    next: StatusMappingDecision
  ) {
    setPlanDirty(true)
    commitIdempotencyKeyRef.current = null
    setStatusMappingDecisions((current) => ({
      ...current,
      [candidateId]: next,
    }))
  }

  function updateRowDecision(rowId: string, next: Partial<RowDecision>) {
    setPlanDirty(true)
    commitIdempotencyKeyRef.current = null
    const row = rows.find((candidate) => candidate.id === rowId)
    setRowDecisions((current) => ({
      ...current,
      [rowId]: {
        ...(current[rowId] ??
          (row
            ? (effectiveRowDecisions[rowId] ?? rowDecisionFor(row))
            : {
                action: "CREATE",
                proposedNumber: null,
                existingRentalItemId: null,
                targetExpectedVersion: null,
                rentalTypeId: null,
                dimensionId: null,
                finishingId: null,
                fieldDecisions: {},
              })),
        ...next,
      },
    }))
  }

  function markCabinRowResolved(rowId: string) {
    setResolvedCabinRowIds((current) =>
      current.includes(rowId) ? current : [...current, rowId]
    )
  }

  function pairCabinRow(row: HtmlImportRow, target: RentalItemDto) {
    updateRowDecision(row.id, {
      action: "MERGE",
      existingRentalItemId: target.id,
      targetExpectedVersion: target.version,
      fieldDecisions: htmlWinsMergeChoices(row),
    })
    setSkippedSystemCabinIds((current) =>
      current.filter((itemId) => itemId !== target.id)
    )
    markCabinRowResolved(row.id)
  }

  function createCabinRow(row: HtmlImportRow, proposedNumber: string) {
    updateRowDecision(row.id, {
      action: "CREATE",
      proposedNumber,
      existingRentalItemId: null,
      targetExpectedVersion: null,
      fieldDecisions: {},
    })
    markCabinRowResolved(row.id)
  }

  function skipHtmlCabinRow(row: HtmlImportRow) {
    updateRowDecision(row.id, {
      action: "EXCLUDE",
      existingRentalItemId: null,
      targetExpectedVersion: null,
      fieldDecisions: {},
    })
    markCabinRowResolved(row.id)
  }

  function skipSystemCabin(target: RentalItemDto) {
    setSkippedSystemCabinIds((current) =>
      current.includes(target.id) ? current : [...current, target.id]
    )
  }

  function restoreHtmlCabinRow(row: HtmlImportRow) {
    setPlanDirty(true)
    commitIdempotencyKeyRef.current = null
    setResolvedCabinRowIds((current) =>
      current.filter((rowId) => rowId !== row.id)
    )
    setRowDecisions((current) => {
      const next = { ...current }
      delete next[row.id]
      return next
    })
  }

  function restoreSystemCabin(target: RentalItemDto) {
    setSkippedSystemCabinIds((current) =>
      current.filter((itemId) => itemId !== target.id)
    )
  }

  const sourceFooter = (
    <DialogFooter>
      <Button
        type="button"
        disabled={createMutation.isPending}
        onClick={submitSource}
      >
        Далее
        <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-end" />
      </Button>
    </DialogFooter>
  )

  let content: ReactNode
  let footer: ReactNode

  if (activeStep === "source") {
    content = (
      <FieldGroup className="gap-5">
        <p className="text-sm text-muted-foreground">
          Склад назначения: {warehouseName}
        </p>
        <Field data-invalid={Boolean(sourceError) || undefined}>
          <FieldLabel htmlFor="html-import-source">HTML для импорта</FieldLabel>
          <Textarea
            id="html-import-source"
            value={html}
            onChange={(event) => updateSourceHtml(event.target.value)}
            placeholder="Вставьте HTML из старой панели"
            aria-invalid={Boolean(sourceError)}
            className="min-h-80 resize-y font-mono"
          />
          <FieldDescription>
            {sourceName
              ? `Загружен файл: ${sourceName}`
              : "Данные сохраняются в сервисе только после нажатия «Далее»."}
          </FieldDescription>
          {sourceError ? <FieldError>{sourceError}</FieldError> : null}
          {createMutation.error ? (
            <FieldError role="alert">
              {commandError(createMutation.error)}
            </FieldError>
          ) : null}
        </Field>
        <Field>
          <FieldLabel htmlFor="html-import-file" className="sr-only">
            Добавить HTML-файл
          </FieldLabel>
          <Input
            ref={fileInputRef}
            id="html-import-file"
            type="file"
            accept={HTML_FILE_ACCEPT}
            className="sr-only"
            onChange={chooseFile}
          />
        </Field>
      </FieldGroup>
    )
    footer = sourceFooter
  } else if (currentImport === null) {
    content = <p className="text-sm text-muted-foreground">Загрузка импорта…</p>
    footer = null
  } else if (activeStep === "diagnostics") {
    content = (
      <div className="flex flex-col gap-4">
        <Card>
          <CardHeader>
            <CardTitle>HTML обработан</CardTitle>
            <CardDescription>
              {rowsComplete
                ? `Найдено строк с конфликтами: ${conflicts.rows}. Посмотрите разбивку и перейдите к ручному разрешению.`
                : "Собираем все строки, чтобы показать точное количество конфликтов."}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={importStateBadgeVariant(currentImport.state)}>
                {importStateTitle(currentImport.state)}
              </Badge>
              <span className="text-sm text-muted-foreground">
                Версия {currentImport.version}
              </span>
            </div>
            {rowsQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">
                Считаем конфликты по строкам импорта…
              </p>
            ) : null}
            {rowsQuery.isFetchingNextPage ? (
              <p className="text-sm text-muted-foreground">
                Загружено строк: {rows.length}
                {rowsQuery.data?.pages[0]?.totalElements
                  ? ` из ${rowsQuery.data.pages[0].totalElements}`
                  : ""}
                …
              </p>
            ) : null}
            {rowsQuery.error ? (
              <FieldError role="alert">
                {commandError(rowsQuery.error)}
              </FieldError>
            ) : null}
          </CardContent>
        </Card>

        {!rowsQuery.error ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {[
              ["Некорректные номера", conflicts.invalidNumbers],
              ["Дубликаты номеров в импорте", conflicts.duplicateNumbers],
              ["Тип не распознан", conflicts.types],
              ["Размер не распознан", conflicts.dimensions],
              ["Отделка не распознана", conflicts.finishings],
              ["Статус не распознан", conflicts.statuses],
              ["Совпадения с бытовкой", conflicts.existingMatches],
              ["Другие конфликты", conflicts.otherErrors],
            ].map(([label, count]) => (
              <Card key={label}>
                <CardContent className="flex items-center justify-between gap-3 py-4">
                  <span className="text-sm text-muted-foreground">{label}</span>
                  <span className="text-2xl font-semibold tabular-nums">
                    {count}
                  </span>
                </CardContent>
              </Card>
            ))}
          </div>
        ) : null}

        {conflicts.invalidNumbers > 0 ? (
          <Card>
            <CardHeader>
              <CardTitle>Некорректные номера — отдельно</CardTitle>
              <CardDescription>
                В этом сценарии номер не исправляется. Для таких строк можно
                выбрать «Не добавлять» или оставить их на проверку.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-wrap gap-2">
              {manualConflictRows
                .filter((row) => hasRowDiagnostic(row, INVALID_NUMBER_CODE))
                .slice(0, 8)
                .map((row) => (
                  <Badge key={row.id} variant="outline">
                    {row.number}
                  </Badge>
                ))}
              {conflicts.invalidNumbers > 8 ? (
                <Badge variant="outline">
                  ещё {conflicts.invalidNumbers - 8}
                </Badge>
              ) : null}
            </CardContent>
          </Card>
        ) : null}

        {conflicts.duplicateNumbers > 0 ? (
          <Card>
            <CardHeader>
              <CardTitle>Повторяющиеся номера — требуется решение</CardTitle>
              <CardDescription>
                Один номер нельзя создать для нескольких бытовок. На шаге
                «Конфликты» измените номер одной строки, объедините её с
                бытовкой склада или исключите.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              {Array.from(numberConflicts.entries()).map(
                ([numberKey, rowIds]) => {
                  const conflictRows = rowIds.flatMap((rowId) => {
                    const row = rows.find((candidate) => candidate.id === rowId)
                    if (!row) return []
                    const decision =
                      effectiveRowDecisions[row.id] ?? rowDecisionFor(row)
                    return [
                      {
                        number: normalizeHtmlImportRentalNumber(
                          decision.proposedNumber ??
                            row.proposedNumber ??
                            row.number
                        ),
                        rowNumber: row.number,
                      },
                    ]
                  })
                  const number = conflictRows[0]?.number ?? numberKey
                  return (
                    <div
                      key={numberKey}
                      className="flex flex-wrap items-center gap-2 text-sm"
                    >
                      <Badge variant="destructive">{number}</Badge>
                      <span className="text-muted-foreground">
                        {conflictRows
                          .map((row) => `«${row.rowNumber}»`)
                          .join(", ")}
                      </span>
                    </div>
                  )
                }
              )}
            </CardContent>
          </Card>
        ) : null}

        {conflicts.skippedPhotos > 0 ? (
          <Alert>
            <HugeiconsIcon icon={InformationCircleIcon} />
            <AlertTitle>
              Битые ссылки на Яндекс‑фото пропущены: {conflicts.skippedPhotos}
            </AlertTitle>
            <AlertDescription>
              Эти ссылки не попадут в медиаимпорт и не блокируют перенос
              бытовок.
            </AlertDescription>
          </Alert>
        ) : null}
      </div>
    )
    footer = (
      <DialogFooter>
        {canCancelCurrentImport ? (
          <Button
            type="button"
            variant="destructive"
            disabled={isPending}
            onClick={() => setCancelConfirmationOpen(true)}
          >
            <HugeiconsIcon icon={Delete02Icon} data-icon="inline-start" />
            Удалить импорт
          </Button>
        ) : null}
        <Button
          type="button"
          disabled={!rowsComplete}
          onClick={() => setStep("mappings")}
        >
          {!rowsComplete ? (
            <HugeiconsIcon icon={Loading03Icon} data-icon="inline-start" />
          ) : null}
          Ручное разрешение конфликтов
          <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-end" />
        </Button>
      </DialogFooter>
    )
  } else if (activeStep === "mappings") {
    content = (
      <div className="flex flex-col gap-4">
        <MappingPairingBoard
          title="Справочник бытовок"
          mappings={currentImport.catalogMappings}
          decisions={mappingDecisions}
          onDecisionChange={updateMappingDecision}
        />
        <MappingPairingBoard
          title="Дополнительное оборудование"
          mappings={currentImport.equipmentMappings}
          decisions={mappingDecisions}
          onDecisionChange={updateMappingDecision}
        />
        {currentImport.statusCandidates.length > 0 ? (
          <StatusMappingPairingBoard
            candidates={currentImport.statusCandidates}
            decisions={statusMappingDecisions}
            onDecisionChange={updateStatusMappingDecision}
          />
        ) : null}
      </div>
    )
    footer = (
      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          onClick={() => setStep("diagnostics")}
        >
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
        <Button type="button" onClick={() => setStep("cabins")}>
          К конфликтам по бытовкам
          <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-end" />
        </Button>
      </DialogFooter>
    )
  } else if (activeStep === "cabins") {
    content = (
      <div className="flex flex-col gap-4">
        {rowsQuery.error ? (
          <FieldError role="alert">{commandError(rowsQuery.error)}</FieldError>
        ) : null}
        <HtmlImportCabinPairingBoard
          rows={manualConflictRows}
          duplicateNumberConflicts={numberConflicts}
          systemCabins={systemCabins}
          resolvedRows={resolvedCabinRows}
          skippedSystemCabins={skippedSystemCabins}
          systemLoading={
            systemCabinsQuery.isLoading ||
            systemCabinsQuery.isFetchingNextPage
          }
          systemError={
            systemCabinsQuery.error
              ? commandError(systemCabinsQuery.error)
              : null
          }
          onPair={pairCabinRow}
          onCreate={createCabinRow}
          onSkipHtml={skipHtmlCabinRow}
          onSkipSystem={skipSystemCabin}
          onRestoreHtml={restoreHtmlCabinRow}
          onRestoreSystem={restoreSystemCabin}
        />
      </div>
    )
    footer = (
      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          onClick={() => setStep("mappings")}
        >
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
        <Button type="button" onClick={() => setStep("summary")}>
          К итогу
          <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-end" />
        </Button>
      </DialogFooter>
    )
  } else {
    const summary = summaryRows(rows, effectiveRowDecisions)
    const skippedHtmlRows = rows.filter(
      (row) =>
        (effectiveRowDecisions[row.id] ?? rowDecisionFor(row)).action ===
        "EXCLUDE"
    )
    const terminal = TERMINAL_IMPORT_STATES.has(currentImport.state)
    const running = isImportInProgress(currentImport)
    const mediaRetryAvailable = canRetryImportMedia(currentImport)
    const mediaSkipAvailable =
      currentImport.state === "FAILED" && currentImport.mediaJobId !== null
    const canReplaceRejectedMedia =
      yandexMediaRecoveryAvailable &&
      rowsComplete &&
      mediaLinkedRows.length > 0 &&
      mediaReplacementItems.length > 0 &&
      invalidMediaReplacementRowIds.size === 0
    content = (
      <div className="flex flex-col gap-4">
        <Card>
          <CardHeader>
            <CardTitle>Итог импорта</CardTitle>
            <CardDescription>
              План хранится в сервисе имущества и может быть продолжен после
              закрытия окна.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={importStateBadgeVariant(currentImport.state)}>
                {importStateTitle(currentImport.state)}
              </Badge>
              <span className="text-sm text-muted-foreground">
                Версия {currentImport.version}
              </span>
            </div>
            <p className="text-sm text-muted-foreground">
              Склад назначения: {warehouseName}
            </p>
            <progress
              className="w-full"
              value={importProgressValue(currentImport.state)}
              max={100}
              aria-label="Ход импорта"
            />
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              <Badge variant="outline">Создать: {summary.create}</Badge>
              <Badge variant="outline">Объединить: {summary.merge}</Badge>
              <Badge variant="outline">Исключить: {summary.exclude}</Badge>
            </div>
            {currentImport.summary.committedRows !== null ? (
              <p className="text-sm text-muted-foreground">
                Обработано строк: {currentImport.summary.committedRows}.
              </p>
            ) : null}
            {currentImport.summary.mediaPendingRows !== null ? (
              <p className="text-sm text-muted-foreground">
                Медиафайлов в импорте: {currentImport.summary.mediaPendingRows}.
              </p>
            ) : null}
            {currentImport.failureCode ? (
              <div className="flex flex-col gap-1 text-sm text-destructive">
                <p>{mediaFailureDescription(currentImport.failureCode)}</p>
                <p>Код ошибки: {currentImport.failureCode}</p>
              </div>
            ) : null}
            {!terminal && !canExecuteImport ? (
              <p className="text-sm text-muted-foreground">
                Готовый импорт должен запустить администратор.
              </p>
            ) : null}
          </CardContent>
        </Card>

        {yandexMediaRecoveryAvailable ? (
          <Card>
            <CardHeader>
              <CardTitle>Ссылки на фото бытовок</CardTitle>
              <CardDescription>
                Укажите новые ссылки только для тех бытовок, где ссылка больше
                не открывается. Исходные публичные ссылки повторно не
                показываются и не сохраняются в браузере.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              {rowsQuery.isLoading || rowsQuery.isFetchingNextPage ? (
                <p className="text-sm text-muted-foreground">
                  Загружаем бытовки со ссылками на фото…
                </p>
              ) : null}
              {rowsQuery.error ? (
                <FieldError role="alert">
                  {commandError(rowsQuery.error)}
                </FieldError>
              ) : null}
              {rowsComplete && mediaLinkedRows.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  В этом импорте не найдено строк с медиа для замены.
                </p>
              ) : null}
              {mediaLinkedRows.map((row) => {
                const publicUrl = mediaReplacementLinks[row.id] ?? ""
                const invalid =
                  publicUrl.trim().length > 0 &&
                  invalidMediaReplacementRowIds.has(row.id)
                const inputId = `html-import-media-link-${row.id}`
                return (
                  <Field key={row.id} data-invalid={invalid || undefined}>
                    <FieldLabel htmlFor={inputId}>
                      Бытовка {row.number}
                    </FieldLabel>
                    <Input
                      id={inputId}
                      value={publicUrl}
                      onChange={(event) =>
                        updateMediaReplacementLink(row.id, event.target.value)
                      }
                      placeholder="https://disk.yandex.ru/d/…"
                      inputMode="url"
                      aria-invalid={invalid || undefined}
                    />
                    {invalid ? (
                      <FieldError>
                        Нужна полная публичная ссылка Яндекс.Диска вида
                        https://disk.yandex.ru/d/…
                      </FieldError>
                    ) : null}
                  </Field>
                )
              })}
            </CardContent>
          </Card>
        ) : null}

        <SkippedRecordsSummary
          htmlRows={skippedHtmlRows}
          systemCabins={skippedSystemCabins}
        />

        {issues.length > 0 ? (
          <Card>
            <CardHeader>
              <CardTitle>Перед сохранением исправьте</CardTitle>
              <CardDescription>
                Все непустые конфликты и обязательные поля должны иметь решение.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="flex list-disc flex-col gap-2 pl-5 text-sm text-destructive">
                {issues.map((issue) => (
                  <li key={issue.id}>{issue.message}</li>
                ))}
              </ul>
            </CardContent>
          </Card>
        ) : null}
        {savePlanMutation.error ? (
          <FieldError role="alert">
            {commandError(savePlanMutation.error)}
          </FieldError>
        ) : null}
        {commitMutation.error ? (
          <FieldError role="alert">
            {commandError(commitMutation.error)}
          </FieldError>
        ) : null}
        {retryMediaMutation.error ? (
          <FieldError role="alert">
            {commandError(retryMediaMutation.error)}
          </FieldError>
        ) : null}
        {replaceMediaMutation.error ? (
          <FieldError role="alert">
            {commandError(replaceMediaMutation.error)}
          </FieldError>
        ) : null}
        {skipMediaMutation.error ? (
          <FieldError role="alert">
            {commandError(skipMediaMutation.error)}
          </FieldError>
        ) : null}
      </div>
    )
    footer = (
      <DialogFooter>
        {!terminal && !running ? (
          <Button
            type="button"
            variant="outline"
            onClick={() => setStep("cabins")}
          >
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Button>
        ) : null}
        {mediaRetryAvailable && canExecuteImport ? (
          <Button
            type="button"
            variant="outline"
            disabled={retryMediaMutation.isPending}
            onClick={() => retryMediaMutation.mutate()}
          >
            <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
            Повторить медиа
          </Button>
        ) : null}
        {yandexMediaRecoveryAvailable && canExecuteImport ? (
          <Button
            type="button"
            disabled={
              !canReplaceRejectedMedia || replaceMediaMutation.isPending
            }
            onClick={() => replaceMediaMutation.mutate()}
          >
            {replaceMediaMutation.isPending ? (
              <HugeiconsIcon icon={Loading03Icon} data-icon="inline-start" />
            ) : null}
            Заменить ссылки и повторить
          </Button>
        ) : null}
        {mediaSkipAvailable && canExecuteImport ? (
          <Button
            type="button"
            variant="outline"
            disabled={skipMediaMutation.isPending}
            onClick={() => skipMediaMutation.mutate()}
          >
            {skipMediaMutation.isPending ? (
              <HugeiconsIcon icon={Loading03Icon} data-icon="inline-start" />
            ) : null}
            Пропустить медиа
          </Button>
        ) : null}
        {!terminal && !running && !planSaved ? (
          <Button
            type="button"
            disabled={issues.length > 0 || savePlanMutation.isPending}
            onClick={() => savePlanMutation.mutate()}
          >
            {savePlanMutation.isPending ? (
              <HugeiconsIcon icon={Loading03Icon} data-icon="inline-start" />
            ) : null}
            Сохранить план
          </Button>
        ) : null}
        {!terminal && !running && planSaved && canExecuteImport ? (
          <Button
            type="button"
            disabled={commitMutation.isPending}
            onClick={() => commitMutation.mutate()}
          >
            {commitMutation.isPending ? (
              <HugeiconsIcon icon={Loading03Icon} data-icon="inline-start" />
            ) : null}
            Запустить импорт
            <HugeiconsIcon
              icon={CheckmarkCircle02Icon}
              data-icon="inline-end"
            />
          </Button>
        ) : null}
        {terminal ? (
          <Button type="button" onClick={() => changeOpen(false)}>
            Закрыть
          </Button>
        ) : null}
      </DialogFooter>
    )
  }

  return (
    <>
      <Dialog open={open} onOpenChange={changeOpen}>
        <DialogContent
          showCloseButton={false}
          className="!fixed !top-3 !right-3 !bottom-3 !left-3 !h-auto !w-auto !max-w-none !translate-x-0 !translate-y-0 !gap-0 !overflow-hidden !p-0 sm:!max-w-none md:!left-[calc(var(--sidebar-width)+0.75rem)]"
        >
          <DialogHeader className="border-b px-5 py-4 text-left sm:px-6">
            <div className="flex items-center justify-between gap-3">
              <Button
                type="button"
                variant="ghost"
                disabled={isPending}
                onClick={() => changeOpen(false)}
              >
                <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
                Назад
              </Button>
              {activeStep === "source" ? (
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => fileInputRef.current?.click()}
                >
                  <HugeiconsIcon
                    icon={FileUploadIcon}
                    data-icon="inline-start"
                  />
                  Добавить файл
                </Button>
              ) : null}
            </div>
            <DialogTitle>Импортировать из HTML</DialogTitle>
            <DialogDescription>
              Импорт относится только к выбранному складу и сохраняется в
              сервисе имущества.
            </DialogDescription>
          </DialogHeader>
          <WizardShell activeStep={activeStep} footer={footer}>
            {content}
          </WizardShell>
        </DialogContent>
      </Dialog>

      <AlertDialog
        open={cancelConfirmationOpen}
        onOpenChange={(nextOpen) => {
          if (!cancelMutation.isPending) setCancelConfirmationOpen(nextOpen)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Удалить текущий импорт?</AlertDialogTitle>
            <AlertDialogDescription>
              Будут удалены только черновик импорта и распознанные строки.
              Бытовки склада и их фотографии не изменятся.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {cancelMutation.error ? (
            <FieldError role="alert">
              {commandError(cancelMutation.error)}
            </FieldError>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancelMutation.isPending}>
              Продолжить импорт
            </AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={cancelMutation.isPending}
              onClick={(event) => {
                event.preventDefault()
                cancelMutation.mutate()
              }}
            >
              {cancelMutation.isPending ? (
                <HugeiconsIcon icon={Loading03Icon} data-icon="inline-start" />
              ) : null}
              Удалить импорт
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

export function HtmlImportHeaderAction({
  warehouseId,
  warehouseName,
}: {
  warehouseId: string
  warehouseName: string
}) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        <HugeiconsIcon icon={FileImportIcon} data-icon="inline-start" />
        Импортировать из HTML
      </Button>
      <HtmlImportWorkspace
        open={open}
        warehouseId={warehouseId}
        warehouseName={warehouseName}
        onOpenChange={setOpen}
      />
    </>
  )
}
