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
import { acceptUndamagedReturn } from "@/features/logistics/returns/api"
import type {
  ReturnDocument,
  ReturnMediaLine,
} from "@/features/logistics/returns/model"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

type MediaReferenceDraft = {
  key: string
  mediaId: string
  generation: string
}

type ReturnMediaLineDraft = {
  lineId: string
  lineNumber: number
  references: MediaReferenceDraft[]
}

function emptyReference(): MediaReferenceDraft {
  return {
    key: crypto.randomUUID(),
    mediaId: "",
    generation: "1",
  }
}

function initialLines(document: ReturnDocument): ReturnMediaLineDraft[] {
  return document.lines.map((line) => ({
    lineId: line.id,
    lineNumber: line.lineNumber,
    references: [emptyReference()],
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

  function updateReference(
    lineId: string,
    referenceKey: string,
    update: Partial<Pick<MediaReferenceDraft, "mediaId" | "generation">>
  ) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId
          ? {
              ...line,
              references: line.references.map((reference) =>
                reference.key === referenceKey
                  ? { ...reference, ...update }
                  : reference
              ),
            }
          : line
      )
    )
  }

  function addReference(lineId: string) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId && line.references.length < 20
          ? { ...line, references: [...line.references, emptyReference()] }
          : line
      )
    )
  }

  function removeReference(lineId: string, referenceKey: string) {
    setLines((current) =>
      current.map((line) =>
        line.lineId === lineId && line.references.length > 1
          ? {
              ...line,
              references: line.references.filter(
                (reference) => reference.key !== referenceKey
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
      const references = []
      for (const reference of line.references) {
        const mediaId = reference.mediaId.trim()
        const generation = Number(reference.generation)
        if (
          !UUID_PATTERN.test(mediaId) ||
          !Number.isSafeInteger(generation) ||
          generation < 1 ||
          mediaIds.has(mediaId)
        ) {
          setValidationError(
            "Для каждой строки укажите уникальные READY media UUID и generation не меньше 1."
          )
          return
        }
        mediaIds.add(mediaId)
        references.push({ mediaId, generation })
      }
      commandLines.push({ lineId: line.lineId, references })
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
              Укажите заранее подтверждённые media-service READY references для
              каждой server-issued строки. Панель не загружает и не привязывает
              фотографии самостоятельно.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <FieldSet>
              <FieldLegend variant="label">Фотографии осмотра</FieldLegend>
              <FieldDescription>
                Logistics-service проверит владельца, готовность и generation
                каждой ссылки до изменения состояния документа.
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
                        {line.references.map((reference, index) => {
                          const mediaInvalid =
                            reference.mediaId.length > 0 &&
                            !UUID_PATTERN.test(reference.mediaId.trim())
                          const generation = Number(reference.generation)
                          const generationInvalid =
                            !Number.isSafeInteger(generation) || generation < 1

                          return (
                            <Card key={reference.key} size="sm">
                              <CardHeader>
                                <CardTitle>
                                  Media reference {index + 1}
                                </CardTitle>
                                {line.references.length > 1 ? (
                                  <CardAction>
                                    <Button
                                      type="button"
                                      size="icon-sm"
                                      variant="outline"
                                      aria-label={`Удалить media reference ${index + 1} строки ${line.lineNumber}`}
                                      onClick={() =>
                                        removeReference(
                                          line.lineId,
                                          reference.key
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
                                  <Field data-invalid={mediaInvalid}>
                                    <FieldLabel
                                      htmlFor={`return-media-${line.lineId}-${reference.key}`}
                                    >
                                      Media UUID
                                    </FieldLabel>
                                    <Input
                                      id={`return-media-${line.lineId}-${reference.key}`}
                                      aria-label={`Media UUID · строка ${line.lineNumber} · ссылка ${index + 1}`}
                                      aria-invalid={mediaInvalid}
                                      required
                                      value={reference.mediaId}
                                      placeholder="00000000-0000-0000-0000-000000000000"
                                      onChange={(event) =>
                                        updateReference(
                                          line.lineId,
                                          reference.key,
                                          { mediaId: event.target.value }
                                        )
                                      }
                                    />
                                  </Field>
                                  <Field data-invalid={generationInvalid}>
                                    <FieldLabel
                                      htmlFor={`return-generation-${line.lineId}-${reference.key}`}
                                    >
                                      Generation
                                    </FieldLabel>
                                    <Input
                                      id={`return-generation-${line.lineId}-${reference.key}`}
                                      aria-label={`Generation · строка ${line.lineNumber} · ссылка ${index + 1}`}
                                      aria-invalid={generationInvalid}
                                      type="number"
                                      min={1}
                                      step={1}
                                      required
                                      value={reference.generation}
                                      onChange={(event) =>
                                        updateReference(
                                          line.lineId,
                                          reference.key,
                                          { generation: event.target.value }
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
                          disabled={line.references.length >= 20}
                          onClick={() => addReference(line.lineId)}
                        >
                          <HugeiconsIcon
                            icon={Add01Icon}
                            data-icon="inline-start"
                          />
                          Добавить media reference
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
