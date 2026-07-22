import { useRef, useState } from "react"
import { useMutation } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
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
import { requestReturnEstimate } from "@/features/logistics/returns/api"
import type {
  ReturnDocument,
  ReturnShortageLine,
} from "@/features/logistics/returns/model"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

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

export function RequestEstimateDialog({
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
  const [lines, setLines] = useState<ReturnShortageLineDraft[]>(() =>
    initialLines(document)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const attempt = useRef<CommandAttempt | null>(null)
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

    for (const line of lines) {
      const equipmentIds = new Set<string>()
      const shortages = []
      for (const shortage of line.shortages) {
        const equipmentId = shortage.equipmentId.trim()
        const missingQuantity = Number(shortage.missingQuantity)
        if (
          !UUID_PATTERN.test(equipmentId) ||
          !Number.isSafeInteger(missingQuantity) ||
          missingQuantity < 1 ||
          equipmentIds.has(equipmentId)
        ) {
          setValidationError(
            "Для каждой строки укажите уникальные equipment UUID и целое недостающее количество не меньше 1."
          )
          return
        }
        equipmentIds.add(equipmentId)
        shortages.push({ equipmentId, missingQuantity })
      }
      commandLines.push({ lineId: line.lineId, shortages })
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
              Зафиксируйте недостающее оборудование для каждой server-issued
              строки. Logistics-service сам выполнит settlement и передаст факт
              в maintenance-service.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <FieldSet>
              <FieldLegend variant="label">
                Недостающее оборудование
              </FieldLegend>
              <FieldDescription>
                Справочник оборудования не входит в публичный return contract;
                используйте только подтверждённые equipment ID.
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
                      <FieldGroup>
                        {line.shortages.map((shortage, index) => {
                          const equipmentInvalid =
                            shortage.equipmentId.length > 0 &&
                            !UUID_PATTERN.test(shortage.equipmentId.trim())
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
                                      Equipment UUID
                                    </FieldLabel>
                                    <Input
                                      id={`return-equipment-${line.lineId}-${shortage.key}`}
                                      aria-label={`Equipment UUID · строка ${line.lineNumber} · позиция ${index + 1}`}
                                      aria-invalid={equipmentInvalid}
                                      required
                                      value={shortage.equipmentId}
                                      placeholder="00000000-0000-0000-0000-000000000000"
                                      onChange={(event) =>
                                        updateShortage(
                                          line.lineId,
                                          shortage.key,
                                          { equipmentId: event.target.value }
                                        )
                                      }
                                    />
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
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Создаётся…" : "Создать смету"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
