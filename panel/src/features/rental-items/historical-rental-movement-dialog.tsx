import {
  useCallback,
  useRef,
  useState,
  type FormEvent,
} from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
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
  CLIENTS_QUERY_KEY,
  createClient,
  createClientIdempotencyKey,
} from "@/features/clients/api/clients-api"
import {
  clientNeedsContactPerson,
  isValidClientPhone,
  normalizeClientDisplayName,
  parseAdditionalContacts,
} from "@/features/clients/domain/clients"
import {
  OrderClientChooser,
  type OrderClientChoice,
} from "@/features/orders/components/order-client-chooser"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  createHistoricalRentalMovement,
  createHistoricalRentalMovementIdempotencyKey,
  type HistoricalRentalMovementDocument,
  type HistoricalRentalMovementKind,
} from "@/features/rental-items/historical-rental-movement-api"

function localDateInputValue() {
  const today = new Date()
  const month = String(today.getMonth() + 1).padStart(2, "0")
  const day = String(today.getDate()).padStart(2, "0")
  return `${today.getFullYear()}-${month}-${day}`
}

function movementLabel(kind: HistoricalRentalMovementKind) {
  return kind === "SHIPMENT" ? "отгрузку" : "возврат"
}

function movementDateLabel(kind: HistoricalRentalMovementKind) {
  return kind === "SHIPMENT" ? "Дата отгрузки" : "Дата возврата"
}

function validateNewClient(choice: OrderClientChoice) {
  if (choice.kind !== "new") return null
  if (!normalizeClientDisplayName(choice.displayName)) {
    return "Укажите наименование или ФИО нового клиента."
  }
  if (!isValidClientPhone(choice.phone)) {
    return "Укажите корректный основной телефон нового клиента."
  }
  if (
    clientNeedsContactPerson(choice.clientType) &&
    !choice.contactPerson?.trim()
  ) {
    return "Укажите основное контактное лицо юридического лица."
  }
  if (!parseAdditionalContacts(choice.additionalContacts).contacts) {
    return "Проверьте дополнительные контакты нового клиента."
  }
  return null
}

/**
 * Collects one historical rental shipment or return without exposing a driver or route planner.
 * A new client is persisted first with its own retry key, then the logistics command is retried
 * with a stable, independent key until its response is known.
 */
