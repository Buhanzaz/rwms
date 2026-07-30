import {
  useEffect,
  useMemo,
  useState,
  type RefObject,
} from "react"
import { useQuery } from "@tanstack/react-query"
import { Add01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

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
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  listOrderClients,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import {
  normalizeClientDisplayName,
  normalizeClientSearch,
  ORDER_CLIENT_TYPES,
  ORDER_CLIENT_TYPE_LABELS,
  type OrderClientSearchItem,
  type OrderClientType,
} from "@/features/orders/domain/orders"

export type OrderClientChoice =
  | {
      kind: "existing"
      client: OrderClientSearchItem
    }
  | {
      kind: "new"
      clientType: OrderClientType
      displayName: string
      phone: string
      email: string | null
    }

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

export function OrderClientChooser({
  accessToken,
  actorId,
  idPrefix,
  portalContainer,
  newClientCreationContext = "после успешного создания бронирования",
  onChange,
}: {
  accessToken: string
  actorId: string
  idPrefix: string
  portalContainer?: RefObject<HTMLDivElement | null>
  newClientCreationContext?: string
  onChange: (choice: OrderClientChoice | null) => void
}) {
  const [clientType, setClientType] =
    useState<OrderClientType>("LEGAL_ENTITY")
  const [clientComboboxOpen, setClientComboboxOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [selection, setSelection] = useState<ClientOption | null>(null)
  const [phone, setPhone] = useState("")
  const [email, setEmail] = useState("")
  const normalizedSearch = normalizeClientSearch(search)
  const displayName = normalizeClientDisplayName(search)

  const clientsQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "clients",
      actorId,
      clientType,
      normalizedSearch,
    ],
    queryFn: () =>
      listOrderClients({
        accessToken,
        type: clientType,
        search: normalizeClientDisplayName(search),
        page: 0,
        size: 20,
      }),
    enabled: normalizedSearch.length >= 2,
  })
  const existingOptions = useMemo<ExistingClientOption[]>(
    () =>
      (clientsQuery.data?.content ?? []).map((client) => ({
        kind: "existing",
        value: client.id,
        label: client.displayName,
        client,
      })),
    [clientsQuery.data?.content]
  )
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

  useEffect(() => {
    if (selection?.kind === "existing") {
      onChange({ kind: "existing", client: selection.client })
      return
    }
    if (selection?.kind === "create") {
      onChange({
        kind: "new",
        clientType,
        displayName: selection.displayName,
        phone: phone.trim(),
        email: email.trim() || null,
      })
      return
    }
    onChange(null)
  }, [clientType, email, onChange, phone, selection])

  function resetChoice() {
    setSelection(null)
    setPhone("")
    setEmail("")
  }

  function selectNewClient() {
    if (!createOption) return

    setSelection(createOption)
    setSearch(createOption.displayName)
    setClientComboboxOpen(false)
  }

  return (
    <>
      <Field>
        <FieldLabel htmlFor={`${idPrefix}-client-type`}>
          Тип клиента
        </FieldLabel>
        <Select
          value={clientType}
          onValueChange={(value) => {
            setClientType(value as OrderClientType)
            resetChoice()
          }}
        >
          <SelectTrigger id={`${idPrefix}-client-type`} className="w-full">
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

      <Field data-invalid={clientsQuery.isError || undefined}>
        <FieldLabel htmlFor={`${idPrefix}-client-search`}>Клиент</FieldLabel>
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
              resetChoice()
            }
          }}
          onValueChange={(option) => {
            if (!option) {
              resetChoice()
              return
            }

            setSelection(option)
            setSearch(
              option.kind === "existing"
                ? option.client.displayName
                : option.displayName
            )
          }}
        >
          <ComboboxInput
            id={`${idPrefix}-client-search`}
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
                      <span className="min-w-0">
                        <span className="block truncate">{option.label}</span>
                        {option.client.phone ? (
                          <span className="block text-xs text-muted-foreground">
                            {option.client.phone}
                          </span>
                        ) : null}
                      </span>
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
              ? `Новый клиент «${selection.displayName}» будет создан в базе ${newClientCreationContext}.`
              : "Введите имя, название, телефон или email, затем выберите клиента либо создайте нового."}
        </FieldDescription>
      </Field>

      {selection?.kind === "create" ? (
        <>
          <Field>
            <FieldLabel htmlFor={`${idPrefix}-client-phone`}>
              Телефон
            </FieldLabel>
            <Input
              id={`${idPrefix}-client-phone`}
              type="tel"
              value={phone}
              required
              autoComplete="tel"
              placeholder="+7 999 000-00-00"
              onChange={(event) => setPhone(event.target.value)}
            />
            <FieldDescription>
              Обязателен и используется для поиска существующего клиента.
            </FieldDescription>
          </Field>
          <Field>
            <FieldLabel htmlFor={`${idPrefix}-client-email`}>
              Email
            </FieldLabel>
            <Input
              id={`${idPrefix}-client-email`}
              type="email"
              value={email}
              autoComplete="email"
              placeholder="client@example.ru"
              onChange={(event) => setEmail(event.target.value)}
            />
            <FieldDescription>Необязательное поле.</FieldDescription>
          </Field>
        </>
      ) : null}
    </>
  )
}
