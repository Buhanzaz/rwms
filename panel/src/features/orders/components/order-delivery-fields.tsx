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
  function patch(next: Partial<OrderDeliveryDraft>) {
    onChange({ ...value, ...next })
  }

  return (
    <FieldSet>
      <FieldLegend>Контакт и комментарий</FieldLegend>
      <FieldDescription>
        Адрес, координаты и дополнительные контакты клиент укажет в
        представлении после выбора бытовок и наполнения.
      </FieldDescription>
      <FieldGroup>
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
