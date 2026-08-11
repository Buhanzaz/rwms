import type { RefObject } from "react"

import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { AdditionalContactsFields } from "@/features/clients/components/additional-contacts-fields"
import {
  CLIENT_TYPES,
  CLIENT_TYPE_LABELS,
  clientNeedsContactPerson,
  type AdditionalContact,
  type AdditionalContactErrors,
  type ClientType,
} from "@/features/clients/domain/clients"

/** Values collected whenever a manager creates a rental client in the panel. */
export type ClientCreateFieldsValue = {
  clientType: ClientType
  displayName: string
  phone: string
  contactPerson: string
  email: string
  comment: string
  source: string
  additionalContacts: AdditionalContact[]
}

/** Inline validation messages shared by the standalone and embedded client forms. */
export type ClientCreateFieldsErrors = Partial<
  Record<"displayName" | "phone" | "contactPerson", string>
> & { additionalContacts?: AdditionalContactErrors[] }

/** Optional refs let a parent move keyboard focus to the first invalid client field. */
export type ClientCreateFieldsRefs = {
  displayName?: RefObject<HTMLInputElement | null>
  phone?: RefObject<HTMLInputElement | null>
  contactPerson?: RefObject<HTMLInputElement | null>
}

/**
 * Renders the two legal-form choices shared by every client-creation entry point.
 *
 * <p>The caller owns the value because the field is also used to filter existing clients before
 * an inline client is created.</p>
 */
