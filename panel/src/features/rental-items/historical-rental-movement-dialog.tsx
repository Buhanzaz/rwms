import { useCallback, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
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
  getClient,
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
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  createHistoricalRentalMovement,
  createHistoricalRentalMovementIdempotencyKey,
  type HistoricalRentalMovementDocument,
  type HistoricalRentalMovementKind,
  updateHistoricalRentalShipment,
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
 * Collects or corrects one historical rental movement without exposing route planning. A shipment
 * may retain one selected driver as audit evidence or explicitly keep that driver unknown; neither
 * form creates driver work. A new client is persisted first with its own retry key, then the
 * logistics command is retried with a stable, independent key until its response is known.
 * Corrections keep the existing shipment identity and never replay physical effects.
 */
export function HistoricalRentalMovementDialog({
  open,
  kind,
  rentalItem,
  accessToken,
  actorId,
  responsibleManagerDisplayName,
  existingShipment = null,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  kind: HistoricalRentalMovementKind
  rentalItem: RentalItemDto
  accessToken: string
  actorId: string
  responsibleManagerDisplayName: string
  existingShipment?: {
    id: string
    version: number
    clientId: string | null
    driverSnapshot: string | null
    driverWorkerId: string | null
    scheduledDate: string | null
  } | null
  onOpenChange: (open: boolean) => void
  onCreated: (document: HistoricalRentalMovementDocument) => void
}) {
  const queryClient = useQueryClient()
  const contentRef = useRef<HTMLDivElement>(null)
  const clientIdempotencyKey = useRef<string | null>(null)
  const movementIdempotencyKey = useRef<string | null>(null)
  const createdClientId = useRef<string | null>(null)
  const [choice, setChoice] = useState<OrderClientChoice | null>(null)
  const [occurredOn, setOccurredOn] = useState(
    () => existingShipment?.scheduledDate ?? localDateInputValue()
  )
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(
    () =>
      existingShipment?.driverSnapshot && existingShipment.driverWorkerId
        ? {
            id: existingShipment.driverWorkerId,
            name: existingShipment.driverSnapshot,
          }
        : null
  )
  const [errorText, setErrorText] = useState<string | null>(null)
  const isEditing = kind === "SHIPMENT" && existingShipment !== null
  const existingClientQuery = useQuery({
    queryKey: [
      ...CLIENTS_QUERY_KEY,
      "detail",
      existingShipment?.clientId ?? "none",
    ],
    queryFn: () => getClient(accessToken, existingShipment!.clientId!),
    enabled: Boolean(open && isEditing && existingShipment?.clientId),
  })

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
      if (!clientId)
        throw new Error("Не удалось определить клиента для операции.")

      const driverSnapshot =
        kind === "SHIPMENT" ? driver?.name.trim() || null : null
      const driverWorkerId = driverSnapshot ? (driver?.id ?? null) : null
      movementIdempotencyKey.current ??=
        createHistoricalRentalMovementIdempotencyKey()
      if (isEditing) {
        return updateHistoricalRentalShipment({
          accessToken,
          documentId: existingShipment!.id,
          idempotencyKey: movementIdempotencyKey.current,
          input: {
            expectedVersion: existingShipment!.version,
            rentalItemId: rentalItem.id,
            clientId,
            driverSnapshot,
            driverWorkerId,
            occurredOn,
          },
        })
      }
      return createHistoricalRentalMovement({
        accessToken,
        idempotencyKey: movementIdempotencyKey.current,
        input: {
          warehouseId: rentalItem.warehouseId,
          rentalItemId: rentalItem.id,
          expectedRentalItemVersion: rentalItem.version,
          clientId,
          driverSnapshot,
          driverWorkerId,
          kind,
          occurredOn,
        },
      })
    },
    onSuccess: async (document) => {
      await queryClient.invalidateQueries({ queryKey: CLIENTS_QUERY_KEY })
      toast.success(
        isEditing
          ? "Историческая отгрузка обновлена."
          : `Историческая ${movementLabel(kind)} создана и передана в логистику.`
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
            {isEditing ? "Изменить" : "Создать"} {movementLabel(kind)} задним
            числом
          </DialogTitle>
          <DialogDescription>
            {isEditing
              ? `Будут исправлены клиент, водитель и дата существующей отгрузки бытовки ${rentalItem.number}. Физическая отгрузка повторно не выполняется.`
              : `Будет создан реальный документ логистики для бытовки ${rentalItem.number}. Можно привязать водителя; маршрут и задание водителя не создаются.`}
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <form noValidate onSubmit={submit}>
            <FieldGroup>
              {isEditing &&
              existingShipment?.clientId &&
              existingClientQuery.isLoading ? (
                <FieldDescription>Загрузка текущего клиента…</FieldDescription>
              ) : (
                <OrderClientChooser
                  key={existingClientQuery.data?.id ?? "historical-client"}
                  accessToken={accessToken}
                  actorId={actorId}
                  idPrefix={`historical-rental-${kind.toLowerCase()}`}
                  responsibleManagerDisplayName={responsibleManagerDisplayName}
                  portalContainer={contentRef}
                  initialClient={existingClientQuery.data ?? null}
                  newClientCreationContext="до сохранения исторической операции"
                  onChange={handleChoice}
                />
              )}
              {existingClientQuery.isError ? (
                <FieldError>
                  Текущий клиент не загрузился. Выберите клиента заново.
                </FieldError>
              ) : null}

              {kind === "SHIPMENT" ? (
                <>
                  <LogisticsDriverPicker
                    accessToken={accessToken}
                    id="historical-rental-driver"
                    required={false}
                    unknownLabel="Неизвестен"
                    value={driver}
                    warehouseId={rentalItem.warehouseId}
                    onChange={(nextDriver) => {
                      movementIdempotencyKey.current = null
                      setDriver(nextDriver)
                      setErrorText(null)
                    }}
                  />
                  <FieldDescription>
                    Если водитель известен, выберите его. Иначе оставьте
                    «Неизвестен» — это не помешает сохранить отгрузку.
                  </FieldDescription>
                </>
              ) : null}

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
                  disabled={
                    choice === null ||
                    movementMutation.isPending ||
                    existingClientQuery.isLoading
                  }
                >
                  {movementMutation.isPending ? (
                    <HugeiconsIcon
                      icon={Loading03Icon}
                      data-icon="inline-start"
                      className="animate-spin"
                    />
                  ) : null}
                  {movementMutation.isPending
                    ? isEditing
                      ? "Сохранение…"
                      : "Создание…"
                    : isEditing
                      ? "Сохранить отгрузку"
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
