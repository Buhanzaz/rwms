import { useRef, useState, type FormEvent } from "react"
import { useMutation } from "@tanstack/react-query"
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
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import {
  updateOrder,
  type OrderDeliveryInput,
} from "@/features/orders/api/orders-api"
import { OrderDeliveryFields } from "@/features/orders/components/order-delivery-fields"
import {
  parseOrderDeliveryDraft,
  type OrderDeliveryDraft,
  type OrderDeliveryErrors,
} from "@/features/orders/domain/order-delivery-draft"
import { isOrderDeliveryComplete } from "@/features/orders/domain/order-delivery-readiness"
import type { OrderDetail } from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { ApiError } from "@/lib/api-client"

function deliveryDraft(order: OrderDetail): OrderDeliveryDraft {
  return {
    contactPhone: order.contactPhone ?? order.client.phone ?? "",
    comment: order.comment ?? "",
  }
}

/**
 * Edits manager-owned order contact data without changing the selected client.
 */
export function OrderDeliveryDialog({
  open,
  order,
  onOpenChange,
  onUpdated,
  onConflict,
}: {
  open: boolean
  order: OrderDetail
  onOpenChange: (open: boolean) => void
  onUpdated: (order: OrderDetail) => void
  onConflict: () => void
}) {
  const contactComplete = isOrderDeliveryComplete(order)
  const title = contactComplete
    ? "Изменить данные заказа"
    : "Добавить данные заказа"

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Контактный телефон и комментарий сохраняются в заказе. Адрес,
            координаты и дополнительные контакты клиент укажет в представлении.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <OrderDeliveryDialogContent
            key={`${order.id}:${order.version}`}
            order={order}
            onClose={() => onOpenChange(false)}
            onUpdated={onUpdated}
            onConflict={onConflict}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function OrderDeliveryDialogContent({
  order,
  onClose,
  onUpdated,
  onConflict,
}: {
  order: OrderDetail
  onClose: () => void
  onUpdated: (order: OrderDetail) => void
  onConflict: () => void
}) {
  const { accessToken } = useOrdersModule()
  const initialDelivery = deliveryDraft(order)
  const [delivery, setDelivery] = useState<OrderDeliveryDraft>(initialDelivery)
  const [deliveryErrors, setDeliveryErrors] = useState<OrderDeliveryErrors>({})
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const deliveryUnchanged =
    JSON.stringify(delivery) === JSON.stringify(initialDelivery)

  const updateMutation = useMutation({
    mutationFn: ({
      expectedVersion,
      delivery: nextDelivery,
      fingerprint,
    }: {
      expectedVersion: number
      delivery: OrderDeliveryInput
      fingerprint: string
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")

      return updateOrder({
        accessToken,
        orderId: order.id,
        expectedVersion,
        clientId: order.client.id,
        delivery: nextDelivery,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      onUpdated(projection)
      toast.success("Данные заказа сохранены.")
      onClose()
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        onConflict()
        setErrorText(
          "Данные заказа изменились. Актуальные значения загружены с сервера."
        )
        return
      }

      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось сохранить данные заказа."
      )
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedDelivery = parseOrderDeliveryDraft(delivery)
    setDeliveryErrors(parsedDelivery.errors)
    if (!parsedDelivery.input) {
      setErrorText("Проверьте контактный телефон заказа.")
      return
    }
    if (deliveryUnchanged) {
      setErrorText("Измените данные заказа перед сохранением.")
      return
    }

    const fingerprint = JSON.stringify({
      operation: "update-order-details",
      orderId: order.id,
      expectedVersion: order.version,
      clientId: order.client.id,
      delivery: parsedDelivery.input,
    })
    updateMutation.mutate({
      expectedVersion: order.version,
      delivery: parsedDelivery.input,
      fingerprint,
    })
  }

  return (
    <form noValidate onSubmit={submit}>
      <FieldGroup>
        <OrderDeliveryFields
          idPrefix="order-delivery"
          value={delivery}
          errors={deliveryErrors}
          disabled={updateMutation.isPending}
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
            disabled={deliveryUnchanged || updateMutation.isPending}
          >
            {updateMutation.isPending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {updateMutation.isPending
              ? "Сохранение…"
              : "Сохранить данные заказа"}
          </Button>
        </DialogFooter>
      </FieldGroup>
    </form>
  )
}
