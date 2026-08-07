import { useRef, useState, type FormEvent } from "react"
import { useMutation } from "@tanstack/react-query"

import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
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
  FieldError,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { startReturnEstimates } from "@/features/logistics/returns/api"
import type {
  MediaReference,
  ReturnDocument,
  ReturnEstimateLine,
} from "@/features/logistics/returns/model"
import { logisticsReturnMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import { useIsMobile } from "@/hooks/use-mobile"

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

type ReturnEstimateLineDraft = ReturnEstimateLine & {
  lineNumber: number
}

function initialLines(document: ReturnDocument): ReturnEstimateLineDraft[] {
  return document.lines.map((line) => ({
    lineId: line.id,
    lineNumber: line.lineNumber,
    references: [],
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

export function StartReturnEstimatesDialog({
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
  const isMobile = useIsMobile()

  if (isMobile) {
    return (
      <MobileAppRequiredDialog
        open={true}
        onOpenChange={onOpenChange}
        operation="Создание сметы"
      />
    )
  }

  return (
    <StartReturnEstimatesDialogForm
      accessToken={accessToken}
      document={document}
      onOpenChange={onOpenChange}
      onSuccess={onSuccess}
      onConflict={onConflict}
    />
  )
}

function StartReturnEstimatesDialogForm({
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
  const [lines, setLines] = useState<ReturnEstimateLineDraft[]>(() =>
    initialLines(document)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const attempt = useRef<CommandAttempt | null>(null)
  const mutation = useMutation({
    mutationFn: (command: {
      lines: ReturnEstimateLine[]
      idempotencyKey: string
    }) =>
      startReturnEstimates({
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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const commandLines: ReturnEstimateLine[] = []
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
      commandLines.push({
        lineId: line.lineId,
        references: line.references,
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
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать сметы</DialogTitle>
            <DialogDescription>
              Для каждой бытовки будет создана отдельная новая смета. Укажите
              фотографии осмотра; мебель добавляется уже внутри нужной сметы.
            </DialogDescription>
          </DialogHeader>

          <FieldGroup>
            <FieldSet>
              <FieldLegend>Фотографии осмотра</FieldLegend>
              <div className="grid gap-3">
                {lines.map((line) => (
                  <Card key={line.lineId} size="sm">
                    <CardHeader>
                      <CardTitle>Бытовка · строка {line.lineNumber}</CardTitle>
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
                    </CardContent>
                  </Card>
                ))}
              </div>
            </FieldSet>
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error && !isConflict(mutation.error) ? (
              <FieldError>
                {mutation.error instanceof Error
                  ? mutation.error.message
                  : "Не удалось создать сметы"}
              </FieldError>
            ) : null}
          </FieldGroup>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={mutation.isPending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Создаём…" : "Создать сметы"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
