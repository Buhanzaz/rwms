import { useRef, useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { getEquipmentItems } from "@/api/equipment-api"
import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
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
  FieldLegend,
  FieldSet,
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
import { requestReturnEstimate } from "@/features/logistics/returns/api"
import type {
  MediaReference,
  ReturnDocument,
  ReturnShortageLine,
} from "@/features/logistics/returns/model"
import { logisticsReturnMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import { useIsMobile } from "@/hooks/use-mobile"

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

type ShortageDraft = {
  key: string
  equipmentId: string
  missingQuantity: string
}

type ReturnShortageLineDraft = {
  lineId: string
  lineNumber: number
  references: MediaReference[]
  shortages: ShortageDraft[]
}

function emptyShortage(): ShortageDraft {
  return {
    key: crypto.randomUUID(),
    equipmentId: "",
    missingQuantity: "1",
  }
}

function initialLines(document: ReturnDocument): ReturnShortageLineDraft[] {
  return document.lines.map((line) => ({
    lineId: line.id,
    lineNumber: line.lineNumber,
    references: [],
    shortages: [emptyShortage()],
  }))
}

function isConflict(cause: unknown) {
  return (
    typeof cause === "object" &&
    cause !== null &&
    "status" in cause &&
    cause.status === 409
  )
}

type RequestEstimateDialogProps = {
  accessToken: string
  document: ReturnDocument
  onOpenChange: (open: boolean) => void
  onSuccess: (document: ReturnDocument) => void
  onConflict: (cause: unknown) => void
}

export function RequestEstimateDialog(props: RequestEstimateDialogProps) {
  const isMobile = useIsMobile()

  if (isMobile) {
    return (
      <MobileAppRequiredDialog
        open={true}
        onOpenChange={props.onOpenChange}
        operation="Создание сметы"
      />
    )
  }

  return <RequestEstimateDialogForm {...props} />
}

function RequestEstimateDialogForm({
  accessToken,
  document,
  onOpenChange,
  onSuccess,
  onConflict,
}: RequestEstimateDialogProps) {
  const [lines, setLines] = useState<ReturnShortageLineDraft[]>(() =>
    initialLines(document)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const attempt = useRef<CommandAttempt | null>(null)
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "return-shortages", document.warehouseId],
    queryFn: () =>
      getEquipmentItems(accessToken, { warehouseId: document.warehouseId }),
    enabled: Boolean(accessToken),
  })
  const equipmentItems = (equipmentQuery.data ?? []).filter(
    (item) => item.active
  )
  const mutation = useMutation({
    mutationFn: (command: {
      lines: ReturnShortageLine[]
      idempotencyKey: string
    }) =>
      requestReturnEstimate({
        accessToken,
        documentId: document.id,
        expectedVersion: document.version,
        ...command,
      }),
    onSuccess,
    onError: (cause) => {
      if (isConflict(cause)) onConflict(cause)
    },
  })

  function updateReferences(lineId: string, references: MediaReference[]) {
    setLines((current) =>
      current.map((line) =>
        line.lineId !== lineId ||
        (line.references.length === references.length &&
          line.references.every(
            (reference, index) =>
              reference.mediaId === references[index]?.mediaId &&
              reference.generation === references[index]?.generation
          ))
          ? line
          : { ...line, references }
      )
    )
  }

  function updateShortage(
    lineId: string,
    shortageKey: string,
    update: Partial<Pick<ShortageDraft, "equipmentId" | "missingQuantity">>
  ) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId
          ? {
              ...line,
              shortages: line.shortages.map((shortage) =>
                shortage.key === shortageKey
                  ? { ...shortage, ...update }
                  : shortage
              ),
            }
          : line
      )
    )
  }

  function addShortage(lineId: string) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId && line.shortages.length < 100
          ? { ...line, shortages: [...line.shortages, emptyShortage()] }
          : line
      )
    )
  }

  function removeShortage(lineId: string, shortageKey: string) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId && line.shortages.length > 1
          ? {
              ...line,
              shortages: line.shortages.filter(
                (shortage) => shortage.key !== shortageKey
              ),
            }
          : line
      )
    )
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const commandLines: ReturnShortageLine[] = []
    const mediaIds = new Set<string>()

    for (const line of lines) {
      if (line.references.length === 0) {
        setValidationError(
          "Добавьте хотя бы одну готовую фотографию осмотра для каждой строки."
        )
        return
      }
      for (const reference of line.references) {
        if (mediaIds.has(reference.mediaId)) {
          setValidationError(
            "Одна фотография не может относиться сразу к нескольким строкам возврата."
          )
          return
        }
        mediaIds.add(reference.mediaId)
      }
      const equipmentIds = new Set<string>()
      const shortages = []
      for (const shortage of line.shortages) {
        const equipmentId = shortage.equipmentId.trim()
        const missingQuantity = Number(shortage.missingQuantity)
        if (
          !equipmentItems.some((item) => item.id === equipmentId) ||
          !Number.isSafeInteger(missingQuantity) ||
          missingQuantity < 1 ||
          equipmentIds.has(equipmentId)
        ) {
          setValidationError(
            "Для каждой строки выберите уникальное оборудование и укажите целое недостающее количество не меньше 1."
          )
          return
        }
        equipmentIds.add(equipmentId)
        shortages.push({ equipmentId, missingQuantity })
      }
      commandLines.push({
        lineId: line.lineId,
        references: line.references,
        shortages,
      })
    }

    setValidationError(null)
    const signature = JSON.stringify({
      documentId: document.id,
      expectedVersion: document.version,
      lines: commandLines,
    })
    const idempotencyKey =
      attempt.current?.signature === signature
        ? attempt.current.idempotencyKey
        : crypto.randomUUID()
    attempt.current = { signature, idempotencyKey }
    mutation.mutate({ lines: commandLines, idempotencyKey })
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать смету</DialogTitle>
            <DialogDescription>
              Загрузите фотографии осмотра и зафиксируйте недостающее
              оборудование для каждой server-issued строки. Logistics-service
              проверит принадлежность готовых фотографий, выполнит settlement и
              передаст факт в maintenance-service.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <FieldSet>
              <FieldLegend variant="label">
                Недостающее оборудование
              </FieldLegend>
              <FieldDescription>
                Выберите недостающее оборудование по названию из справочника
                текущего склада.
              </FieldDescription>
              {equipmentQuery.isError ? (
                <FieldError>
                  Не удалось загрузить справочник оборудования.
                </FieldError>
              ) : null}
              {!equipmentQuery.isPending &&
              !equipmentQuery.isError &&
              equipmentItems.length === 0 ? (
                <FieldError>
                  В текущем складе нет доступного оборудования.
                </FieldError>
              ) : null}
              <FieldGroup>
                {lines.map((line) => (
                  <Card key={line.lineId} size="sm">
                    <CardHeader>
                      <CardTitle>Строка {line.lineNumber}</CardTitle>
                    </CardHeader>
                    <CardContent>
                      <FieldGroup>
                        <ServiceOwnerPhotos
                          accessToken={accessToken}
                          owner={logisticsReturnMediaOwner(
                            document.id,
                            line.lineId,
                            document.warehouseId
                          )}
                          readOnly={mutation.isPending}
                          maxItems={20}
                          title={`Фотографии строки ${line.lineNumber}`}
                          onReadyReferencesChange={(references) =>
                            updateReferences(line.lineId, references)
                          }
                        />
                        {line.shortages.map((shortage, index) => {
                          const equipmentInvalid =
                            shortage.equipmentId.length > 0 &&
                            !equipmentItems.some(
                              (item) => item.id === shortage.equipmentId
                            )
                          const missingQuantity = Number(
                            shortage.missingQuantity
                          )
                          const quantityInvalid =
                            !Number.isSafeInteger(missingQuantity) ||
                            missingQuantity < 1

                          return (
                            <Card key={shortage.key} size="sm">
                              <CardHeader>
                                <CardTitle>Позиция {index + 1}</CardTitle>
                                {line.shortages.length > 1 ? (
                                  <CardAction>
                                    <Button
                                      type="button"
                                      size="icon-sm"
                                      variant="outline"
                                      aria-label={`Удалить позицию ${index + 1} строки ${line.lineNumber}`}
                                      onClick={() =>
                                        removeShortage(
                                          line.lineId,
                                          shortage.key
                                        )
                                      }
                                    >
                                      <HugeiconsIcon
                                        icon={Delete02Icon}
                                        data-icon="inline-start"
                                      />
                                    </Button>
                                  </CardAction>
                                ) : null}
                              </CardHeader>
                              <CardContent>
                                <FieldGroup>
                                  <Field data-invalid={equipmentInvalid}>
                                    <FieldLabel
                                      htmlFor={`return-equipment-${line.lineId}-${shortage.key}`}
                                    >
                                      Оборудование
                                    </FieldLabel>
                                    <Select
                                      value={shortage.equipmentId}
                                      disabled={
                                        mutation.isPending ||
                                        equipmentQuery.isPending ||
                                        equipmentQuery.isError
                                      }
                                      onValueChange={(equipmentId) =>
                                        updateShortage(
                                          line.lineId,
                                          shortage.key,
                                          { equipmentId }
                                        )
                                      }
                                    >
                                      <SelectTrigger
                                        id={`return-equipment-${line.lineId}-${shortage.key}`}
                                        aria-label={`Оборудование · строка ${line.lineNumber} · позиция ${index + 1}`}
                                        aria-invalid={equipmentInvalid}
                                      >
                                        <SelectValue placeholder="Выберите оборудование" />
                                      </SelectTrigger>
                                      <SelectContent>
                                        <SelectGroup>
                                          {equipmentItems
                                            .filter(
                                              (item) =>
                                                item.id ===
                                                  shortage.equipmentId ||
                                                !line.shortages.some(
                                                  (other) =>
                                                    other.key !==
                                                      shortage.key &&
                                                    other.equipmentId ===
                                                      item.id
                                                )
                                            )
                                            .map((item) => (
                                              <SelectItem
                                                key={item.id}
                                                value={item.id}
                                              >
                                                {item.name}
                                              </SelectItem>
                                            ))}
                                        </SelectGroup>
                                      </SelectContent>
                                    </Select>
                                  </Field>
                                  <Field data-invalid={quantityInvalid}>
                                    <FieldLabel
                                      htmlFor={`return-quantity-${line.lineId}-${shortage.key}`}
                                    >
                                      Недостающее количество
                                    </FieldLabel>
                                    <Input
                                      id={`return-quantity-${line.lineId}-${shortage.key}`}
                                      aria-label={`Недостающее количество · строка ${line.lineNumber} · позиция ${index + 1}`}
                                      aria-invalid={quantityInvalid}
                                      type="number"
                                      min={1}
                                      step={1}
                                      required
                                      value={shortage.missingQuantity}
                                      onChange={(event) =>
                                        updateShortage(
                                          line.lineId,
                                          shortage.key,
                                          {
                                            missingQuantity: event.target.value,
                                          }
                                        )
                                      }
                                    />
                                  </Field>
                                </FieldGroup>
                              </CardContent>
                            </Card>
                          )
                        })}
                        <Button
                          type="button"
                          variant="outline"
                          disabled={line.shortages.length >= 100}
                          onClick={() => addShortage(line.lineId)}
                        >
                          <HugeiconsIcon
                            icon={Add01Icon}
                            data-icon="inline-start"
                          />
                          Добавить позицию
                        </Button>
                      </FieldGroup>
                    </CardContent>
                  </Card>
                ))}
              </FieldGroup>
            </FieldSet>
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error && !isConflict(mutation.error) ? (
              <FieldError>
                {mutation.error instanceof Error
                  ? mutation.error.message
                  : "Не удалось создать смету"}
              </FieldError>
            ) : null}
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
              type="submit"
              disabled={
                mutation.isPending ||
                equipmentQuery.isPending ||
                equipmentQuery.isError ||
                equipmentItems.length === 0
              }
            >
              {mutation.isPending ? "Создаётся…" : "Создать смету"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
