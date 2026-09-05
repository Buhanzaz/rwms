import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
  InputGroupText,
} from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import {
  cabinRentalPricesKey,
  equipmentRentalPricingKey,
  getEquipmentRentalPricing,
  rentalPricingSettingsKey,
  updateEquipmentRentalPrice,
  validMonthlyRentalPrice,
  type EquipmentRentalPrice,
  type UpdateEquipmentRentalPrice,
} from "@/features/assistant/api/rental-pricing-api"
import { ApiError } from "@/lib/api-client"

export function EquipmentPricesPage() {
  const { accessToken, currentUser } = useAuth()
  const allowed =
    currentUser?.globalRole === "SYSTEM_ADMIN" ||
    currentUser?.globalRole === "WMS_ADMIN"
  if (!allowed)
    return (
      <Alert variant="destructive">
        <AlertTitle>Нет доступа к ценам наполнения</AlertTitle>
        <AlertDescription>
          Настройка доступна глобальному администратору.
        </AlertDescription>
      </Alert>
    )
  return <EquipmentPricesSettings accessToken={accessToken ?? ""} />
}

export function EquipmentPricesSettings({
  accessToken,
}: {
  accessToken: string
}) {
  const client = useQueryClient()
  const mutation = useMutation({
    mutationFn: (request: UpdateEquipmentRentalPrice) =>
      updateEquipmentRentalPrice(request),
    onMutate: () =>
      client.cancelQueries({
        queryKey: equipmentRentalPricingKey,
        exact: true,
      }),
    onSuccess: async (settings) => {
      await client.cancelQueries({
        queryKey: equipmentRentalPricingKey,
        exact: true,
      })
      client.setQueryData(equipmentRentalPricingKey, settings)
      await Promise.all([
        client.invalidateQueries({
          queryKey: rentalPricingSettingsKey,
          exact: true,
        }),
        client.invalidateQueries({ queryKey: cabinRentalPricesKey }),
      ])
      toast.success("Цена наполнения сохранена.")
    },
    onError: (error) => toast.error(error.message),
  })
  const query = useQuery({
    queryKey: equipmentRentalPricingKey,
    queryFn: ({ signal }) => getEquipmentRentalPricing(accessToken, signal),
    enabled: Boolean(accessToken.trim()),
    refetchInterval: mutation.isPending ? false : 30_000,
    refetchOnWindowFocus: true,
  })
  const conflict =
    mutation.error instanceof ApiError && mutation.error.status === 409
  async function refresh() {
    const result = await query.refetch()
    if (!result.isError) mutation.reset()
  }
  return (
    <section
      aria-labelledby="equipment-prices-title"
      className="flex max-w-4xl flex-col gap-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 id="equipment-prices-title" className="text-xl font-semibold">
            Стоимость наполнения
          </h1>
          <p className="max-w-2xl text-sm text-muted-foreground">
            Аренда мебели: цена за одну единицу в месяц. Тариф общий для всех
            объектов.
          </p>
        </div>
        <Button
          variant="outline"
          onClick={() => void refresh()}
          disabled={
            !accessToken.trim() || query.isFetching || mutation.isPending
          }
        >
          {query.isFetching ? "Обновляем цены…" : "Обновить цены"}
        </Button>
      </div>
      {!accessToken.trim() ? (
        <Alert variant="destructive">
          <AlertTitle>Не получен токен доступа</AlertTitle>
          <AlertDescription>
            Войдите заново, чтобы загрузить стоимость наполнения.
          </AlertDescription>
        </Alert>
      ) : (
        <>
          {query.isError && (
            <Alert variant="destructive">
              <AlertTitle>Не удалось обновить стоимость наполнения</AlertTitle>
              <AlertDescription>
                {query.error.message} Повторите обновление. Ваш ввод сохранён.
              </AlertDescription>
            </Alert>
          )}
          {mutation.isError && (
            <Alert variant="destructive">
              <AlertTitle>
                {conflict ? "Цены изменились" : "Цена не сохранена"}
              </AlertTitle>
              <AlertDescription>
                {conflict
                  ? "Обновите цены и проверьте изменения перед сохранением. Ваш ввод сохранён."
                  : mutation.error.message}
              </AlertDescription>
            </Alert>
          )}
          {!query.data && !query.isError && (
            <div role="status" aria-label="Загружаем стоимость наполнения">
              <Skeleton className="h-48 w-full" />
            </div>
          )}
          {query.data && (
            <Card>
              <CardHeader>
                <CardTitle>Мебель и наполнение</CardTitle>
                <CardDescription>
                  Список из справочника мебели, включая позиции без остатков.
                  Новая позиция получает цену 0 ₽.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <FieldGroup>
                  {query.data.items.map((item) => (
                    <EquipmentPriceRow
                      key={item.equipmentId}
                      item={item}
                      disabled={mutation.isPending || query.isError || conflict}
                      onSave={(monthlyPriceRubles) =>
                        mutation.mutateAsync({
                          accessToken,
                          equipmentId: item.equipmentId,
                          expectedVersion: query.data!.version,
                          monthlyPriceRubles,
                        })
                      }
                    />
                  ))}
                  {query.data.items.length === 0 && (
                    <FieldDescription>
                      Добавьте мебель в справочник наполнения бытовок — здесь
                      появятся строки для цен.
                    </FieldDescription>
                  )}
                </FieldGroup>
              </CardContent>
            </Card>
          )}
        </>
      )}
    </section>
  )
}

