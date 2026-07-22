import { useState, type RefObject } from "react"
import { useQuery } from "@tanstack/react-query"

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
  listOrderClients,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import {
  normalizeClientDisplayName,
  normalizeClientSearch,
  type OrderClientSearchItem,
} from "@/features/orders/domain/orders"

export type RentalClientSelection = OrderClientSearchItem

export function RentalClientPicker({
  accessToken,
  disabled = false,
  idPrefix,
  portalContainer,
  value,
  onChange,
}: {
  accessToken: string
  disabled?: boolean
  idPrefix: string
  portalContainer?: RefObject<HTMLDivElement | null>
  value: RentalClientSelection | null
  onChange: (next: RentalClientSelection | null) => void
}) {
  const [search, setSearch] = useState(value?.displayName ?? "")
  const normalizedSearch = normalizeClientSearch(search)
  const clientsQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "clients",
      "logistics-picker",
      normalizedSearch,
    ],
    queryFn: () =>
      listOrderClients({
        accessToken,
        search: normalizeClientDisplayName(search),
        page: 0,
        size: normalizedSearch ? 50 : 5,
      }),
    enabled: !disabled && Boolean(accessToken),
  })
  const clients = clientsQuery.data?.content ?? []

  return (
    <Field data-invalid={clientsQuery.isError || undefined}>
      <FieldLabel htmlFor={`${idPrefix}-client`}>Контрагент</FieldLabel>
      <Combobox<RentalClientSelection>
        items={clients}
        value={value}
        inputValue={search}
        itemToStringLabel={(client) => client.displayName}
        itemToStringValue={(client) => client.id}
        isItemEqualToValue={(left, right) => left.id === right.id}
        disabled={disabled}
        onInputValueChange={(next) => {
          setSearch(next)
          if (
            value &&
            normalizeClientSearch(next) !==
              normalizeClientSearch(value.displayName)
          ) {
            onChange(null)
          }
        }}
        onValueChange={(next) => {
          onChange(next)
          setSearch(next?.displayName ?? "")
        }}
      >
        <ComboboxInput
          id={`${idPrefix}-client`}
          placeholder="Начните вводить имя или название"
          autoComplete="off"
          showClear
        />
        <ComboboxContent portalContainer={portalContainer}>
          <ComboboxEmpty>
            {clientsQuery.isFetching
              ? "Поиск контрагентов…"
              : clientsQuery.isError
                ? "Не удалось загрузить контрагентов"
                : normalizedSearch
                  ? "Контрагенты не найдены"
                  : "Недавних контрагентов пока нет"}
          </ComboboxEmpty>
          <ComboboxList>
            <ComboboxGroup items={clients}>
              <ComboboxCollection>
                {(client: RentalClientSelection) => (
                  <ComboboxItem key={client.id} value={client}>
                    {client.displayName}
                  </ComboboxItem>
                )}
              </ComboboxCollection>
            </ComboboxGroup>
          </ComboboxList>
        </ComboboxContent>
      </Combobox>
      {clientsQuery.isFetching ? (
        <FieldDescription>Ищем контрагентов…</FieldDescription>
      ) : null}
      {clientsQuery.isError ? (
        <FieldError>
          {clientsQuery.error instanceof Error
            ? clientsQuery.error.message
            : "Не удалось загрузить контрагентов."}
        </FieldError>
      ) : null}
    </Field>
  )
}
