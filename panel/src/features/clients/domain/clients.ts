export const CLIENT_TYPES = ["INDIVIDUAL", "LEGAL_ENTITY"] as const

export type ClientType = (typeof CLIENT_TYPES)[number]

/** Named phone contact owned by a client or, separately, by an order. */
export type AdditionalContact = {
  name: string
  phone: string
}

/** Field-level errors for one additional contact row. */
export type AdditionalContactErrors = Partial<
  Record<keyof AdditionalContact, string>
>

export const CLIENT_TYPE_LABELS: Record<ClientType, string> = {
  INDIVIDUAL: "Физическое лицо",
  LEGAL_ENTITY: "Юридическое лицо",
}

export type RentalClient = {
  id: string
  version: number
  type: ClientType
  displayName: string
  phone: string | null
  contactPerson: string | null
  email: string | null
  responsibleManagerId: string
  responsibleManagerDisplayName: string | null
  comment: string | null
  source: string | null
  additionalContacts: AdditionalContact[]
  createdAt: string
  updatedAt: string
}

export type ClientPage = {
  content: RentalClient[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type CreateClientInput = {
  clientType: ClientType
  displayName: string
  phone: string
  contactPerson?: string | null
  email?: string | null
  comment?: string | null
  source?: string | null
  additionalContacts?: AdditionalContact[] | null
}

const CLIENT_PHONE_PATTERN = /^(?:\+|8)[0-9() .-]{6,31}$/

export function isValidClientPhone(value: string) {
  return CLIENT_PHONE_PATTERN.test(value.trim())
}

export function normalizeClientDisplayName(value: string) {
  return value.trim().replace(/\s+/g, " ")
}

export function normalizeClientSearch(value: string) {
  return normalizeClientDisplayName(value).toLocaleLowerCase("ru-RU")
}

export function clientNeedsContactPerson(type: ClientType) {
  return type === "LEGAL_ENTITY"
}

/**
 * Normalizes and validates a list of named phone contacts without imposing an
 * arbitrary row limit.
 */
export function parseAdditionalContacts(value: AdditionalContact[]): {
  contacts: AdditionalContact[] | null
  errors: AdditionalContactErrors[]
} {
  const errors = value.map<AdditionalContactErrors>((contact) => {
    const rowErrors: AdditionalContactErrors = {}
    if (!contact.name.trim()) {
      rowErrors.name = "Укажите имя дополнительного контакта."
    }
    if (!contact.phone.trim()) {
      rowErrors.phone = "Укажите телефон дополнительного контакта."
    } else if (!isValidClientPhone(contact.phone)) {
      rowErrors.phone = "Укажите корректный телефон дополнительного контакта."
    }
    return rowErrors
  })

  return {
    contacts: errors.some((row) => Object.keys(row).length > 0)
      ? null
      : value.map((contact) => ({
          name: normalizeClientDisplayName(contact.name),
          phone: contact.phone.trim(),
        })),
    errors,
  }
}
