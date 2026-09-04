import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  Delete02Icon,
  ImageUploadIcon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"
import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"

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
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"
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
  calculateLineTotal,
  createManualEstimateLine,
  formatMoneyDecimal,
  normalizeEstimateLine,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  ReadyMediaReference,
  ServiceMediaOwner,
} from "@/features/media/media-service"
import type {
  RepairEstimateLineDto,
  RepairEstimateLineType,
} from "@/features/repair-estimates/model/repair-estimate"
import { WorkLinePhotoControls } from "@/features/repair-estimates/work-line-photo-controls"
import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"

type RepairEstimateLinesEditorProps = {
  lines: RepairEstimateLineDto[]
  readOnly: boolean
  mode?: "ESTIMATE" | "TASK"
  catalogValuesReadOnly?: boolean
  /** Custom lines must be assigned to a repair-work stage in production editors. */
  customWorkLinesOnly?: boolean
  accessToken?: string | null
  warehouseId?: string
  mediaOwner?: ServiceMediaOwner | null
  ensureMediaOwner?: (
    lines?: RepairEstimateLineDto[]
  ) => Promise<ServiceMediaOwner>
  onChange: (lines: RepairEstimateLineDto[]) => void
}

type CustomWorkQueue = WorkQueueDto & {
  type: "REPAIR" | "HOLDING"
}

type CustomLineEditorId = string | "new" | null

function availableCustomQueues(queues: WorkQueueDto[]): CustomWorkQueue[] {
  return queues
    .filter(
      (queue): queue is CustomWorkQueue =>
        queue.active &&
        !queue.hidden &&
        (queue.type === "REPAIR" || queue.type === "HOLDING")
    )
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder ||
        left.name.localeCompare(right.name, "ru") ||
        left.definitionId.localeCompare(right.definitionId)
    )
}

function customQueueLabel(queue: Pick<WorkQueueDto, "type">) {
  return queue.type === "HOLDING" ? "Ожидание" : "Ремонт"
}

function lineTypeLabel(lineType: RepairEstimateLineType) {
  return lineType === "WORK" ? "Работа" : "Материал"
}

function lineUnitLabel(unit: string | null) {
  return unit?.trim() || "—"
}

