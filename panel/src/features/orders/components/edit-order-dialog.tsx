import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  Combobox,
  ComboboxCollection,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox"
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import {
  listOrderClients,
  ORDERS_QUERY_KEY,
  updateOrder,
} from "@/features/orders/api/orders-api"
import {
  normalizeClientDisplayName,
  normalizeClientSearch,
  ORDER_CLIENT_TYPES,
  ORDER_CLIENT_TYPE_LABELS,
  type OrderClientSearchItem,
  type OrderClientType,
  type OrderDetail,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { ApiError } from "@/lib/api-client"

type ExistingClientOption = {
  value: string
  label: string
  client: OrderClientSearchItem
}

export function EditOrderDialog({
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
  const contentRef = useRef<HTMLDivElement>(null)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent ref={contentRef} className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Редактировать заказ</DialogTitle>
          <DialogDescription>
            Для черновика можно изменить клиента, выбрав уже существующую
            карточку.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <EditOrderDialogContent
            key={`${order.id}:${order.version}`}
            order={order}
            portalContainer={contentRef}
            onClose={() => onOpenChange(false)}
            onUpdated={onUpdated}
            onConflict={onConflict}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function EditOrderDialogContent({
  order,
  portalContainer,
  onClose,
  onUpdated,
  onConflict,
}: {
  order: OrderDetail
  portalContainer: React.RefObject<HTMLDivElement | null>
  onClose: () => void
  onUpdated: (order: OrderDetail) => void
  onConflict: () => void
}) {
  const { accessToken, currentUser } = useOrdersModule()
  const [clientType, setClientType] = useState<OrderClientType>(
    order.client.type
  )
  const [clientComboboxOpen, setClientComboboxOpen] = useState(false)
  const [search, setSearch] = useState(order.client.displayName)
  const [selection, setSelection] = useState<ExistingClientOption | null>({
    value: order.client.id,
    label: order.client.displayName,
    client: order.client,
  })
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const normalizedSearch = normalizeClientSearch(search)

  const clientsQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "clients",
      currentUser?.id ?? "unknown-user",
      clientType,
      normalizedSearch,
    ],
    queryFn: () =>
      listOrderClients({
        accessToken: accessToken!,
        type: clientType,
        search: normalizeClientDisplayName(search),
        page: 0,
        size: 20,
      }),
    enabled: Boolean(accessToken) && normalizedSearch.length >= 2,
  })
  const existingOptions = useMemo<ExistingClientOption[]>(
    () =>
      (clientsQuery.data?.content ?? []).map((client) => ({
        value: client.id,
        label: client.displayName,
        client,
      })),
    [clientsQuery.data?.content]
  )
  const selectedClientUnchanged = selection?.client.id === order.client.id

  const updateMutation = useMutation({
    mutationFn: ({
      clientId,
      expectedVersion,
      fingerprint,
    }: {
      clientId: string
      expectedVersion: number
      fingerprint: string
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")

      return updateOrder({
        accessToken,
        orderId: order.id,
        expectedVersion,
        clientId,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      onUpdated(projection)
      toast.success("Клиент заказа изменён.")
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
        error instanceof Error ? error.message : "Не удалось изменить заказ."
      )
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selection) {
      setErrorText("Выберите существующего клиента.")
      return
    }
    if (selectedClientUnchanged) {
      setErrorText("Выберите клиента, отличающегося от текущего.")
      return
    }

    const fingerprint = `update-client:${order.id}:${order.version}:${selection.client.id}`
    updateMutation.mutate({
      clientId: selection.client.id,
      expectedVersion: order.version,
      fingerprint,
    })
  }

  return (
    <form onSubmit={submit}>
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="edit-order-client-type">Тип клиента</FieldLabel>
          <Select
            value={clientType}
            onValueChange={(value) => {
              commandIdentity.current.reset()
              setClientType(value as OrderClientType)
              setSearch("")
              setSelection(null)
              setErrorText(null)
            }}
          >
            <SelectTrigger id="edit-order-client-type" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {ORDER_CLIENT_TYPES.map((type) => (
                  <SelectItem key={type} value={type}>
                    {ORDER_CLIENT_TYPE_LABELS[type]}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>

        <Field data-invalid={clientsQuery.isError || errorText !== null}>
          <FieldLabel htmlFor="edit-order-client-search">Клиент</FieldLabel>
          <Combobox<ExistingClientOption>
            items={existingOptions}
            value={selection}
            open={clientComboboxOpen}
            inputValue={search}
            itemToStringLabel={(option) => option.label}
            itemToStringValue={(option) => option.value}
            isItemEqualToValue={(left, right) => left.value === right.value}
            onOpenChange={setClientComboboxOpen}
            onInputValueChange={(value) => {
              if (value !== search) commandIdentity.current.reset()
              setSearch(value)
              if (
                selection &&
                normalizeClientSearch(value) !==
                  normalizeClientSearch(selection.client.displayName)
              ) {
                setSelection(null)
              }
              setErrorText(null)
            }}
            onValueChange={(option) => {
              commandIdentity.current.reset()
              if (!option) return

              setSelection(option)
              setSearch(option.client.displayName)
              setErrorText(null)
            }}
          >
            <ComboboxInput
              id="edit-order-client-search"
              placeholder="Например, ООО Петров"
              autoComplete="off"
              showClear
            />
            <ComboboxContent portalContainer={portalContainer}>
              <ComboboxEmpty>
                {clientsQuery.isFetching
                  ? "Поиск клиентов…"
                  : clientsQuery.isError
                    ? "Не удалось выполнить поиск клиентов"
                    : normalizedSearch.length < 2
                      ? "Введите минимум два символа"
                      : "Клиенты не найдены"}
              </ComboboxEmpty>
              <ComboboxList>
                <ComboboxGroup
                  data-testid="edit-order-client-search-results"
                  items={existingOptions}
                >
                  <ComboboxCollection>
                    {(option: ExistingClientOption) => (
                      <ComboboxItem key={option.value} value={option}>
                        {option.label}
                      </ComboboxItem>
                    )}
                  </ComboboxCollection>
                </ComboboxGroup>
              </ComboboxList>
            </ComboboxContent>
          </Combobox>
          {clientsQuery.isFetching ? (
            <FieldDescription role="status">
              Проверяем существующих клиентов…
            </FieldDescription>
          ) : clientsQuery.isError ? (
            <FieldError>
              {clientsQuery.error instanceof Error
                ? clientsQuery.error.message
                : "Не удалось выполнить поиск клиентов."}
            </FieldError>
          ) : null}
          <FieldDescription>
            {selectedClientUnchanged
              ? "Выберите другого существующего клиента для изменения заказа."
              : selection
                ? `В заказе будет указан клиент «${selection.client.displayName}».`
                : "Введите минимум два символа и выберите клиента из выдачи."}
          </FieldDescription>
        </Field>

        {errorText ? <FieldError>{errorText}</FieldError> : null}

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button
            type="submit"
            disabled={
              selection === null ||
              selectedClientUnchanged ||
              updateMutation.isPending
            }
          >
            {updateMutation.isPending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {updateMutation.isPending ? "Сохранение…" : "Сохранить изменения"}
          </Button>
        </DialogFooter>
      </FieldGroup>
    </form>
  )
}