function EquipmentPriceRow({
  item,
  disabled,
  onSave,
}: {
  item: EquipmentRentalPrice
  disabled: boolean
  onSave: (amount: string) => Promise<unknown>
}) {
  const [draft, setDraft] = useState<{ value: string; original: string }>()
  const [error, setError] = useState<string>()
  const [saving, setSaving] = useState(false)
  const id = `equipment-price-${item.equipmentId}`
  const value = draft?.value ?? item.monthlyPriceRubles
  const changedElsewhere = Boolean(
    draft && draft.original !== item.monthlyPriceRubles
  )
  const dirty = value !== item.monthlyPriceRubles
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (disabled || saving || changedElsewhere || !dirty) return
    if (!validMonthlyRentalPrice(value)) {
      setError(
        "Укажите целую сумму от 0 до 9223372036854775807 ₽. Пустое поле не означает 0 ₽."
      )
      document.getElementById(id)?.focus()
      return
    }
    setSaving(true)
    try {
      await onSave(value)
      setDraft(undefined)
      setError(undefined)
    } catch {
      // Keep the failed draft; the parent displays the actual server error.
    } finally {
      setSaving(false)
    }
  }
  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <Field data-invalid={Boolean(error)} data-disabled={disabled || saving}>
        <FieldLabel htmlFor={id}>
          {item.name}{" "}
          {!item.active && <Badge variant="secondary">Неактивна</Badge>}
        </FieldLabel>
        <div className="flex flex-wrap items-center gap-2">
          <InputGroup className="min-w-52 flex-1">
            <InputGroupInput
              id={id}
              inputMode="numeric"
              autoComplete="off"
              value={value}
              disabled={disabled || saving}
              aria-invalid={Boolean(error)}
              aria-describedby={[
                `${id}-unit`,
                error && `${id}-error`,
                changedElsewhere && `${id}-conflict`,
              ]
                .filter(Boolean)
                .join(" ")}
              onChange={(event) => {
                setDraft({
                  value: event.target.value,
                  original: draft?.original ?? item.monthlyPriceRubles,
                })
                setError(undefined)
              }}
            />
            <InputGroupAddon align="inline-end">
              <InputGroupText id={`${id}-unit`}>₽ / шт. / мес.</InputGroupText>
            </InputGroupAddon>
          </InputGroup>
          <Button
            type="submit"
            variant="outline"
            size="sm"
            disabled={disabled || saving || changedElsewhere || !dirty}
            aria-label={`Сохранить цену: ${item.name}`}
          >
            {saving ? "Сохраняем…" : "Сохранить цену"}
          </Button>
        </div>
        {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
        {changedElsewhere && (
          <FieldDescription id={`${id}-conflict`}>
            Эта цена уже изменена: сейчас {item.monthlyPriceRubles} ₽ за шт. в
            месяц. Ваш ввод не сохранён.
          </FieldDescription>
        )}
        {draft && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="self-start"
            disabled={disabled || saving}
            aria-label={`Сбросить правку: ${item.name}`}
            onClick={() => {
              setDraft(undefined)
              setError(undefined)
            }}
          >
            {changedElsewhere
              ? "Использовать актуальную цену"
              : "Сбросить правку"}
          </Button>
        )}
      </Field>
    </form>
  )
}
