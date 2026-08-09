export const CLIENT_TYPES = ["INDIVIDUAL", "LEGAL_ENTITY"] as const

export type ClientType = (typeof CLIENT_TYPES)[number]

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
