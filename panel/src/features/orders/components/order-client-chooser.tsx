import { useEffect, useMemo, useState, type RefObject } from "react"
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
import {
  ClientCreateFields,
  ClientTypeField,
  type ClientCreateFieldsValue,
} from "@/features/clients/components/client-create-fields"
import {
  listOrderClients,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import {
  normalizeClientDisplayName,
  normalizeClientSearch,
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
      contactPerson: string | null
      email: string | null
      comment: string | null
      source: string | null
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

function emptyClientValue(
  clientType: OrderClientType
): ClientCreateFieldsValue {
  return {
    clientType,
    displayName: "",
    phone: "",
    contactPerson: "",
    email: "",
    comment: "",
    source: "",
  }
}

/**
 * Finds an existing rental client or collects the same fields for a new one.
 *
 * <p>The parent owns command submission; this component only emits a normalized choice and
 * never lets the browser select the responsible manager.</p>
 */
export function OrderClientChooser({
  accessToken,
  actorId,
  idPrefix,
  responsibleManagerDisplayName,
  portalContainer,
  initialClient = null,
  newClientCreationContext = "после успешного создания бронирования",
  onChange,
}: {
  accessToken: string
  actorId: string
  idPrefix: string
  responsibleManagerDisplayName: string
  portalContainer?: RefObject<HTMLDivElement | null>
  initialClient?: OrderClientSearchItem | null
  newClientCreationContext?: string
  onChange: (choice: OrderClientChoice | null) => void
}) {
  const initialType = initialClient?.type ?? "LEGAL_ENTITY"
  const [clientValue, setClientValue] = useState<ClientCreateFieldsValue>(() =>
    emptyClientValue(initialType)
  )
  const [clientComboboxOpen, setClientComboboxOpen] = useState(false)
  const [search, setSearch] = useState(initialClient?.displayName ?? "")
  const [selection, setSelection] = useState<ClientOption | null>(() =>
    initialClient
      ? {
          kind: "existing",
          value: initialClient.id,
          label: initialClient.displayName,
          client: initialClient,
        }
      : null
  )
  const normalizedSearch = normalizeClientSearch(search)
  const displayName = normalizeClientDisplayName(search)

  const clientsQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "clients",
      actorId,
      clientValue.clientType,
      normalizedSearch,
    ],
    queryFn: () =>
      listOrderClients({
        accessToken,
        type: clientValue.clientType,
        search: normalizeClientDisplayName(search),
        page: 0,
        size: 20,
      }),
    enabled: normalizedSearch.length >= 2,
  })
  const existingOptions = useMemo<ExistingClientOption[]>(
    () =>
      [
        ...(initialClient ? [initialClient] : []),
        ...(clientsQuery.data?.content ?? []),
      ]
        .filter(
          (client, index, clients) =>
            clients.findIndex((candidate) => candidate.id === client.id) ===
            index
        )
        .map((client) => ({
          kind: "existing",
          value: client.id,
          label: client.displayName,
          client,
        })),
    [clientsQuery.data?.content, initialClient]
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
        clientType: clientValue.clientType,
        displayName: selection.displayName,
        phone: clientValue.phone.trim(),
        contactPerson: clientValue.contactPerson.trim() || null,
        email: clientValue.email.trim() || null,
        comment: clientValue.comment.trim() || null,
        source: clientValue.source.trim() || null,
      })
      return
    }
    onChange(null)
  }, [clientValue, onChange, selection])

  function resetChoice(clientType = clientValue.clientType) {
    setSelection(null)
    setClientValue(emptyClientValue(clientType))
  }

  function selectNewClient() {
    if (!createOption) return

    setSelection(createOption)
    setSearch(createOption.displayName)
    setClientValue((current) => ({
      ...current,
      displayName: createOption.displayName,
    }))
    setClientComboboxOpen(false)
  }

  return (
    <>
      <ClientTypeField
        id={`${idPrefix}-client-type`}
        value={clientValue.clientType}
        onChange={(clientType) => {
          setSearch("")
          resetChoice(clientType)
        }}
      />

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
          onInputValueChange={(next) => {
            setSearch(next)
            if (
              selection &&
              normalizeClientSearch(next) !==
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
                        <span className="block text-xs text-muted-foreground">
                          {option.client.phone ?? "Телефон не указан"}
                        </span>
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
        <ClientCreateFields
          idPrefix={idPrefix}
          value={clientValue}
          responsibleManagerDisplayName={responsibleManagerDisplayName}
          showClientType={false}
          showDisplayName={false}
          onChange={setClientValue}
        />
      ) : null}
    </>
  )
}
