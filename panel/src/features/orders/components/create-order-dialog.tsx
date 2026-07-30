import {
  useCallback,
  useRef,
  useState,
  type FormEvent,
  type RefObject,
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
import { FieldError, FieldGroup } from "@/components/ui/field"
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

export function CreateOrderDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (order: OrderDetail) => void
}) {
  const contentRef = useRef<HTMLDivElement>(null)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent ref={contentRef} className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Новое бронирование</DialogTitle>
          <DialogDescription>
            Выберите существующего клиента или явно подтвердите создание нового.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <CreateOrderDialogContent
            portalContainer={contentRef}
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
  onClose,
  onCreated,
}: {
  portalContainer: RefObject<HTMLDivElement | null>
  onClose: () => void
  onCreated: (order: OrderDetail) => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [choice, setChoice] = useState<OrderClientChoice | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const handleChoice = useCallback((next: OrderClientChoice | null) => {
    commandIdentity.current.reset()
    setChoice(next)
    setErrorText(null)
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

    const input: CreateOrderInput =
      choice.kind === "existing"
        ? { clientId: choice.client.id }
        : {
            newClient: {
              clientType: choice.clientType,
              displayName: choice.displayName,
              phone: choice.phone,
              email: choice.email,
            },
          }
    createMutation.mutate({ input, fingerprint: JSON.stringify(input) })
  }

  if (!accessToken || !currentUser) {
    return <FieldError>Сессия завершена.</FieldError>
  }

  return (
    <form onSubmit={submit}>
      <FieldGroup>
        <OrderClientChooser
          accessToken={accessToken}
          actorId={currentUser.id}
          idPrefix="order"
          portalContainer={portalContainer}
          onChange={handleChoice}
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