export function RepairEstimateLinesEditor({
  lines,
  readOnly,
  mode = "ESTIMATE",
  catalogValuesReadOnly = false,
  customWorkLinesOnly = false,
  accessToken = null,
  warehouseId,
  mediaOwner = null,
  ensureMediaOwner,
  onChange,
}: RepairEstimateLinesEditorProps) {
  const [customLineEditorId, setCustomLineEditorId] =
    useState<CustomLineEditorId>(null)
  const [photoLineId, setPhotoLineId] = useState<string | null>(null)
  const photoLine =
    lines.find((line) => line.id === photoLineId && line.lineType === "WORK") ??
    null
  const excludedWorkMediaIds = useMemo(
    () =>
      new Set(
        lines.flatMap((line) =>
          line.lineType === "WORK" && line.id !== photoLineId
            ? (line.maintenanceMediaReferences ?? []).map(
                (reference) => reference.mediaId
              )
            : []
        )
      ),
    [lines, photoLineId]
  )
  const editedCustomLine =
    typeof customLineEditorId === "string"
      ? (lines.find(
          (line) =>
            line.id === customLineEditorId && line.catalogSnapshot === null
        ) ?? null)
      : null
  const customLineDialogOpen = customLineEditorId !== null
  const needsCustomQueues =
    customWorkLinesOnly &&
    (customLineDialogOpen ||
      lines.some((line) => line.catalogSnapshot === null))
  const customQueuesQuery = useQuery({
    queryKey: ["repair-work", "custom-line-queues", warehouseId],
    queryFn: () =>
      taskBoardSettingsClient.listQueues(accessToken!, warehouseId!),
    enabled:
      !readOnly && needsCustomQueues && Boolean(accessToken && warehouseId),
  })
  const customQueues = useMemo(
    () => availableCustomQueues(customQueuesQuery.data ?? []),
    [customQueuesQuery.data]
  )
  const customQueuesError = customQueuesQuery.isError
    ? "Не удалось загрузить доступные очереди. Повторите попытку."
    : null
  const customQueuesUnavailable =
    !accessToken || !warehouseId
      ? "Для выбора этапа нужен действующий доступ к складу."
      : customQueuesQuery.isSuccess && customQueues.length === 0
        ? "Нет активных видимых очередей ремонта или ожидания."
        : null

  function updateCatalogLine(
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) {
    onChange(
      lines.map((line) => {
        if (line.id !== lineId) return line
        const next = update(line)
        return normalizeEstimateLine({
          ...next,
          customQueueBinding: null,
        })
      })
    )
  }

  function replaceCatalogLine(
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) {
    onChange(
      lines.map((line) => {
        if (line.id !== lineId) return line
        return { ...update(line), customQueueBinding: null }
      })
    )
  }

  function saveCustomLine(nextLine: RepairEstimateLineDto) {
    const line = normalizeEstimateLine({
      ...nextLine,
      normativeMinutes:
        nextLine.lineType === "MATERIAL" ? 0 : nextLine.normativeMinutes,
    })
    onChange(
      customLineEditorId === "new"
        ? [...lines, line]
        : lines.map((current) => (current.id === line.id ? line : current))
    )
    setCustomLineEditorId(null)
  }

  function updateWorkMediaReferences(
    lineId: string,
    references: ReadyMediaReference[]
  ) {
    onChange(
      lines.map((line) =>
        line.id === lineId && line.lineType === "WORK"
          ? normalizeEstimateLine({
              ...line,
              maintenanceMediaReferences: references,
            })
          : line
      )
    )
  }

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="font-heading text-sm font-medium">
            {mode === "TASK" ? "Состав ремонта" : "Смета"}
          </h3>
          <p className="text-xs text-muted-foreground">
            Работа, материал, количество и цена сохраняются отдельными полями.
          </p>
        </div>

        {!readOnly ? (
          <Button
            type="button"
            variant="outline"
            onClick={() => setCustomLineEditorId("new")}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить пользовательскую строку
          </Button>
        ) : null}
      </div>

      {lines.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Строк пока нет</CardTitle>
          </CardHeader>
          <CardContent className="text-muted-foreground">
            {mode === "TASK"
              ? "Для ремонтного задания требуется хотя бы один этап. Добавьте работу или материал из каталога либо пользовательскую строку."
              : "Пустую смету можно сохранить или завершить. Для добавления выберите позицию каталога либо добавьте пользовательскую строку."}
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {lines.map((line, index) =>
            line.rework?.disposition === "REPEAT" ? (
              <RepeatedReworkLineCard
                key={line.id}
                line={line}
                index={index}
                readOnly={readOnly}
                onRemove={() =>
                  onChange(lines.filter((item) => item.id !== line.id))
                }
                onUpdate={updateCatalogLine}
                onEditPhotos={() => setPhotoLineId(line.id)}
              />
            ) : line.catalogSnapshot === null ? (
              <CustomEstimateLineSummary
                key={line.id}
                line={line}
                index={index}
                readOnly={readOnly}
                onEdit={() => setCustomLineEditorId(line.id)}
                onRemove={() =>
                  onChange(lines.filter((item) => item.id !== line.id))
                }
                onEditPhotos={() => setPhotoLineId(line.id)}
              />
            ) : (
              <CatalogEstimateLineCard
                key={line.id}
                line={line}
                index={index}
                readOnly={readOnly}
                catalogValuesReadOnly={catalogValuesReadOnly}
                onRemove={() =>
                  onChange(lines.filter((item) => item.id !== line.id))
                }
                onUpdate={updateCatalogLine}
                onReplace={replaceCatalogLine}
                onEditPhotos={() => setPhotoLineId(line.id)}
              />
            )
          )}
        </div>
      )}

      <CustomEstimateLineDialog
        key={editedCustomLine?.id ?? "new"}
        open={customLineDialogOpen}
        line={editedCustomLine}
        requiresStageBinding={customWorkLinesOnly}
        customQueues={customQueues}
        customQueuesLoading={customQueuesQuery.isLoading}
        customQueuesError={customQueuesError}
        customQueuesUnavailable={customQueuesUnavailable}
        onOpenChange={(open) => {
          if (!open) setCustomLineEditorId(null)
        }}
        onSave={saveCustomLine}
      />
      {photoLine ? (
        <WorkLinePhotoControls
          accessToken={accessToken}
          owner={mediaOwner}
          ensureOwner={
            ensureMediaOwner ? () => ensureMediaOwner(lines) : undefined
          }
          disabled={readOnly}
          excludedMediaIds={excludedWorkMediaIds}
          value={photoLine.maintenanceMediaReferences ?? []}
          open
          onOpenChange={(open) => {
            if (!open) setPhotoLineId(null)
          }}
          onChange={(references) =>
            updateWorkMediaReferences(photoLine.id, references)
          }
        />
      ) : null}
    </section>
  )
}

