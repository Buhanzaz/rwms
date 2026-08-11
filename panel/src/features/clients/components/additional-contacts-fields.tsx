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
import type {
  AdditionalContact,
  AdditionalContactErrors,
} from "@/features/clients/domain/clients"

/**
 * Controlled rows for contacts that complement, but never replace, the
 * client's or order's primary contact.
 */
export function AdditionalContactsFields({
  idPrefix,
  value,
  errors = [],
  disabled = false,
  ownerLabel,
  onChange,
}: {
  idPrefix: string
  value: AdditionalContact[]
  errors?: AdditionalContactErrors[]
  disabled?: boolean
  ownerLabel: string
  onChange: (value: AdditionalContact[]) => void
}) {
  function update(index: number, field: keyof AdditionalContact, next: string) {
    onChange(
      value.map((contact, currentIndex) =>
        currentIndex === index ? { ...contact, [field]: next } : contact
      )
    )
  }

  return (
    <FieldSet>
      <FieldLegend>Дополнительные контакты {ownerLabel}</FieldLegend>
      <FieldDescription>
        Основное контактное лицо и телефон сохраняются отдельно. Здесь можно
        добавить любое количество других контактных лиц.
      </FieldDescription>
      <FieldGroup className="gap-3">
        {value.map((contact, index) => (
          <FieldSet key={`${idPrefix}-additional-contact-${index}`}>
            <FieldLegend variant="label">
              Дополнительный контакт {index + 1}
            </FieldLegend>
            <FieldGroup className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] sm:items-start">
              <Field data-invalid={Boolean(errors[index]?.name) || undefined}>
                <FieldLabel htmlFor={`${idPrefix}-contact-${index}-name`}>
                  Имя
                </FieldLabel>
                <Input
                  id={`${idPrefix}-contact-${index}-name`}
                  value={contact.name}
                  required
                  disabled={disabled}
                  maxLength={255}
                  aria-invalid={Boolean(errors[index]?.name)}
                  autoComplete="name"
                  placeholder="Фамилия Имя"
                  onChange={(event) =>
                    update(index, "name", event.target.value)
                  }
                />
                {errors[index]?.name ? (
                  <FieldError>{errors[index].name}</FieldError>
                ) : null}
              </Field>
              <Field data-invalid={Boolean(errors[index]?.phone) || undefined}>
                <FieldLabel htmlFor={`${idPrefix}-contact-${index}-phone`}>
                  Телефон
                </FieldLabel>
                <Input
                  id={`${idPrefix}-contact-${index}-phone`}
                  type="tel"
                  value={contact.phone}
                  required
                  disabled={disabled}
                  maxLength={32}
                  pattern="(?:\\+|8)[0-9() .-]{6,31}"
                  aria-invalid={Boolean(errors[index]?.phone)}
                  autoComplete="tel"
                  inputMode="tel"
                  placeholder="+7 999 000-00-00"
                  onChange={(event) =>
                    update(index, "phone", event.target.value)
                  }
                />
                {errors[index]?.phone ? (
                  <FieldError>{errors[index].phone}</FieldError>
                ) : null}
              </Field>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                className="sm:mt-6"
                disabled={disabled}
                aria-label={`Удалить дополнительный контакт ${index + 1}`}
                onClick={() =>
                  onChange(
                    value.filter(
                      (_contact, currentIndex) => currentIndex !== index
                    )
                  )
                }
              >
                <HugeiconsIcon icon={Delete02Icon} />
              </Button>
            </FieldGroup>
          </FieldSet>
        ))}
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="self-start"
          disabled={disabled}
          onClick={() => onChange([...value, { name: "", phone: "" }])}
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Добавить контакт
        </Button>
      </FieldGroup>
    </FieldSet>
  )
}
