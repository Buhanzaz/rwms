import {
  useCallback,
  useRef,
  useState,
  type FormEvent,
  type RefObject,
} from "react"
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
import { FieldError, FieldGroup } from "@/components/ui/field"
import {
  getClient,
  CLIENTS_QUERY_KEY,
} from "@/features/clients/api/clients-api"
import {
  OrderClientChooser,
  type OrderClientChoice,
} from "@/features/orders/components/order-client-chooser"
import {
  createOrder,
  ORDERS_QUERY_KEY,
  type CreateOrderInput,
} from "@/features/orders/api/orders-api"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import type { OrderDetail } from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import {
  clientNeedsContactPerson,
  parseAdditionalContacts,
} from "@/features/clients/domain/clients"
import { OrderDeliveryFields } from "@/features/orders/components/order-delivery-fields"
import {
  emptyOrderDeliveryDraft,
  parseOrderDeliveryDraft,
  type OrderDeliveryErrors,
} from "@/features/orders/domain/order-delivery-draft"

export function CreateOrderDialog({
  open,
  initialClientId = null,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  initialClientId?: string | null
  onOpenChange: (open: boolean) => void
  onCreated: (order: OrderDetail) => void
}) {
  const contentRef = useRef<HTMLDivElement>(null)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        ref={contentRef}
        className="max-h-[90vh] w-[calc(100vw-2rem)] max-w-6xl overflow-y-auto sm:max-w-6xl"
      >
        <DialogHeader>
          <DialogTitle>Новое бронирование</DialogTitle>
          <DialogDescription>
            Выберите существующего клиента или явно подтвердите создание нового.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <CreateOrderDialogContent
            portalContainer={contentRef}
            initialClientId={initialClientId}
            onClose={() => onOpenChange(false)}
            onCreated={onCreated}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function CreateOrderDialogContent({
  portalContainer,
  initialClientId,
  onClose,
  onCreated,
}: {
  portalContainer: RefObject<HTMLDivElement | null>
  initialClientId: string | null
  onClose: () => void
  onCreated: (order: OrderDetail) => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [choice, setChoice] = useState<OrderClientChoice | null>(null)
  const [delivery, setDelivery] = useState(() => emptyOrderDeliveryDraft())
  const [deliveryErrors, setDeliveryErrors] = useState<OrderDeliveryErrors>({})
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const suggestedPhone = useRef("")
  const initialClientQuery = useQuery({
    queryKey: [...CLIENTS_QUERY_KEY, "detail", initialClientId],
    queryFn: () => getClient(accessToken!, initialClientId!),
    enabled: Boolean(accessToken && initialClientId),
  })
  const handleChoice = useCallback((next: OrderClientChoice | null) => {
    commandIdentity.current.reset()
    setChoice(next)
    setErrorText(null)
    const phone =
      next?.kind === "existing"
        ? (next.client.phone ?? "")
        : (next?.phone ?? "")
    setDelivery((current) => ({
      ...current,
      contactPhone:
        !current.contactPhone || current.contactPhone === suggestedPhone.current
          ? phone
          : current.contactPhone,
    }))
    suggestedPhone.current = phone
  }, [])

  const createMutation = useMutation({
    mutationFn: ({
      input,
      fingerprint,
    }: {
      input: CreateOrderInput
      fingerprint: string
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")

      return createOrder({
        accessToken,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
        input,
      })
    },
    onSuccess: async (order, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      await queryClient.invalidateQueries({ queryKey: ORDERS_QUERY_KEY })
      toast.success(`Бронирование ${order.number} создано.`)
      onCreated(order)
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось создать бронирование."
      )
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!choice) {
      setErrorText("Выберите клиента или действие создания.")
      return
    }
    if (choice.kind === "new" && !choice.phone) {
      setErrorText("Укажите телефон нового клиента.")
      return
    }
    if (
      choice.kind === "new" &&
      clientNeedsContactPerson(choice.clientType) &&
      !choice.contactPerson
    ) {
      setErrorText("Укажите основное контактное лицо нового клиента.")
      return
    }
    const parsedNewClientContacts =
      choice.kind === "new"
        ? parseAdditionalContacts(choice.additionalContacts)
        : null
    if (parsedNewClientContacts && !parsedNewClientContacts.contacts) {
      setErrorText("Проверьте дополнительные контакты нового клиента.")
      return
    }
    const parsedDelivery = parseOrderDeliveryDraft(delivery)
    setDeliveryErrors(parsedDelivery.errors)
    if (!parsedDelivery.input) {
      setErrorText("Проверьте контактный телефон заказа.")
      return
    }

    const input: CreateOrderInput =
      choice.kind === "existing"
        ? { clientId: choice.client.id, ...parsedDelivery.input }
        : {
            newClient: {
              clientType: choice.clientType,
              displayName: choice.displayName,
              phone: choice.phone,
              contactPerson: choice.contactPerson,
              email: choice.email,
              comment: choice.comment,
              source: choice.source,
              additionalContacts: parsedNewClientContacts!.contacts!,
            },
            ...parsedDelivery.input,
          }
    createMutation.mutate({ input, fingerprint: JSON.stringify(input) })
  }

  if (!accessToken || !currentUser) {
    return <FieldError>Сессия завершена.</FieldError>
  }
  if (initialClientId && initialClientQuery.isPending) {
    return <FieldError>Загружаем выбранного клиента…</FieldError>
  }
  if (initialClientId && initialClientQuery.isError) {
    return (
      <FieldError>
        {initialClientQuery.error instanceof Error
          ? initialClientQuery.error.message
          : "Не удалось загрузить выбранного клиента."}
      </FieldError>
    )
  }

  return (
    <form onSubmit={submit}>
      <FieldGroup>
        <OrderClientChooser
          accessToken={accessToken}
          actorId={currentUser.id}
          idPrefix="order"
          responsibleManagerDisplayName={
            currentUser.displayName || currentUser.id
          }
          portalContainer={portalContainer}
          initialClient={initialClientQuery.data ?? null}
          onChange={handleChoice}
        />

        <OrderDeliveryFields
          idPrefix="order"
          value={delivery}
          errors={deliveryErrors}
          disabled={createMutation.isPending}
          onChange={(next) => {
            commandIdentity.current.reset()
            setDelivery(next)
            setDeliveryErrors({})
            setErrorText(null)
          }}
        />

        {errorText ? <FieldError>{errorText}</FieldError> : null}

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button
            type="submit"
            disabled={
              choice === null ||
              (choice.kind === "new" && !choice.phone) ||
              createMutation.isPending
            }
          >
            {createMutation.isPending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {createMutation.isPending ? "Создание…" : "Создать бронирование"}
          </Button>
        </DialogFooter>
      </FieldGroup>
    </form>
  )
}
