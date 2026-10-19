const RENTAL_ITEM_NUMBER_PATTERN =
  /^[\p{L}\p{N}][\p{L}\p{N} _-]{0,127}$/u

export function normalizeHtmlImportRentalNumber(value: string) {
  return value
    .trim()
    .replace(/[\p{Z}\s]+/gu, " ")
    .toLocaleUpperCase("ru-RU")
}

export function htmlImportRentalNumberError(value: string) {
  const normalized = normalizeHtmlImportRentalNumber(value)
  if (normalized.length === 0) return "Укажите новый номер бытовки."
  if (!RENTAL_ITEM_NUMBER_PATTERN.test(normalized)) {
    return "Номер должен начинаться с буквы или цифры и содержать только буквы, цифры, пробелы, дефис или подчёркивание."
  }
  return null
}

export function htmlImportRentalNumberIdentityKey(value: string) {
  return normalizeHtmlImportRentalNumber(value).replace(/[ _-]/g, "")
}