function WorkLinePhotoAction({
  line,
  onClick,
}: {
  line: RepairEstimateLineDto
  onClick: () => void
}) {
  const hasPhotos = (line.maintenanceMediaReferences?.length ?? 0) > 0
  return (
    <Button type="button" size="sm" variant="outline" onClick={onClick}>
      <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
      {hasPhotos ? "Редактировать фото" : "Добавить фото"}
    </Button>
  )
}

function RepeatedReworkLineCard({
  line,
  index,
  readOnly,
  onRemove,
  onUpdate,
  onEditPhotos,
}: {
  line: RepairEstimateLineDto
  index: number
  readOnly: boolean
  onRemove: () => void
  onEditPhotos: () => void
  onUpdate: (
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) => void
}) {
  const prefix = line.lineType === "WORK" ? "РД" : "МД"
  return (
    <Card size="sm" className="border-primary/40">
      <CardHeader>
        <CardTitle className="flex min-w-0 items-center justify-between gap-2">
          <span className="min-w-0 truncate">
            {prefix} · {line.description}
          </span>
          <div className="flex items-center gap-2">
            <Badge variant="secondary">Переделать</Badge>
            {!readOnly ? (
              <>
                {line.lineType === "WORK" ? (
                  <WorkLinePhotoAction line={line} onClick={onEditPhotos} />
                ) : null}
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`Удалить повтор строки ${index + 1}`}
                  onClick={onRemove}
                >
                  <HugeiconsIcon icon={Delete02Icon} />
                </Button>
              </>
            ) : null}
          </div>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-3 md:grid-cols-12">
        <Field className="md:col-span-5" data-disabled>
          <FieldLabel>Работа / материал</FieldLabel>
          <Input readOnly value={line.description} />
        </Field>
        <Field className="md:col-span-2" data-disabled>
          <FieldLabel>Единица</FieldLabel>
          <Input readOnly value={lineUnitLabel(line.unit)} />
        </Field>
        <Field className="md:col-span-2" data-disabled={readOnly}>
          <FieldLabel htmlFor={`repeat-quantity-${line.id}`}>
            Количество
          </FieldLabel>
          <Input
            id={`repeat-quantity-${line.id}`}
            type="number"
            inputMode="decimal"
            min={0.001}
            step={0.001}
            disabled={readOnly}
            value={line.quantity}
            onChange={(event) => {
              const quantity = Number(event.target.value)
              if (!Number.isFinite(quantity) || quantity <= 0) return
              onUpdate(line.id, (current) => ({ ...current, quantity }))
            }}
          />
        </Field>
        <Field className="md:col-span-3" data-disabled>
          <FieldLabel>Цена</FieldLabel>
          <Input readOnly value={formatMoneyDecimal(line.unitPrice)} />
        </Field>
        {line.lineType === "WORK" ? (
          <Field className="md:col-span-12" data-disabled={readOnly}>
            <FieldLabel htmlFor={`repeat-comment-${line.id}`}>
              Комментарий
            </FieldLabel>
            <Textarea
              id={`repeat-comment-${line.id}`}
              disabled={readOnly}
              value={line.lineComment}
              onChange={(event) =>
                onUpdate(line.id, (current) => ({
                  ...current,
                  lineComment: event.target.value,
                }))
              }
            />
          </Field>
        ) : null}
      </CardContent>
    </Card>
  )
}

