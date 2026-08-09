import { useEffect } from "react"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import type {
  OrderDeliveryDraft,
  OrderDeliveryErrors,
} from "@/features/orders/domain/order-delivery-draft"

export function OrderDeliveryFields({
  idPrefix,
  value,
  errors = {},
  disabled = false,
  onChange,
}: {
  idPrefix: string
  value: OrderDeliveryDraft
  errors?: OrderDeliveryErrors
  disabled?: boolean
  onChange: (value: OrderDeliveryDraft) => void
}) {
  const firstErrorKey = (
    [
      "deliveryAddress",
      "latitude",
      "longitude",
      "contactPhone",
      "acceptableDeliveryDates",
    ] as const
  ).find((key) => Boolean(errors[key]))
  useEffect(() => {
    if (!firstErrorKey) return
    const suffix =
      firstErrorKey === "acceptableDeliveryDates"
        ? "delivery-date-0"
        : firstErrorKey === "contactPhone"
          ? "contact-phone"
          : firstErrorKey === "deliveryAddress"
            ? "delivery-address"
            : firstErrorKey
    document.getElementById(`${idPrefix}-${suffix}`)?.focus()
  }, [errors, firstErrorKey, idPrefix])

  function patch(next: Partial<OrderDeliveryDraft>) {
    onChange({ ...value, ...next })
  }

  return (
    <FieldSet>
      <FieldLegend>Доставка и приёмка</FieldLegend>
      <FieldDescription>
        Адрес, координаты, контакт и допустимые даты сохраняются в заказе.
      </FieldDescription>
      <FieldGroup>
        <Field data-invalid={Boolean(errors.deliveryAddress) || undefined}>
          <FieldLabel htmlFor={`${idPrefix}-delivery-address`}>
            Адрес доставки
          </FieldLabel>
          <Input
            id={`${idPrefix}-delivery-address`}
            name={`${idPrefix}-deliveryAddress`}
            value={value.deliveryAddress}
            disabled={disabled}
            required
            maxLength={1_000}
            aria-invalid={Boolean(errors.deliveryAddress)}
            placeholder="Город, улица, дом, ориентир"
            autoComplete="street-address"
            onChange={(event) => patch({ deliveryAddress: event.target.value })}
          />
          {errors.deliveryAddress ? (
            <FieldError>{errors.deliveryAddress}</FieldError>
          ) : null}
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field data-invalid={Boolean(errors.latitude) || undefined}>
            <FieldLabel htmlFor={`${idPrefix}-latitude`}>Широта</FieldLabel>
            <Input
              id={`${idPrefix}-latitude`}
              name={`${idPrefix}-latitude`}
              type="number"
              inputMode="decimal"
              min={-90}
              max={90}
              step="any"
              value={value.latitude}
              disabled={disabled}
              required
              aria-invalid={Boolean(errors.latitude)}
              placeholder="59.9343"
              autoComplete="off"
              onChange={(event) => patch({ latitude: event.target.value })}
            />
            {errors.latitude ? (
              <FieldError>{errors.latitude}</FieldError>
            ) : null}
          </Field>
          <Field data-invalid={Boolean(errors.longitude) || undefined}>
            <FieldLabel htmlFor={`${idPrefix}-longitude`}>Долгота</FieldLabel>
            <Input
              id={`${idPrefix}-longitude`}
              name={`${idPrefix}-longitude`}
              type="number"
              inputMode="decimal"
              min={-180}
              max={180}
              step="any"
              value={value.longitude}
              disabled={disabled}
              required
              aria-invalid={Boolean(errors.longitude)}
              placeholder="30.3351"
              autoComplete="off"
              onChange={(event) => patch({ longitude: event.target.value })}
            />
            {errors.longitude ? (
              <FieldError>{errors.longitude}</FieldError>
            ) : null}
          </Field>
        </div>

        <Field data-invalid={Boolean(errors.contactPhone) || undefined}>
          <FieldLabel htmlFor={`${idPrefix}-contact-phone`}>
            Контактный телефон заказа
          </FieldLabel>
          <Input
            id={`${idPrefix}-contact-phone`}
            name={`${idPrefix}-contactPhone`}
            type="tel"
            value={value.contactPhone}
            disabled={disabled}
            required
            maxLength={32}
            aria-invalid={Boolean(errors.contactPhone)}
            placeholder="+7 999 000-00-00"
            autoComplete="tel"
            onChange={(event) => patch({ contactPhone: event.target.value })}
          />
          <FieldDescription>
            По умолчанию используется основной телефон клиента, но его можно
            изменить.
          </FieldDescription>
          {errors.contactPhone ? (
            <FieldError>{errors.contactPhone}</FieldError>
          ) : null}
        </Field>

        <Field
          data-invalid={Boolean(errors.acceptableDeliveryDates) || undefined}
        >
          <FieldLabel>Дни, когда клиент может принять заказ</FieldLabel>
          <FieldGroup className="gap-2">
            {value.acceptableDeliveryDates.map((date, index) => (
              <Field
                key={`${idPrefix}-delivery-date-${index}`}
                orientation="horizontal"
              >
                <Input
                  id={`${idPrefix}-delivery-date-${index}`}
                  name={`${idPrefix}-acceptableDeliveryDates-${index}`}
                  type="date"
                  value={date}
                  disabled={disabled}
                  required
                  aria-label={`Допустимая дата приёмки ${index + 1}`}
                  aria-invalid={Boolean(errors.acceptableDeliveryDates)}
                  autoComplete="off"
                  onChange={(event) => {
                    const dates = [...value.acceptableDeliveryDates]
                    dates[index] = event.target.value
                    patch({ acceptableDeliveryDates: dates })
                  }}
                />
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  disabled={
                    disabled || value.acceptableDeliveryDates.length === 1
                  }
                  aria-label={`Удалить дату приёмки ${index + 1}`}
                  onClick={() =>
                    patch({
                      acceptableDeliveryDates:
                        value.acceptableDeliveryDates.filter(
                          (_, currentIndex) => currentIndex !== index
                        ),
                    })
                  }
                >
                  <HugeiconsIcon icon={Delete02Icon} />
                </Button>
              </Field>
            ))}
          </FieldGroup>
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="self-start"
            disabled={disabled || value.acceptableDeliveryDates.length >= 31}
            onClick={() =>
              patch({
                acceptableDeliveryDates: [...value.acceptableDeliveryDates, ""],
              })
            }
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить дату
          </Button>
          {errors.acceptableDeliveryDates ? (
            <FieldError>{errors.acceptableDeliveryDates}</FieldError>
          ) : null}
        </Field>

        <Field>
          <FieldLabel htmlFor={`${idPrefix}-comment`}>
            Комментарий к заказу
          </FieldLabel>
          <Textarea
            id={`${idPrefix}-comment`}
            name={`${idPrefix}-comment`}
            value={value.comment}
            disabled={disabled}
            maxLength={2_000}
            placeholder="Особенности подъезда, пропуск, время связи"
            autoComplete="off"
            onChange={(event) => patch({ comment: event.target.value })}
          />
          <FieldDescription>Необязательное поле.</FieldDescription>
        </Field>
      </FieldGroup>
    </FieldSet>
  )
}
