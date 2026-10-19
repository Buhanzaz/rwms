import { type FormEvent, type ReactNode, useMemo, useState } from "react"
import {
  DndContext,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import type {
  HtmlImportRow,
  HtmlImportRowAction,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  htmlImportRentalNumberError,
  htmlImportRentalNumberIdentityKey,
  normalizeHtmlImportRentalNumber,
} from "@/features/rental-items/html-import/html-import-number"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { cn } from "@/lib/utils"

const INVALID_NUMBER_CODE = "INVALID_RENTAL_NUMBER"

type SelectedRecord =
  | { kind: "html"; id: string }
  | { kind: "system"; id: string }
  | null

type DragRecord =
  | { kind: "html"; rowId: string }
  | { kind: "system"; rentalItemId: string }

type DropRecord =
  | DragRecord
  | { kind: "skip"; side: "html" | "system" }
  | { kind: "create" }

export type ResolvedHtmlCabinRow = {
  row: HtmlImportRow
  action: Exclude<HtmlImportRowAction, "REVIEW">
  target: RentalItemDto | null
  proposedNumber: string | null
}

function isDragRecord(value: unknown): value is DragRecord {
  if (!value || typeof value !== "object" || !("kind" in value)) return false
  if (value.kind === "html") {
    return "rowId" in value && typeof value.rowId === "string"
  }
  if (value.kind === "system") {
    return (
      "rentalItemId" in value && typeof value.rentalItemId === "string"
    )
  }
  return false
}

function isDropRecord(value: unknown): value is DropRecord {
  if (isDragRecord(value)) return true
  if (!value || typeof value !== "object" || !("kind" in value)) return false
  return value.kind === "skip" || value.kind === "create"
}

function htmlField(row: HtmlImportRow, aliases: string[]) {
  const entry = Object.entries(row.source.fields).find(([key]) =>
    aliases.includes(key.toLocaleLowerCase("ru-RU"))
  )
  return entry?.[1] ?? null
}

function htmlSearchText(row: HtmlImportRow) {
  return [
    row.number,
    row.source.label,
    ...Object.values(row.source.fields),
    ...row.diagnostics.flatMap((diagnostic) => [
      diagnostic.code,
      diagnostic.field,
    ]),
  ]
    .filter((value): value is string => typeof value === "string")
    .join(" ")
    .toLocaleLowerCase("ru-RU")
}

function systemSearchText(item: RentalItemDto) {
  return [
    item.number,
    item.type,
    item.dimensions,
    item.finishing,
    item.category,
    RENTAL_ITEM_STATUS_LABEL[item.status],
    item.comment,
    ...item.characteristics.map((characteristic) => characteristic.name),
    ...item.tags,
  ]
    .filter((value): value is string => typeof value === "string")
    .join(" ")
    .toLocaleLowerCase("ru-RU")
}

function HtmlQueueRow({
  row,
  selected,
  numberNeedsChange,
  duplicateNumber,
  onSelect,
  onEditNumber,
}: {
  row: HtmlImportRow
  selected: boolean
  numberNeedsChange: boolean
  duplicateNumber: boolean
  onSelect: () => void
  onEditNumber: () => void
}) {
  const dragData: DragRecord = { kind: "html", rowId: row.id }
  const dropData: DropRecord = { kind: "html", rowId: row.id }
  const {
    attributes,
    isDragging,
    listeners,
    setNodeRef: setDragRef,
  } = useDraggable({
    id: `html-cabin-drag-${row.id}`,
    data: dragData,
  })
  const { isOver, setNodeRef: setDropRef } = useDroppable({
    id: `html-cabin-drop-${row.id}`,
    data: dropData,
  })
  const invalidNumber = row.diagnostics.some(
    (diagnostic) => diagnostic.code === INVALID_NUMBER_CODE
  )
  const errorCount = row.diagnostics.filter(
    (diagnostic) => diagnostic.severity === "ERROR"
  ).length

  return (
    <TableRow
      ref={setDropRef}
      data-state={selected ? "selected" : undefined}
      className={cn(isOver && "bg-accent", isDragging && "opacity-50")}
    >
      <TableCell className="min-w-40">
        <Button
          ref={setDragRef}
          type="button"
          {...attributes}
          {...listeners}
          size="sm"
          variant={selected ? "default" : "ghost"}
          className="w-full cursor-grab justify-start active:cursor-grabbing"
          aria-label={`Выбрать HTML: ${row.number}`}
          aria-pressed={selected}
          onClick={onSelect}
        >
          {row.number}
        </Button>
      </TableCell>
      <TableCell className="max-w-56 whitespace-normal">
        {htmlField(row, ["rentaltype", "type"]) ?? "—"}
      </TableCell>
      <TableCell className="max-w-52 whitespace-normal">
        {htmlField(row, ["dimension", "dimensions"]) ?? "—"}
      </TableCell>
      <TableCell className="max-w-52 whitespace-normal">
        {htmlField(row, ["finishing", "finish"]) ?? "—"}
      </TableCell>
      <TableCell className="max-w-52 whitespace-normal">
        <div className="flex flex-wrap gap-1">
          {invalidNumber ? (
            <Badge variant="destructive">Некорректный номер</Badge>
          ) : null}
          {!invalidNumber && duplicateNumber ? (
            <Badge variant="destructive">Дубликат в импорте</Badge>
          ) : null}
          {!invalidNumber && !duplicateNumber && numberNeedsChange ? (
            <Badge variant="destructive">Номер уже занят</Badge>
          ) : null}
          {!invalidNumber && !numberNeedsChange && errorCount > 0 ? (
            <Badge variant="destructive">Конфликтов: {errorCount}</Badge>
          ) : null}
          {errorCount === 0 && !numberNeedsChange ? (
            <Badge variant="outline">Проверка</Badge>
          ) : null}
          {numberNeedsChange ? (
            <Button
              type="button"
              size="xs"
              variant="outline"
              onClick={onEditNumber}
            >
              Изменить номер
            </Button>
          ) : null}
        </div>
      </TableCell>
    </TableRow>
  )
}

function SystemQueueRow({
  item,
  selected,
  onSelect,
}: {
  item: RentalItemDto
  selected: boolean
  onSelect: () => void
}) {
  const dragData: DragRecord = {
    kind: "system",
    rentalItemId: item.id,
  }
  const dropData: DropRecord = {
    kind: "system",
    rentalItemId: item.id,
  }
  const {
    attributes,
    isDragging,
    listeners,
    setNodeRef: setDragRef,
  } = useDraggable({
    id: `system-cabin-drag-${item.id}`,
    data: dragData,
  })
  const { isOver, setNodeRef: setDropRef } = useDroppable({
    id: `system-cabin-drop-${item.id}`,
    data: dropData,
  })

  return (
    <TableRow
      ref={setDropRef}
      data-state={selected ? "selected" : undefined}
      className={cn(isOver && "bg-accent", isDragging && "opacity-50")}
    >
      <TableCell className="min-w-40">
        <Button
          ref={setDragRef}
          type="button"
          {...attributes}
          {...listeners}
          size="sm"
          variant={selected ? "default" : "ghost"}
          className="w-full cursor-grab justify-start active:cursor-grabbing"
          aria-label={`Выбрать в системе: ${item.number}`}
          aria-pressed={selected}
          onClick={onSelect}
        >
          {item.number}
        </Button>
      </TableCell>
      <TableCell className="max-w-56 whitespace-normal">
        {item.type || "—"}
      </TableCell>
      <TableCell className="max-w-52 whitespace-normal">
        {item.dimensions || "—"}
      </TableCell>
      <TableCell className="max-w-52 whitespace-normal">
        {item.finishing || "—"}
      </TableCell>
      <TableCell className="max-w-52 whitespace-normal">
        {RENTAL_ITEM_STATUS_LABEL[item.status]}
      </TableCell>
    </TableRow>
  )
}

function SkipDropZone({
  side,
  selected,
  onSkipSelected,
}: {
  side: "html" | "system"
  selected: boolean
  onSkipSelected: () => void
}) {
  const { isOver, setNodeRef } = useDroppable({
    id: `cabin-skip-${side}`,
    data: { kind: "skip", side } satisfies DropRecord,
  })

  return (
    <Button
      ref={setNodeRef}
      type="button"
      variant="destructive"
      className={cn(
        "h-auto min-h-16 w-full whitespace-normal",
        isOver && "ring-2 ring-ring ring-offset-2"
      )}
      aria-disabled={!selected}
      onClick={onSkipSelected}
    >
      Пропустить выбранную строку
    </Button>
  )
}

function CreateDropZone({
  selectedHtml,
  onCreateSelected,
}: {
  selectedHtml: boolean
  onCreateSelected: () => void
}) {
  const { isOver, setNodeRef } = useDroppable({
    id: "cabin-create",
    data: { kind: "create" } satisfies DropRecord,
  })

  return (
    <Button
      ref={setNodeRef}
      type="button"
      variant="outline"
      className={cn(
        "h-auto min-h-16 w-full whitespace-normal",
        isOver && "bg-accent ring-2 ring-ring ring-offset-2"
      )}
      aria-disabled={!selectedHtml}
      onClick={onCreateSelected}
    >
      Создать новую бытовку из HTML
    </Button>
  )
}

function QueueTableCard({
  side,
  title,
  description,
  count,
  search,
  onSearchChange,
  children,
  emptyMessage,
  selected,
  onSkipSelected,
  onCreateSelected,
  createAllCount,
  createAllDisabled,
  onCreateAll,
}: {
  side: "html" | "system"
  title: string
  description: string
  count: number
  search: string
  onSearchChange: (value: string) => void
  children: ReactNode
  emptyMessage: string
  selected: boolean
  onSkipSelected: () => void
  onCreateSelected?: () => void
  createAllCount?: number
  createAllDisabled?: boolean
  onCreateAll?: () => void
}) {
  return (
    <Card className="min-w-0">
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <CardTitle>{title}</CardTitle>
          <Badge variant="outline">{count}</Badge>
        </div>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Input
          type="search"
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
          aria-label={`Поиск: ${title}`}
          placeholder="Номер, тип, размер, отделка или статус"
        />
        <div className="max-h-[46vh] overflow-y-auto rounded-md border">
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-background">
              <TableRow>
                <TableHead>Номер</TableHead>
                <TableHead>Тип</TableHead>
                <TableHead>Размер</TableHead>
                <TableHead>Отделка</TableHead>
                <TableHead>{side === "html" ? "Причина" : "Статус"}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {count === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={5}
                    className="h-24 whitespace-normal text-center text-muted-foreground"
                  >
                    {emptyMessage}
                  </TableCell>
                </TableRow>
              ) : (
                children
              )}
            </TableBody>
          </Table>
        </div>
        {side === "html" && onCreateSelected ? (
          <CreateDropZone
            selectedHtml={selected}
            onCreateSelected={onCreateSelected}
          />
        ) : null}
        {side === "html" && onCreateAll ? (
          <Button
            type="button"
            variant="secondary"
            disabled={createAllDisabled || createAllCount === 0}
            onClick={onCreateAll}
          >
            Добавить все из HTML
            {typeof createAllCount === "number" ? ` (${createAllCount})` : ""}
          </Button>
        ) : null}
        <SkipDropZone
          side={side}
          selected={selected}
          onSkipSelected={onSkipSelected}
        />
        {side === "system" ? (
          <p className="text-xs text-muted-foreground">
            Пропуск убирает бытовку только из этого списка сопоставления. Из
            склада и базы она не удаляется.
          </p>
        ) : null}
      </CardContent>
    </Card>
  )
}

