import { useRef, useState } from "react"
import { useMutation } from "@tanstack/react-query"

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
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { acceptUndamagedReturn } from "@/features/logistics/returns/api"
import type {
  MediaReference,
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
}

function initialLines(document: ReturnDocument): ReturnMediaLineDraft[] {
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
      commandLines.push({ lineId: line.lineId, references: line.references })
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
            <DialogTitle>Принять возврат без повреждений</DialogTitle>
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
              {mutation.isPending ? "Принимается…" : "Подтвердить приёмку"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