function CustomEstimateLineSummary({
  line,
  index,
  readOnly,
  onEdit,
  onRemove,
  onEditPhotos,
}: {
  line: RepairEstimateLineDto
  index: number
  readOnly: boolean
  onEdit: () => void
  onRemove: () => void
  onEditPhotos: () => void
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="flex min-w-0 items-center justify-between gap-2">
          <span className="truncate">
            {line.rework?.disposition === "ADDED"
              ? `${line.lineType === "WORK" ? "РД" : "МД"} · `
              : ""}
            Строка {index + 1}
          </span>
          <div className="flex items-center gap-2">
            {line.rework?.disposition === "ADDED" ? (
              <Badge variant="outline">Добавлено в доработке</Badge>
            ) : null}
            <Badge variant="secondary">{lineTypeLabel(line.lineType)}</Badge>
          </div>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-col gap-1">
          <p className="text-sm font-medium break-words">
            {line.description || "Без названия"}
          </p>
          <p className="text-xs text-muted-foreground">
            {line.quantity} {lineUnitLabel(line.unit)} · Цена{" "}
            {formatMoneyDecimal(line.unitPrice)} · Итого{" "}
            {formatMoneyDecimal(line.lineTotal)}
          </p>
          {line.lineType === "WORK" && line.lineComment.trim() ? (
            <p className="text-xs break-words text-muted-foreground">
              {line.lineComment}
            </p>
          ) : null}
          {line.customQueueBinding ? (
            <p className="text-xs text-muted-foreground">
              Этап: {line.customQueueBinding.queueName} —{" "}
              {line.customQueueBinding.queueKind === "HOLDING"
                ? "Ожидание"
                : "Ремонт"}
            </p>
          ) : null}
        </div>
        {!readOnly ? (
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" variant="outline" size="sm" onClick={onEdit}>
              Изменить
            </Button>
            {line.lineType === "WORK" ? (
              <WorkLinePhotoAction line={line} onClick={onEditPhotos} />
            ) : null}
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`Удалить строку ${index + 1}`}
              onClick={onRemove}
            >
              <HugeiconsIcon icon={Delete02Icon} data-icon="inline-start" />
              Удалить
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

function CustomEstimateLineDialog({
  open,
  line,
  requiresStageBinding,
  customQueues,
  customQueuesLoading,
  customQueuesError,
  customQueuesUnavailable,
  onOpenChange,
  onSave,
}: {
  open: boolean
  line: RepairEstimateLineDto | null
  requiresStageBinding: boolean
  customQueues: CustomWorkQueue[]
  customQueuesLoading: boolean
  customQueuesError: string | null
  customQueuesUnavailable: string | null
  onOpenChange: (open: boolean) => void
  onSave: (line: RepairEstimateLineDto) => void
}) {
  const [draft, setDraft] = useState<RepairEstimateLineDto>(() =>
    line
      ? { ...line, customQueueBinding: line.customQueueBinding ?? null }
      : createManualEstimateLine()
  )
  const durationInvalid =
    draft.lineType === "WORK" &&
    (!Number.isSafeInteger(draft.normativeMinutes) ||
      draft.normativeMinutes === undefined ||
      draft.normativeMinutes <= 0 ||
      draft.normativeMinutes > 525600)
  const nameInvalid = !draft.description.trim()
  const unitInvalid = !draft.unit?.trim()
  const selectedQueue = draft.customQueueBinding ?? null
  const selectedQueueAvailable = selectedQueue
    ? customQueues.some(
        (queue) =>
          queue.definitionId === selectedQueue.queueId &&
          queue.type === selectedQueue.queueKind
      )
    : false
  const stageInvalid =
    requiresStageBinding &&
    !customQueuesLoading &&
    (!selectedQueue || (customQueues.length > 0 && !selectedQueueAvailable))
  const stageMessage = customQueuesLoading
    ? "Загружаем доступные очереди…"
    : (customQueuesError ??
      customQueuesUnavailable ??
      (!selectedQueue
        ? "Выберите этап, к которому будет привязана строка."
        : !selectedQueueAvailable
          ? "Выбранный этап больше недоступен. Выберите активную очередь."
          : "Строка будет включена в этап этой очереди."))
  const total = useMemo(() => {
    try {
      return calculateLineTotal(draft.unitPrice || "0", draft.quantity)
    } catch {
      return "—"
    }
  }, [draft.quantity, draft.unitPrice])
  const saveDisabled =
    nameInvalid ||
    unitInvalid ||
    durationInvalid ||
    (requiresStageBinding &&
      (Boolean(customQueuesError) || Boolean(customQueuesUnavailable))) ||
    stageInvalid

  function updateDraft(
    update: (current: RepairEstimateLineDto) => RepairEstimateLineDto
  ) {
    setDraft((current) => update(current))
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] w-[calc(100%-2rem)] overflow-y-auto sm:max-w-[48vw]">
        <DialogHeader>
          <DialogTitle>
            {line
              ? "Изменить пользовательскую строку"
              : "Пользовательская строка"}
          </DialogTitle>
          <DialogDescription>
            Укажите состав и этап ремонта. Пользовательский материал получает
            нулевую длительность.
          </DialogDescription>
        </DialogHeader>

        <FieldGroup className="gap-4">
          <FieldGroup className="grid gap-4 sm:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="custom-line-type">Тип</FieldLabel>
              <Select
                value={draft.lineType}
                onValueChange={(value) =>
                  updateDraft((current) => ({
                    ...current,
                    lineType: value as RepairEstimateLineType,
                    normativeMinutes:
                      value === "MATERIAL" ? 0 : current.normativeMinutes,
                    lineComment:
                      value === "MATERIAL" ? "" : current.lineComment,
                  }))
                }
              >
                <SelectTrigger
                  id="custom-line-type"
                  aria-label="Тип пользовательской строки"
                  className="w-full"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="WORK">Работа</SelectItem>
                    <SelectItem value="MATERIAL">Материал</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>

            <Field data-invalid={unitInvalid || undefined}>
              <FieldLabel htmlFor="custom-line-unit">Единица</FieldLabel>
              <Input
                id="custom-line-unit"
                aria-label="Единица измерения пользовательской строки"
                aria-invalid={unitInvalid || undefined}
                value={draft.unit ?? ""}
                onChange={(event) =>
                  updateDraft((current) => ({
                    ...current,
                    unit: event.target.value,
                  }))
                }
              />
            </Field>
          </FieldGroup>

          <Field data-invalid={nameInvalid || undefined}>
            <FieldLabel htmlFor="custom-line-name">Наименование</FieldLabel>
            <Input
              id="custom-line-name"
              aria-label="Наименование пользовательской строки"
              aria-invalid={nameInvalid || undefined}
              value={draft.description}
              onChange={(event) =>
                updateDraft((current) => ({
                  ...current,
                  description: event.target.value,
                }))
              }
            />
          </Field>

          {draft.lineType === "WORK" ? (
            <Field>
              <FieldLabel htmlFor="custom-line-comment">Комментарий</FieldLabel>
              <Textarea
                id="custom-line-comment"
                aria-label="Комментарий пользовательской работы"
                value={draft.lineComment}
                onChange={(event) =>
                  updateDraft((current) => ({
                    ...current,
                    lineComment: event.target.value,
                  }))
                }
              />
            </Field>
          ) : null}

          {draft.lineType === "WORK" ? (
            <Field data-invalid={durationInvalid || undefined}>
              <FieldLabel htmlFor="custom-line-duration">Время, мин</FieldLabel>
              <Input
                id="custom-line-duration"
                type="number"
                inputMode="numeric"
                min={1}
                max={525600}
                step={1}
                required
                aria-invalid={durationInvalid || undefined}
                value={
                  typeof draft.normativeMinutes === "number" &&
                  draft.normativeMinutes > 0
                    ? draft.normativeMinutes
                    : ""
                }
                onChange={(event) => {
                  const value = event.target.value
                  if (value !== "" && !/^\d+$/.test(value)) return
                  const normativeMinutes = value === "" ? 0 : Number(value)
                  if (!Number.isSafeInteger(normativeMinutes)) return
                  updateDraft((current) => ({
                    ...current,
                    normativeMinutes,
                  }))
                }}
              />
              <FieldDescription>
                Укажите плановое время выполнения больше 0 минут.
              </FieldDescription>
            </Field>
          ) : null}

          {requiresStageBinding ? (
            <Field data-invalid={stageInvalid || undefined}>
              <FieldLabel htmlFor="custom-line-stage">
                Рабочий этап / очередь
              </FieldLabel>
              <Select
                disabled={
                  customQueuesLoading ||
                  Boolean(customQueuesError) ||
                  Boolean(customQueuesUnavailable)
                }
                value={selectedQueue?.queueId ?? ""}
                onValueChange={(queueDefinitionId) => {
                  const queue = customQueues.find(
                    (candidate) => candidate.definitionId === queueDefinitionId
                  )
                  if (!queue) return
                  updateDraft((current) => ({
                    ...current,
                    customQueueBinding: {
                      queueId: queue.definitionId,
                      queueName: queue.name,
                      queueKind: queue.type,
                    },
                  }))
                }}
              >
                <SelectTrigger
                  id="custom-line-stage"
                  aria-label="Рабочий этап пользовательской строки"
                  aria-invalid={stageInvalid || undefined}
                  className="w-full"
                >
                  <SelectValue
                    placeholder={
                      customQueuesLoading
                        ? "Загрузка очередей…"
                        : "Выберите этап"
                    }
                  />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {customQueues.map((queue) => (
                      <SelectItem
                        key={queue.definitionId}
                        value={queue.definitionId}
                      >
                        {queue.name} ({customQueueLabel(queue)})
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              <FieldDescription>{stageMessage}</FieldDescription>
            </Field>
          ) : null}

          <FieldGroup className="grid gap-4 sm:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="custom-line-quantity">Количество</FieldLabel>
              <Input
                id="custom-line-quantity"
                aria-label="Количество пользовательской строки"
                type="number"
                inputMode="decimal"
                min={1}
                step="0.001"
                value={draft.quantity}
                onChange={(event) => {
                  const quantity = Number(event.target.value)
                  if (!Number.isFinite(quantity)) return
                  updateDraft((current) => ({
                    ...current,
                    quantity: Math.max(1, quantity),
                  }))
                }}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="custom-line-price">Цена</FieldLabel>
              <Input
                id="custom-line-price"
                aria-label="Цена пользовательской строки"
                inputMode="decimal"
                value={draft.unitPrice}
                onChange={(event) => {
                  const unitPrice = event.target.value.replace(",", ".")
                  if (
                    unitPrice === "" ||
                    /^\d+(?:\.\d{0,2})?$/.test(unitPrice)
                  ) {
                    updateDraft((current) => ({ ...current, unitPrice }))
                  }
                }}
              />
            </Field>
          </FieldGroup>

          <Field>
            <FieldLabel htmlFor="custom-line-total">Сумма</FieldLabel>
            <Input
              id="custom-line-total"
              aria-label="Сумма пользовательской строки"
              readOnly
              value={total}
            />
          </Field>
        </FieldGroup>

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
            disabled={saveDisabled}
            onClick={() =>
              onSave({
                ...draft,
                lineTotal: total === "—" ? draft.lineTotal : total,
                normativeMinutes:
                  draft.lineType === "MATERIAL" ? 0 : draft.normativeMinutes,
              })
            }
          >
            Сохранить строку
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CatalogEstimateLineCard({
  line,
  index,
  readOnly,
  catalogValuesReadOnly,
  onRemove,
  onUpdate,
  onReplace,
  onEditPhotos,
}: {
  line: RepairEstimateLineDto
  index: number
  readOnly: boolean
  catalogValuesReadOnly: boolean
  onRemove: () => void
  onUpdate: (
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) => void
  onReplace: (
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) => void
  onEditPhotos: () => void
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="flex items-center justify-between gap-2">
          <span>
            {line.rework?.disposition === "ADDED"
              ? `${line.lineType === "WORK" ? "РД" : "МД"} · `
              : ""}
            Строка {index + 1}
          </span>
          {line.rework?.disposition === "ADDED" ? (
            <Badge variant="outline">Добавлено в доработке</Badge>
          ) : null}
          {!readOnly ? (
            <div className="flex items-center gap-2">
              {line.lineType === "WORK" ? (
                <WorkLinePhotoAction line={line} onClick={onEditPhotos} />
              ) : null}
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={`Удалить строку ${index + 1}`}
                onClick={onRemove}
              >
                <HugeiconsIcon icon={Delete02Icon} />
              </Button>
            </div>
          ) : null}
        </CardTitle>
      </CardHeader>

      <CardContent>
        <FieldGroup className="grid gap-3 md:grid-cols-12">
          <Field className="md:col-span-2" data-disabled>
            <FieldLabel htmlFor={`line-type-${line.id}`}>Тип</FieldLabel>
            <Input
              id={`line-type-${line.id}`}
              aria-label={`Тип строки ${index + 1}`}
              readOnly
              value={lineTypeLabel(line.lineType)}
            />
          </Field>

          <Field className="md:col-span-5" data-disabled={readOnly}>
            <FieldLabel htmlFor={`line-description-${line.id}`}>
              Работа / материал
            </FieldLabel>
            <Input
              id={`line-description-${line.id}`}
              aria-label={`Работа или материал строки ${index + 1}`}
              disabled={readOnly || catalogValuesReadOnly}
              value={line.description}
              onChange={(event) =>
                onUpdate(line.id, (current) => ({
                  ...current,
                  description: event.target.value,
                }))
              }
            />
          </Field>

          {line.lineType === "WORK" ? (
            <Field className="md:col-span-5" data-disabled={readOnly}>
              <FieldLabel htmlFor={`line-comment-${line.id}`}>
                Комментарий
              </FieldLabel>
              <Textarea
                id={`line-comment-${line.id}`}
                aria-label={`Комментарий ${index + 1}`}
                className="h-9 min-h-9 resize-y"
                disabled={readOnly}
                value={line.lineComment}
                onChange={(event) =>
                  onUpdate(line.id, (current) => ({
                    ...current,
                    lineComment: event.target.value,
                  }))
                }
              />
            </Field>
          ) : null}

          <Field className="md:col-span-2" data-disabled={readOnly}>
            <FieldLabel htmlFor={`line-quantity-${line.id}`}>
              Количество
            </FieldLabel>
            <InputGroup>
              <InputGroupAddon align="inline-start">
                <InputGroupButton
                  type="button"
                  size="icon-xs"
                  aria-label={`Уменьшить количество строки ${index + 1}`}
                  disabled={readOnly || line.quantity <= 1}
                  onClick={() =>
                    onUpdate(line.id, (current) => ({
                      ...current,
                      quantity: Math.max(1, current.quantity - 1),
                    }))
                  }
                >
                  <HugeiconsIcon icon={MinusSignIcon} />
                </InputGroupButton>
              </InputGroupAddon>
              <InputGroupInput
                id={`line-quantity-${line.id}`}
                aria-label={`Количество строки ${index + 1}`}
                inputMode="numeric"
                pattern="[0-9]*"
                disabled={readOnly}
                value={line.quantity}
                onChange={(event) => {
                  const value = event.target.value
                  if (/^\d*$/.test(value)) {
                    onUpdate(line.id, (current) => ({
                      ...current,
                      quantity: Math.max(1, Number(value) || 1),
                    }))
                  }
                }}
              />
              <InputGroupAddon align="inline-end">
                <InputGroupButton
                  type="button"
                  size="icon-xs"
                  aria-label={`Увеличить количество строки ${index + 1}`}
                  disabled={readOnly}
                  onClick={() =>
                    onUpdate(line.id, (current) => ({
                      ...current,
                      quantity: current.quantity + 1,
                    }))
                  }
                >
                  <HugeiconsIcon icon={Add01Icon} />
                </InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
          </Field>

          <Field className="md:col-span-2" data-disabled>
            <FieldLabel htmlFor={`line-unit-${line.id}`}>Единица</FieldLabel>
            <Input
              id={`line-unit-${line.id}`}
              aria-label={`Единица измерения строки ${index + 1}`}
              readOnly
              value={lineUnitLabel(line.unit)}
            />
          </Field>

          <Field className="md:col-span-3" data-disabled={readOnly}>
            <FieldLabel htmlFor={`line-price-${line.id}`}>Цена</FieldLabel>
            <Input
              id={`line-price-${line.id}`}
              aria-label={`Цена строки ${index + 1}`}
              inputMode="decimal"
              disabled={readOnly || catalogValuesReadOnly}
              value={line.unitPrice}
              onChange={(event) => {
                const value = event.target.value.replace(",", ".")
                if (value === "" || /^\d+(?:\.\d{0,2})?$/.test(value)) {
                  onReplace(line.id, (current) => {
                    const unitPrice = value || "0"
                    return {
                      ...current,
                      unitPrice,
                      lineTotal: calculateLineTotal(
                        unitPrice,
                        current.quantity
                      ),
                    }
                  })
                }
              }}
              onBlur={() => onUpdate(line.id, (current) => current)}
            />
          </Field>

          <Field className="md:col-span-5">
            <FieldLabel htmlFor={`line-total-${line.id}`}>Сумма</FieldLabel>
            <Input
              id={`line-total-${line.id}`}
              aria-label={`Сумма строки ${index + 1}`}
              readOnly
              value={line.lineTotal}
            />
          </Field>
        </FieldGroup>
      </CardContent>
    </Card>
  )
}