export function HtmlImportCabinPairingBoard({
  rows,
  duplicateNumberConflicts,
  systemCabins,
  resolvedRows,
  skippedSystemCabins,
  systemLoading,
  systemError,
  onPair,
  onCreate,
  onSkipHtml,
  onSkipSystem,
  onRestoreHtml,
  onRestoreSystem,
}: {
  rows: HtmlImportRow[]
  duplicateNumberConflicts: ReadonlyMap<string, readonly string[]>
  systemCabins: RentalItemDto[]
  resolvedRows: ResolvedHtmlCabinRow[]
  skippedSystemCabins: RentalItemDto[]
  systemLoading: boolean
  systemError: string | null
  onPair: (row: HtmlImportRow, target: RentalItemDto) => void
  onCreate: (row: HtmlImportRow, proposedNumber: string) => void
  onSkipHtml: (row: HtmlImportRow) => void
  onSkipSystem: (target: RentalItemDto) => void
  onRestoreHtml: (row: HtmlImportRow) => void
  onRestoreSystem: (target: RentalItemDto) => void
}) {
  const [htmlSearch, setHtmlSearch] = useState("")
  const [systemSearch, setSystemSearch] = useState("")
  const [selected, setSelected] = useState<SelectedRecord>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionNotice, setActionNotice] = useState<string | null>(null)
  const [numberEditorRow, setNumberEditorRow] = useState<HtmlImportRow | null>(
    null
  )
  const [numberDraft, setNumberDraft] = useState("")
  const [numberEditorError, setNumberEditorError] = useState<string | null>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  )
  const resolvedHtmlIds = useMemo(
    () => new Set(resolvedRows.map((entry) => entry.row.id)),
    [resolvedRows]
  )
  const pairedSystemIds = useMemo(
    () =>
      new Set(
        resolvedRows.flatMap((entry) =>
          entry.action === "MERGE" && entry.target ? [entry.target.id] : []
        )
      ),
    [resolvedRows]
  )
  const skippedSystemIds = useMemo(
    () => new Set(skippedSystemCabins.map((item) => item.id)),
    [skippedSystemCabins]
  )
  const duplicateNumberRowIds = useMemo(
    () =>
      new Set(
        Array.from(duplicateNumberConflicts.values()).flatMap((rowIds) =>
          Array.from(rowIds)
        )
      ),
    [duplicateNumberConflicts]
  )
  const unresolvedRows = useMemo(
    () => rows.filter((row) => !resolvedHtmlIds.has(row.id)),
    [resolvedHtmlIds, rows]
  )
  const availableRows = useMemo(() => {
    const search = htmlSearch.trim().toLocaleLowerCase("ru-RU")
    return unresolvedRows.filter(
      (row) => !search || htmlSearchText(row).includes(search)
    )
  }, [htmlSearch, unresolvedRows])
  const availableSystemCabins = useMemo(() => {
    const search = systemSearch.trim().toLocaleLowerCase("ru-RU")
    return systemCabins.filter(
      (item) =>
        !pairedSystemIds.has(item.id) &&
        !skippedSystemIds.has(item.id) &&
        (!search || systemSearchText(item).includes(search))
    )
  }, [pairedSystemIds, skippedSystemIds, systemCabins, systemSearch])
  const systemNumberKeys = useMemo(
    () =>
      new Set(
        systemCabins.map((item) =>
          htmlImportRentalNumberIdentityKey(item.number)
        )
      ),
    [systemCabins]
  )
  const createdNumberKeys = useMemo(
    () =>
      new Set(
        resolvedRows.flatMap((entry) => {
          if (entry.action !== "CREATE") return []
          const number =
            entry.proposedNumber ??
            entry.row.proposedNumber ??
            entry.row.number
          return [htmlImportRentalNumberIdentityKey(number)]
        })
      ),
    [resolvedRows]
  )

  function invalidNumber(row: HtmlImportRow) {
    return row.diagnostics.some(
      (diagnostic) => diagnostic.code === INVALID_NUMBER_CODE
    )
  }

  function finishAction() {
    setActionError(null)
    setActionNotice(null)
    setSelected(null)
  }

  function proposedNumber(row: HtmlImportRow) {
    return row.proposedNumber ?? row.number
  }

  function numberIssue(
    value: string,
    rowId?: string,
    reservedKeys: ReadonlySet<string> = new Set([
      ...systemNumberKeys,
      ...createdNumberKeys,
    ])
  ) {
    const validationError = htmlImportRentalNumberError(value)
    if (validationError) return validationError

    const key = htmlImportRentalNumberIdentityKey(value)
    const duplicateRowIds = duplicateNumberConflicts.get(key)
    if (duplicateRowIds?.some((candidateId) => candidateId !== rowId)) {
      return `Номер «${normalizeHtmlImportRentalNumber(value)}» повторяется в плане импорта. Измените номер одной из строк, объедините её с бытовкой склада или исключите.`
    }
    if (reservedKeys.has(key)) {
      return `Номер «${normalizeHtmlImportRentalNumber(value)}» уже используется на складе или выбран для другой бытовки этого импорта.`
    }

    return null
  }

  function openNumberEditor(row: HtmlImportRow, error?: string | null) {
    const draft = proposedNumber(row)
    setNumberEditorRow(row)
    setNumberDraft(draft)
    setNumberEditorError(error ?? numberIssue(draft, row.id))
    setActionError(null)
    setActionNotice(null)
  }

  function pair(row: HtmlImportRow, target: RentalItemDto) {
    if (invalidNumber(row)) {
      setActionError(
        `У «${row.number}» некорректный номер. Эту строку можно только пропустить.`
      )
      return
    }
    onPair(row, target)
    finishAction()
  }

  function create(row: HtmlImportRow) {
    if (systemLoading) {
      setActionError(
        "Дождитесь, пока загрузится полный список номеров выбранного склада."
      )
      return
    }
    const number = proposedNumber(row)
    const issue = numberIssue(number, row.id)
    if (issue) {
      openNumberEditor(row, issue)
      return
    }
    onCreate(row, normalizeHtmlImportRentalNumber(number))
    finishAction()
  }

  function createAll() {
    if (systemLoading) {
      setActionError(
        "Дождитесь, пока загрузится полный список номеров выбранного склада."
      )
      return
    }

    const reservedKeys = new Set([...systemNumberKeys, ...createdNumberKeys])
    const blocked: HtmlImportRow[] = []
    let created = 0

    unresolvedRows.forEach((row) => {
      const number = proposedNumber(row)
      if (numberIssue(number, row.id, reservedKeys)) {
        blocked.push(row)
        return
      }
      const normalized = normalizeHtmlImportRentalNumber(number)
      reservedKeys.add(htmlImportRentalNumberIdentityKey(normalized))
      onCreate(row, normalized)
      created += 1
    })

    setSelected(null)
    if (blocked.length > 0) {
      setActionNotice(null)
      setActionError(
        `Добавлено в план: ${created}. Осталось строк с некорректным или занятым номером: ${blocked.length}. Исправьте номер либо сопоставьте такую строку с бытовкой склада.`
      )
      return
    }
    setActionError(null)
    setActionNotice(`Все оставшиеся строки добавлены в план: ${created}.`)
  }

  function submitEditedNumber(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!numberEditorRow) return
    const issue = numberIssue(numberDraft, numberEditorRow.id)
    if (issue) {
      setNumberEditorError(issue)
      return
    }

    onCreate(
      numberEditorRow,
      normalizeHtmlImportRentalNumber(numberDraft)
    )
    setNumberEditorRow(null)
    setNumberDraft("")
    setNumberEditorError(null)
    finishAction()
  }

  function skip(record: DragRecord) {
    if (record.kind === "html") {
      const row = rows.find((candidate) => candidate.id === record.rowId)
      if (row) onSkipHtml(row)
    } else {
      const item = systemCabins.find(
        (candidate) => candidate.id === record.rentalItemId
      )
      if (item) onSkipSystem(item)
    }
    finishAction()
  }

  function selectHtml(row: HtmlImportRow) {
    if (selected?.kind === "system") {
      const target = systemCabins.find((item) => item.id === selected.id)
      if (target) pair(row, target)
      return
    }
    setActionError(null)
    setSelected(
      selected?.kind === "html" && selected.id === row.id
        ? null
        : { kind: "html", id: row.id }
    )
  }

  function selectSystem(target: RentalItemDto) {
    if (selected?.kind === "html") {
      const row = rows.find((candidate) => candidate.id === selected.id)
      if (row) pair(row, target)
      return
    }
    setActionError(null)
    setSelected(
      selected?.kind === "system" && selected.id === target.id
        ? null
        : { kind: "system", id: target.id }
    )
  }

  function onDragEnd(event: DragEndEvent) {
    const active = event.active.data.current
    const over = event.over?.data.current
    if (!isDragRecord(active) || !isDropRecord(over)) return

    if (over.kind === "skip") {
      skip(active)
      return
    }
    if (active.kind === "html" && over.kind === "create") {
      const row = rows.find((candidate) => candidate.id === active.rowId)
      if (row) create(row)
      return
    }
    if (active.kind === "html" && over.kind === "system") {
      const row = rows.find((candidate) => candidate.id === active.rowId)
      const target = systemCabins.find(
        (candidate) => candidate.id === over.rentalItemId
      )
      if (row && target) pair(row, target)
      return
    }
    if (active.kind === "system" && over.kind === "html") {
      const row = rows.find((candidate) => candidate.id === over.rowId)
      const target = systemCabins.find(
        (candidate) => candidate.id === active.rentalItemId
      )
      if (row && target) pair(row, target)
    }
  }

  function skipSelected() {
    if (selected?.kind === "html") {
      skip({ kind: "html", rowId: selected.id })
    } else if (selected?.kind === "system") {
      skip({ kind: "system", rentalItemId: selected.id })
    }
  }

  function createSelected() {
    if (selected?.kind !== "html") return
    const row = rows.find((candidate) => candidate.id === selected.id)
    if (row) create(row)
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Сопоставление бытовок</CardTitle>
          <CardDescription>
            Перетащите строку HTML на бытовку склада или наоборот. При
            объединении значения из HTML заменят конфликтующие поля, а UUID,
            медиа и данные, которых нет в HTML, останутся у складской бытовки.
            Для работы без перетаскивания выберите строки по очереди.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-2">
          <Badge variant="outline">Осталось HTML: {availableRows.length}</Badge>
          <Badge variant="outline">
            Доступно на складе: {availableSystemCabins.length}
          </Badge>
          {selected ? (
            <Badge>
              Выбрано:{" "}
              {selected.kind === "html" ? "строка HTML" : "бытовка склада"}
            </Badge>
          ) : null}
        </CardContent>
      </Card>

      {actionError ? (
        <Alert variant="destructive">
          <AlertTitle>Остались строки, требующие решения</AlertTitle>
          <AlertDescription>{actionError}</AlertDescription>
        </Alert>
      ) : null}
      {actionNotice ? (
        <Alert>
          <AlertTitle>Строки добавлены</AlertTitle>
          <AlertDescription>{actionNotice}</AlertDescription>
        </Alert>
      ) : null}
      {duplicateNumberConflicts.size > 0 ? (
        <Alert variant="destructive">
          <AlertTitle>Повторяющиеся номера в плане</AlertTitle>
          <AlertDescription>
            Для каждого номера оставьте только одну новую бытовку: измените
            номер, объедините строку с бытовкой склада или пропустите её.
          </AlertDescription>
        </Alert>
      ) : null}
      {systemError ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить складские бытовки</AlertTitle>
          <AlertDescription>{systemError}</AlertDescription>
        </Alert>
      ) : null}

      <DndContext sensors={sensors} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-1 gap-4 2xl:grid-cols-2">
          <QueueTableCard
            side="html"
            title="Из HTML"
            description="Только строки, которым действительно нужно ручное решение."
            count={availableRows.length}
            search={htmlSearch}
            onSearchChange={setHtmlSearch}
            emptyMessage="Все конфликтные строки HTML уже обработаны."
            selected={selected?.kind === "html"}
            onSkipSelected={skipSelected}
            onCreateSelected={createSelected}
            createAllCount={unresolvedRows.length}
            createAllDisabled={systemLoading}
            onCreateAll={createAll}
          >
            {availableRows.map((row) => (
              <HtmlQueueRow
                key={row.id}
                row={row}
                selected={selected?.kind === "html" && selected.id === row.id}
                numberNeedsChange={numberIssue(proposedNumber(row), row.id) !== null}
                duplicateNumber={duplicateNumberRowIds.has(row.id)}
                onSelect={() => selectHtml(row)}
                onEditNumber={() => openNumberEditor(row)}
              />
            ))}
          </QueueTableCard>

          <QueueTableCard
            side="system"
            title="На складе"
            description="Существующие бытовки выбранного склада, доступные для привязки."
            count={availableSystemCabins.length}
            search={systemSearch}
            onSearchChange={setSystemSearch}
            emptyMessage={
              systemLoading
                ? "Загружаем бытовки склада…"
                : "Подходящих бытовок на выбранном складе нет."
            }
            selected={selected?.kind === "system"}
            onSkipSelected={skipSelected}
          >
            {availableSystemCabins.map((item) => (
              <SystemQueueRow
                key={item.id}
                item={item}
                selected={
                  selected?.kind === "system" && selected.id === item.id
                }
                onSelect={() => selectSystem(item)}
              />
            ))}
          </QueueTableCard>
        </div>
      </DndContext>

      {resolvedRows.length > 0 || skippedSystemCabins.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Уже обработано</CardTitle>
            <CardDescription>
              Строки исчезают из рабочих таблиц после решения. Здесь их можно
              вернуть и выбрать заново.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex max-h-60 flex-col gap-2 overflow-y-auto">
            {resolvedRows.map(({ row, action, target, proposedNumber }) => (
              <div
                key={row.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2"
              >
                <span className="text-sm">
                  {row.number} —{" "}
                  {action === "MERGE"
                    ? `связана с ${target?.number ?? "бытовкой склада"}`
                    : action === "CREATE"
                      ? proposedNumber &&
                        normalizeHtmlImportRentalNumber(row.number) !==
                          proposedNumber
                        ? `будет создана с номером ${proposedNumber}`
                        : "будет создана"
                      : "пропущена"}
                </span>
                <Button
                  type="button"
                  size="xs"
                  variant="ghost"
                  aria-label={`Вернуть HTML: ${row.number}`}
                  onClick={() => onRestoreHtml(row)}
                >
                  Вернуть
                </Button>
              </div>
            ))}
            {skippedSystemCabins.map((item) => (
              <div
                key={item.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2"
              >
                <span className="text-sm">
                  {item.number} — пропущена в списке склада
                </span>
                <Button
                  type="button"
                  size="xs"
                  variant="ghost"
                  aria-label={`Вернуть в систему: ${item.number}`}
                  onClick={() => onRestoreSystem(item)}
                >
                  Вернуть
                </Button>
              </div>
            ))}
          </CardContent>
        </Card>
      ) : null}

      <Dialog
        open={numberEditorRow !== null}
        onOpenChange={(nextOpen) => {
          if (nextOpen) return
          setNumberEditorRow(null)
          setNumberDraft("")
          setNumberEditorError(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Изменить номер бытовки</DialogTitle>
            <DialogDescription>
              Исходный номер: {numberEditorRow?.number ?? "—"}. После
              подтверждения бытовка будет добавлена из HTML с новым номером.
            </DialogDescription>
          </DialogHeader>
          <form className="flex flex-col gap-4" onSubmit={submitEditedNumber}>
            <FieldGroup>
              <Field data-invalid={numberEditorError ? true : undefined}>
                <FieldLabel htmlFor="html-import-cabin-number">
                  Новый номер
                </FieldLabel>
                <Input
                  id="html-import-cabin-number"
                  value={numberDraft}
                  aria-invalid={Boolean(numberEditorError)}
                  autoFocus
                  onChange={(event) => {
                    const nextValue = event.target.value
                    setNumberDraft(nextValue)
                    setNumberEditorError(
                      numberEditorRow
                        ? numberIssue(nextValue, numberEditorRow.id)
                        : null
                    )
                  }}
                />
                <FieldDescription>
                  Разрешены буквы, цифры, пробелы, дефис и подчёркивание.
                </FieldDescription>
                {numberEditorError ? (
                  <FieldError>{numberEditorError}</FieldError>
                ) : null}
              </Field>
            </FieldGroup>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  setNumberEditorRow(null)
                  setNumberDraft("")
                  setNumberEditorError(null)
                }}
              >
                Отмена
              </Button>
              <Button type="submit" disabled={Boolean(numberEditorError)}>
                Изменить номер и создать
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