export function HistoricalRentalMovementDialog({
  open,
  kind,
  rentalItem,
  accessToken,
  actorId,
  responsibleManagerDisplayName,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  kind: HistoricalRentalMovementKind
  rentalItem: RentalItemDto
  accessToken: string
  actorId: string
  responsibleManagerDisplayName: string
  onOpenChange: (open: boolean) => void
  onCreated: (document: HistoricalRentalMovementDocument) => void
}) {
  const queryClient = useQueryClient()
  const contentRef = useRef<HTMLDivElement>(null)
  const clientIdempotencyKey = useRef<string | null>(null)
  const movementIdempotencyKey = useRef<string | null>(null)
  const createdClientId = useRef<string | null>(null)
  const [choice, setChoice] = useState<OrderClientChoice | null>(null)
  const [occurredOn, setOccurredOn] = useState(localDateInputValue)
  const [errorText, setErrorText] = useState<string | null>(null)

  const movementMutation = useMutation({
    mutationFn: async () => {
      if (!choice) throw new Error("Выберите клиента или создание нового.")
      if (!occurredOn) throw new Error("Укажите дату операции.")

      const clientError = validateNewClient(choice)
      if (clientError) throw new Error(clientError)

      let clientId = choice.kind === "existing" ? choice.client.id : null
      if (choice.kind === "new" && !createdClientId.current) {
        clientIdempotencyKey.current ??= createClientIdempotencyKey()
        const contacts = parseAdditionalContacts(choice.additionalContacts)
        if (!contacts.contacts) {
          throw new Error("Проверьте дополнительные контакты нового клиента.")
        }
        const client = await createClient({
          accessToken,
          idempotencyKey: clientIdempotencyKey.current,
          input: {
            clientType: choice.clientType,
            displayName: normalizeClientDisplayName(choice.displayName),
            phone: choice.phone.trim(),
            contactPerson: choice.contactPerson,
            email: choice.email,
            comment: choice.comment,
            source: choice.source,
            additionalContacts: contacts.contacts,
          },
        })
        createdClientId.current = client.id
        clientId = client.id
      }
      if (choice.kind === "new") clientId = createdClientId.current
      if (!clientId) throw new Error("Не удалось определить клиента для операции.")

      movementIdempotencyKey.current ??=
        createHistoricalRentalMovementIdempotencyKey()
      return createHistoricalRentalMovement({
        accessToken,
        idempotencyKey: movementIdempotencyKey.current,
        input: {
          warehouseId: rentalItem.warehouseId,
          rentalItemId: rentalItem.id,
          expectedRentalItemVersion: rentalItem.version,
          clientId,
          kind,
          occurredOn,
        },
      })
    },
    onSuccess: async (document) => {
      await queryClient.invalidateQueries({ queryKey: CLIENTS_QUERY_KEY })
      toast.success(
        `Историческая ${movementLabel(kind)} создана и передана в логистику.`
      )
      onCreated(document)
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось создать историческую операцию аренды."
      )
    },
  })

  const handleChoice = useCallback((next: OrderClientChoice | null) => {
    clientIdempotencyKey.current = null
    movementIdempotencyKey.current = null
    createdClientId.current = null
    setChoice(next)
    setErrorText(null)
  }, [])

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setErrorText(null)
    if (!choice) {
      setErrorText("Выберите клиента или действие создания.")
      return
    }
    const clientError = validateNewClient(choice)
    if (clientError) {
      setErrorText(clientError)
      return
    }
    if (!occurredOn) {
      setErrorText("Укажите дату операции.")
      return
    }
    movementMutation.mutate()
  }

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen && movementMutation.isPending) return
    onOpenChange(nextOpen)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        ref={contentRef}
        className="max-h-[90vh] w-[calc(100vw-2rem)] max-w-3xl overflow-y-auto"
      >
        <DialogHeader>
          <DialogTitle>
            Создать {movementLabel(kind)} задним числом
          </DialogTitle>
          <DialogDescription>
            Будет создан реальный документ логистики для бытовки {rentalItem.number}.
            Водитель и маршрут для такой операции не указываются.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <form noValidate onSubmit={submit}>
            <FieldGroup>
              <OrderClientChooser
                accessToken={accessToken}
                actorId={actorId}
                idPrefix={`historical-rental-${kind.toLowerCase()}`}
                responsibleManagerDisplayName={responsibleManagerDisplayName}
                portalContainer={contentRef}
                newClientCreationContext="до создания исторической операции"
                onChange={handleChoice}
              />

              <Field>
                <FieldLabel htmlFor="historical-rental-occurred-on">
                  {movementDateLabel(kind)}
                </FieldLabel>
                <Input
                  id="historical-rental-occurred-on"
                  type="date"
                  value={occurredOn}
                  disabled={movementMutation.isPending}
                  required
                  onChange={(event) => {
                    movementIdempotencyKey.current = null
                    setOccurredOn(event.target.value)
                    setErrorText(null)
                  }}
                />
                <FieldDescription>
                  Можно указать сегодняшний или прошедший день; складская дата
                  будет проверена сервером.
                </FieldDescription>
              </Field>

              {kind === "SHIPMENT" ? (
                <FieldDescription>
                  Если у бытовки есть ремонт, капремонт или незапущенное
                  перемещение, сервер зафиксирует автоматическое закрытие с
                  комментарием «Автоматически закрыто в связи с отгрузкой.»
                </FieldDescription>
              ) : (
                <FieldDescription>
                  Возврат поступит в обычный процесс приёмки: после него можно
                  оформить смету и ремонт.
                </FieldDescription>
              )}

              {errorText ? <FieldError>{errorText}</FieldError> : null}

              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  disabled={movementMutation.isPending}
                  onClick={() => onOpenChange(false)}
                >
                  Отмена
                </Button>
                <Button
                  type="submit"
                  disabled={choice === null || movementMutation.isPending}
                >
                  {movementMutation.isPending ? (
                    <HugeiconsIcon
                      icon={Loading03Icon}
                      data-icon="inline-start"
                      className="animate-spin"
                    />
                  ) : null}
                  {movementMutation.isPending
                    ? "Создание…"
                    : `Создать ${movementLabel(kind)}`}
                </Button>
              </DialogFooter>
            </FieldGroup>
          </form>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