export function ClientTypeField({
  id,
  value,
  disabled = false,
  onChange,
}: {
  id: string
  value: ClientType
  disabled?: boolean
  onChange: (value: ClientType) => void
}) {
  return (
    <FieldSet>
      <FieldLegend>Тип клиента</FieldLegend>
      <ToggleGroup
        id={id}
        type="single"
        value={value}
        spacing={2}
        aria-label="Тип клиента"
        disabled={disabled}
        onValueChange={(next) => {
          if (next) onChange(next as ClientType)
        }}
      >
        {CLIENT_TYPES.map((type) => (
          <ToggleGroupItem key={type} value={type}>
            {CLIENT_TYPE_LABELS[type]}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </FieldSet>
  )
}

/**
 * Controlled, accessible client fields used by clients, order creation, booking and chat.
 *
 * <p>The responsible manager is intentionally read-only: logistics derives it from the
 * authenticated session and never accepts it as client-controlled command data.</p>
 */
export function ClientCreateFields({
  idPrefix,
  value,
  responsibleManagerDisplayName,
  errors = {},
  refs: inputRefs = {},
  disabled = false,
  showClientType = true,
  showDisplayName = true,
  onChange,
}: {
  idPrefix: string
  value: ClientCreateFieldsValue
  responsibleManagerDisplayName: string
  errors?: ClientCreateFieldsErrors
  refs?: ClientCreateFieldsRefs
  disabled?: boolean
  showClientType?: boolean
  showDisplayName?: boolean
  onChange: (value: ClientCreateFieldsValue) => void
}) {
  function update<K extends keyof ClientCreateFieldsValue>(
    field: K,
    next: ClientCreateFieldsValue[K]
  ) {
    onChange({ ...value, [field]: next })
  }

  return (
    <>
      {showClientType ? (
        <ClientTypeField
          id={`${idPrefix}-client-type`}
          value={value.clientType}
          disabled={disabled}
          onChange={(clientType) =>
            onChange({
              ...value,
              clientType,
              contactPerson:
                clientType === "INDIVIDUAL" ? "" : value.contactPerson,
            })
          }
        />
      ) : null}

      <div className="grid gap-5 md:grid-cols-2">
        {showDisplayName ? (
          <Field
            className="md:col-span-2"
            data-invalid={Boolean(errors.displayName) || undefined}
          >
            <FieldLabel htmlFor={`${idPrefix}-client-display-name`}>
              Наименование или ФИО
            </FieldLabel>
            <Input
              id={`${idPrefix}-client-display-name`}
              ref={(element) => {
                if (inputRefs.displayName) {
                  inputRefs.displayName.current = element
                }
              }}
              name="displayName"
              value={value.displayName}
              required
              disabled={disabled}
              maxLength={512}
              aria-invalid={Boolean(errors.displayName)}
              autoComplete="name"
              placeholder="Например, ООО «Север»…"
              onChange={(event) => update("displayName", event.target.value)}
            />
            {errors.displayName ? (
              <FieldError>{errors.displayName}</FieldError>
            ) : null}
          </Field>
        ) : null}

        <Field data-invalid={Boolean(errors.phone) || undefined}>
          <FieldLabel htmlFor={`${idPrefix}-client-phone`}>
            Основной телефон
          </FieldLabel>
          <Input
            id={`${idPrefix}-client-phone`}
            ref={(element) => {
              if (inputRefs.phone) inputRefs.phone.current = element
            }}
            name="phone"
            type="tel"
            value={value.phone}
            required
            disabled={disabled}
            maxLength={32}
            pattern="(?:\\+|8)[0-9() .-]{6,31}"
            aria-invalid={Boolean(errors.phone)}
            autoComplete="tel"
            inputMode="tel"
            placeholder="+7 999 000-00-00…"
            onChange={(event) => update("phone", event.target.value)}
          />
          <FieldDescription>
            Используется для связи и поиска существующего клиента.
          </FieldDescription>
          {errors.phone ? <FieldError>{errors.phone}</FieldError> : null}
        </Field>

        {clientNeedsContactPerson(value.clientType) ? (
          <Field data-invalid={Boolean(errors.contactPerson) || undefined}>
            <FieldLabel htmlFor={`${idPrefix}-client-contact-person`}>
              Основное контактное лицо
            </FieldLabel>
            <Input
              id={`${idPrefix}-client-contact-person`}
              ref={(element) => {
                if (inputRefs.contactPerson) {
                  inputRefs.contactPerson.current = element
                }
              }}
              name="contactPerson"
              value={value.contactPerson}
              required
              disabled={disabled}
              maxLength={255}
              aria-invalid={Boolean(errors.contactPerson)}
              autoComplete="name"
              placeholder="Фамилия Имя…"
              onChange={(event) => update("contactPerson", event.target.value)}
            />
            <FieldDescription>
              Обязательно для юридического лица.
            </FieldDescription>
            {errors.contactPerson ? (
              <FieldError>{errors.contactPerson}</FieldError>
            ) : null}
          </Field>
        ) : null}

        <Field>
          <FieldLabel htmlFor={`${idPrefix}-client-email`}>Email</FieldLabel>
          <Input
            id={`${idPrefix}-client-email`}
            name="email"
            type="email"
            value={value.email}
            disabled={disabled}
            maxLength={320}
            autoComplete="email"
            spellCheck={false}
            placeholder="client@example.ru…"
            onChange={(event) => update("email", event.target.value)}
          />
          <FieldDescription>
            Нужен для электронной отправки документов.
          </FieldDescription>
        </Field>

        <Field>
          <FieldLabel htmlFor={`${idPrefix}-responsible-manager`}>
            Ответственный менеджер
          </FieldLabel>
          <Input
            id={`${idPrefix}-responsible-manager`}
            name="responsibleManager"
            value={responsibleManagerDisplayName}
            required
            readOnly
            autoComplete="off"
            aria-readonly="true"
          />
          <FieldDescription>
            Определяется текущей авторизованной сессией и не передаётся как
            клиентское поле.
          </FieldDescription>
        </Field>

        <Field className="md:col-span-2">
          <FieldLabel htmlFor={`${idPrefix}-client-comment`}>
            Комментарий
          </FieldLabel>
          <Textarea
            id={`${idPrefix}-client-comment`}
            name="comment"
            value={value.comment}
            disabled={disabled}
            maxLength={2_000}
            autoComplete="off"
            placeholder="Необязательно…"
            onChange={(event) => update("comment", event.target.value)}
          />
        </Field>

        <Field>
          <FieldLabel htmlFor={`${idPrefix}-client-source`}>
            Источник клиента
          </FieldLabel>
          <Input
            id={`${idPrefix}-client-source`}
            name="source"
            value={value.source}
            disabled={disabled}
            maxLength={255}
            autoComplete="off"
            placeholder="Рекомендация, сайт, звонок…"
            onChange={(event) => update("source", event.target.value)}
          />
        </Field>
      </div>

      <AdditionalContactsFields
        idPrefix={`${idPrefix}-client`}
        value={value.additionalContacts}
        errors={errors.additionalContacts}
        disabled={disabled}
        ownerLabel="клиента"
        onChange={(additionalContacts) =>
          update("additionalContacts", additionalContacts)
        }
      />
    </>
  )
}
