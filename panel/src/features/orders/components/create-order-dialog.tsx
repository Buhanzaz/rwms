import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon, Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  Combobox,
  ComboboxContent,
  ComboboxCollection,
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
import {
  createOrder,
  listOrderClients,
  ORDERS_QUERY_KEY,
  type CreateOrderInput,
} from "@/features/orders/api/orders-api"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
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

type ExistingClientOption = {
  kind: "existing"
  value: string
  label: string
  client: OrderClientSearchItem
}

type CreateClientOption = {
  kind: "create"
  value: string
  label: string
  displayName: string
}

type ClientOption = ExistingClientOption | CreateClientOption

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
          <DialogTitle>Создать новый заказ</DialogTitle>
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
  portalContainer: React.RefObject<HTMLDivElement | null>
  onClose: () => void
  onCreated: (order: OrderDetail) => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [clientType, setClientType] = useState<OrderClientType>("LEGAL_ENTITY")
  const [clientComboboxOpen, setClientComboboxOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [selection, setSelection] = useState<ClientOption | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const normalizedSearch = normalizeClientSearch(search)
  const displayName = normalizeClientDisplayName(search)

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
  const existingOptions = useMemo<ExistingClientOption[]>(() => {
    return (clientsQuery.data?.content ?? []).map((client) => ({
      kind: "existing",
      value: client.id,
      label: client.displayName,
      client,
    }))
  }, [clientsQuery.data?.content])
  const exactMatch =
    clientsQuery.isSuccess &&
    existingOptions.some(
      (option) =>
        normalizeClientSearch(option.client.displayName) === normalizedSearch
    )
  const createOption = useMemo<CreateClientOption | null>(() => {
    if (displayName.length === 0 || exactMatch) return null

    return {
      kind: "create",
      value: `create:${normalizedSearch}`,
      label: `Создать нового клиента «${displayName}»`,
      displayName,
    }
  }, [displayName, exactMatch, normalizedSearch])
  const selectedCreateMatchesExisting =
    selection?.kind === "create" && exactMatch

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
      toast.success(`Заказ ${order.number} создан.`)
      onCreated(order)
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error ? error.message : "Не удалось создать заказ."
      )
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selection) {
      setErrorText("Выберите клиента или действие создания.")
      return
    }
    if (selectedCreateMatchesExisting) {
      setErrorText("Найден существующий клиент. Выберите его из выдачи.")
      return
    }

    const input: CreateOrderInput =
      selection.kind === "existing"
        ? { clientId: selection.client.id }
        : {
            newClient: {
              clientType,
              displayName: selection.displayName,
            },
          }
    createMutation.mutate({ input, fingerprint: JSON.stringify(input) })
  }

  function selectNewClient() {
    if (!createOption) return

    commandIdentity.current.reset()
    setSelection(createOption)
    setSearch(createOption.displayName)
    setErrorText(null)
    setClientComboboxOpen(false)
  }

  return (
    <form onSubmit={submit}>
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="order-client-type">Тип клиента</FieldLabel>
          <Select
            value={clientType}
            onValueChange={(value) => {
              setClientType(value as OrderClientType)
              setSelection(null)
              setErrorText(null)
              commandIdentity.current.reset()
            }}
          >
            <SelectTrigger id="order-client-type" className="w-full">
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

        <Field
          data-invalid={
            clientsQuery.isError || (errorText !== null && selection === null)
          }
        >
          <FieldLabel htmlFor="order-client-search">Клиент</FieldLabel>
          <Combobox<ClientOption>
            items={existingOptions}
            value={selection}
            open={clientComboboxOpen}
            inputValue={search}
            itemToStringLabel={(option) =>
              option.kind === "create" ? option.displayName : option.label
            }
            itemToStringValue={(option) => option.value}
            isItemEqualToValue={(left, right) => left.value === right.value}
            onOpenChange={setClientComboboxOpen}
            onInputValueChange={(value) => {
              if (value !== search) commandIdentity.current.reset()
              setSearch(value)
              if (
                selection &&
                normalizeClientSearch(value) !==
                  normalizeClientSearch(
                    selection.kind === "existing"
                      ? selection.client.displayName
                      : selection.displayName
                  )
              ) {
                setSelection(null)
              }
              setErrorText(null)
            }}
            onValueChange={(option) => {
              commandIdentity.current.reset()
              if (!option) {
                return
              }

              setSelection(option)
              setSearch(
                option.kind === "existing"
                  ? option.client.displayName
                  : option.displayName
              )
              setErrorText(null)
            }}
          >
            <ComboboxInput
              id="order-client-search"
              placeholder="Например, ООО Петров"
              autoComplete="off"
              showClear
            />
            <ComboboxContent portalContainer={portalContainer}>
              <Button
                data-testid="create-client-inline"
                type="button"
                variant={selection?.kind === "create" ? "secondary" : "ghost"}
                size="sm"
                className="m-1 w-[calc(100%-0.5rem)] justify-start"
                disabled={createOption === null}
                onClick={selectNewClient}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                {createOption?.label ??
                  (displayName.length === 0
                    ? "Введите название нового клиента"
                    : "Клиент уже найден")}
              </Button>
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
                  data-testid="client-search-results"
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
            {selectedCreateMatchesExisting
              ? "Найден существующий клиент. Выберите его из выдачи."
              : selection?.kind === "create"
                ? `Новый клиент «${selection.displayName}» будет создан в базе после успешного создания заказа.`
                : "Введите название, затем выберите клиента из выдачи или нажмите «Создать нового клиента»."}
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
              selectedCreateMatchesExisting ||
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
            {createMutation.isPending ? "Создание…" : "Создать заказ"}
          </Button>
        </DialogFooter>
      </FieldGroup>
    </form>
  )
}
