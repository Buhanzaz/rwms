import { useRef, useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"
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
import { acceptUndamagedReturn } from "@/features/logistics/returns/api"
import type {
  MediaReference,
  ReturnAdditionalEquipment,
  ReturnDocument,
  ReturnMediaLine,
} from "@/features/logistics/returns/model"
import { logisticsReturnMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

type ReturnMediaLineDraft = {
  lineId: string
  lineNumber: number
  references: MediaReference[]
  additionalEquipment: Array<ReturnAdditionalEquipment & { key: string }>
}

function initialLines(document: ReturnDocument): ReturnMediaLineDraft[] {
  return document.lines.map((line) => ({
    lineId: line.id,
    lineNumber: line.lineNumber,
    references: [],
    additionalEquipment: [],
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

export function AcceptUndamagedDialog({
  accessToken,
  document,
  onOpenChange,
  onSuccess,
  onConflict,
}: {
  accessToken: string
  document: ReturnDocument
  onOpenChange: (open: boolean) => void
  onSuccess: (document: ReturnDocument) => void
  onConflict: (cause: unknown) => void
}) {
  const [lines, setLines] = useState<ReturnMediaLineDraft[]>(() =>
    initialLines(document)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const attempt = useRef<CommandAttempt | null>(null)
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "return-additional", document.warehouseId],
    queryFn: () =>
      getEquipmentItems(accessToken, { warehouseId: document.warehouseId }),
    enabled: Boolean(accessToken),
  })
  const furniture = (equipmentQuery.data ?? []).filter(
    (item) => item.active && item.category === "FURNITURE"
  )
  const mutation = useMutation({
    mutationFn: (command: {
      lines: ReturnMediaLine[]
      idempotencyKey: string
    }) =>
      acceptUndamagedReturn({
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

  function addAdditionalEquipment(lineId: string) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId
          ? {
              ...line,
              additionalEquipment: [
                ...line.additionalEquipment,
                { key: crypto.randomUUID(), equipmentId: "", quantity: 1 },
              ],
            }
          : line
      )
    )
  }

  function updateAdditionalEquipment(
    lineId: string,
    key: string,
    update: Partial<ReturnAdditionalEquipment>
  ) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId
          ? {
              ...line,
              additionalEquipment: line.additionalEquipment.map((item) =>
                item.key === key ? { ...item, ...update } : item
              ),
            }
          : line
      )
    )
  }

  function removeAdditionalEquipment(lineId: string, key: string) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId
          ? {
              ...line,
              additionalEquipment: line.additionalEquipment.filter(
                (item) => item.key !== key
              ),
            }
          : line
      )
    )
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const mediaIds = new Set<string>()
    const commandLines: ReturnMediaLine[] = []

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
      for (const equipment of line.additionalEquipment) {
        if (
          !equipment.equipmentId ||
          !Number.isSafeInteger(equipment.quantity) ||
          equipment.quantity < 1 ||
          equipmentIds.has(equipment.equipmentId)
        ) {
          setValidationError(
            "Дополнительная мебель должна быть выбрана один раз для каждой бытовки, с целым количеством не меньше одного."
          )
          return
        }
        equipmentIds.add(equipment.equipmentId)
      }
      commandLines.push({
        lineId: line.lineId,
        references: line.references,
        additionalEquipment: line.additionalEquipment.map(
          ({ equipmentId, quantity }) => ({ equipmentId, quantity })
        ),
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
            <DialogTitle>Принять возврат без сметы</DialogTitle>
            <DialogDescription>
              Загрузите фотографии осмотра для каждой строки возврата. Одна
              фотография хранится как один объект, а размеры обрабатывает
              media-service.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <FieldSet>
              <FieldLegend variant="label">Фотографии осмотра</FieldLegend>
              <FieldDescription>
                Подтвердить приёмку можно после завершения обработки хотя бы
                одной фотографии для каждой строки.
              </FieldDescription>
              <FieldGroup>
                {lines.map((line) => (
                  <Card key={line.lineId} size="sm">
                    <CardHeader>
                      <CardTitle>Строка {line.lineNumber}</CardTitle>
                      <CardDescription className="font-mono text-xs">
                        {line.lineId}
                      </CardDescription>
                    </CardHeader>
                    <CardContent>
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
                      <FieldSet className="mt-4">
                        <FieldLegend variant="label">
                          Дополнительная мебель
                        </FieldLegend>
                        <FieldDescription>
                          Добавьте мебель, приехавшую с бытовкой, которой нет в
                          текущем составе. После приёмки logistics-service
                          зафиксирует поступление на этот склад.
                        </FieldDescription>
                        <FieldGroup>
                          {line.additionalEquipment.map((equipment) => {
                            const availableFurniture = furniture.filter(
                              (item) =>
                                item.id === equipment.equipmentId ||
                                !line.additionalEquipment.some(
                                  (other) =>
                                    other.key !== equipment.key &&
                                    other.equipmentId === item.id
                                )
                            )
                            return (
                              <div
                                key={equipment.key}
                                className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_8rem_auto]"
                              >
                                <Field>
                                  <FieldLabel
                                    htmlFor={`return-extra-equipment-${equipment.key}`}
                                  >
                                    Мебель
                                  </FieldLabel>
                                  <Select
                                    value={equipment.equipmentId}
                                    disabled={mutation.isPending}
                                    onValueChange={(equipmentId) =>
                                      updateAdditionalEquipment(
                                        line.lineId,
                                        equipment.key,
                                        { equipmentId }
                                      )
                                    }
                                  >
                                    <SelectTrigger
                                      id={`return-extra-equipment-${equipment.key}`}
                                    >
                                      <SelectValue placeholder="Выберите мебель" />
                                    </SelectTrigger>
                                    <SelectContent>
                                      <SelectGroup>
                                        {availableFurniture.map((item) => (
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
                                <Field>
                                  <FieldLabel
                                    htmlFor={`return-extra-quantity-${equipment.key}`}
                                  >
                                    Количество
                                  </FieldLabel>
                                  <Input
                                    id={`return-extra-quantity-${equipment.key}`}
                                    type="number"
                                    min={1}
                                    step={1}
                                    disabled={mutation.isPending}
                                    value={equipment.quantity}
                                    onChange={(event) =>
                                      updateAdditionalEquipment(
                                        line.lineId,
                                        equipment.key,
                                        { quantity: Number(event.target.value) }
                                      )
                                    }
                                  />
                                </Field>
                                <Button
                                  type="button"
                                  className="self-end"
                                  size="sm"
                                  variant="outline"
                                  disabled={mutation.isPending}
                                  onClick={() =>
                                    removeAdditionalEquipment(
                                      line.lineId,
                                      equipment.key
                                    )
                                  }
                                >
                                  Удалить
                                </Button>
                              </div>
                            )
                          })}
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            disabled={
                              mutation.isPending || furniture.length === 0
                            }
                            onClick={() => addAdditionalEquipment(line.lineId)}
                          >
                            Добавить мебель
                          </Button>
                          {equipmentQuery.isError ? (
                            <FieldError>
                              Не удалось загрузить каталог мебели.
                            </FieldError>
                          ) : null}
                        </FieldGroup>
                      </FieldSet>
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
                  : "Не удалось принять возврат"}
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
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Принимается…" : "Принять без сметы"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
