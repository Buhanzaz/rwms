import {
  DEV_AUTH_BYPASS_ENABLED,
  DEV_AUTH_BYPASS_TOKEN,
} from "@/features/auth/auth-config"
import { getUserManager } from "@/features/auth/oidc-client"

export const DEV_MAINTENANCE_FIXTURES_ENABLED =
  import.meta.env.DEV &&
  import.meta.env.VITE_DEV_MAINTENANCE_FIXTURES === "true"

export async function getMaintenanceAccessToken() {
  if (DEV_AUTH_BYPASS_ENABLED) {
    return DEV_AUTH_BYPASS_TOKEN
  }

  const user = await getUserManager().getUser()
  if (!user || user.expired || !user.access_token.trim()) {
    throw new Error(
      "Не получен токен доступа к сервису технического обслуживания."
    )
  }

  return user.access_token
}

export function requireMaintenanceWarehouseId(warehouseId: string) {
  if (!warehouseId.trim()) {
    throw new Error("Не выбран склад для технического обслуживания.")
  }

  return warehouseId
}

const commandKeys = new Map<string, string>()
const clientIds = new Map<string, string>()
const TECHNICAL_MAPPING_PREFIX = "rwms:maintenance-technical-id:v1:"
const TECHNICAL_MAPPING_INDEX = `${TECHNICAL_MAPPING_PREFIX}index`
const MAX_TECHNICAL_MAPPINGS = 256
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function randomUuid() {
  if (typeof crypto === "undefined" || !("randomUUID" in crypto)) {
    throw new Error("Браузер не поддерживает безопасные UUID команд.")
  }

  return crypto.randomUUID()
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value)
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`
  }
  const source = value as Record<string, unknown>
  return `{${Object.keys(source)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalJson(source[key])}`)
    .join(",")}}`
}

async function cryptographicDigest(value: string) {
  if (
    typeof crypto === "undefined" ||
    !crypto.subtle ||
    typeof TextEncoder === "undefined"
  ) {
    throw new Error(
      "Браузер не поддерживает криптографическую защиту идентификаторов команд."
    )
  }
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value)
  )
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("")
}

function getSessionStorage() {
  try {
    return typeof window === "undefined" ? null : window.sessionStorage
  } catch {
    return null
  }
}

function rememberTechnicalMapping(digest: string, create: () => string) {
  const storageKey = `${TECHNICAL_MAPPING_PREFIX}${digest}`
  const storage = getSessionStorage()
  const stored = storage?.getItem(storageKey)
  if (stored && UUID_PATTERN.test(stored)) return stored

  const created = create()
  if (!storage) return created
  let index: string[] = []
  try {
    const parsed = JSON.parse(storage.getItem(TECHNICAL_MAPPING_INDEX) ?? "[]")
    if (Array.isArray(parsed)) {
      index = parsed.filter(
        (item): item is string =>
          typeof item === "string" &&
          item.startsWith(TECHNICAL_MAPPING_PREFIX) &&
          item !== TECHNICAL_MAPPING_INDEX
      )
    }
  } catch {
    index = []
  }
  const next = [...index.filter((item) => item !== storageKey), storageKey]
  while (next.length > MAX_TECHNICAL_MAPPINGS) {
    const evicted = next.shift()
    if (evicted) storage.removeItem(evicted)
  }
  storage.setItem(storageKey, created)
  storage.setItem(TECHNICAL_MAPPING_INDEX, JSON.stringify(next))
  return created
}

function forgetTechnicalMapping(digest: string) {
  const storageKey = `${TECHNICAL_MAPPING_PREFIX}${digest}`
  const storage = getSessionStorage()
  if (!storage) return
  storage.removeItem(storageKey)
  try {
    const parsed = JSON.parse(storage.getItem(TECHNICAL_MAPPING_INDEX) ?? "[]")
    const next = Array.isArray(parsed)
      ? parsed.filter((item) => item !== storageKey)
      : []
    if (next.length === 0) {
      storage.removeItem(TECHNICAL_MAPPING_INDEX)
    } else {
      storage.setItem(TECHNICAL_MAPPING_INDEX, JSON.stringify(next))
    }
  } catch {
    storage.removeItem(TECHNICAL_MAPPING_INDEX)
  }
}

function commandDigest(scope: string, canonicalCommand: unknown) {
  return cryptographicDigest(
    canonicalJson({ scope, command: canonicalCommand })
  )
}

function pendingIdempotencyKey(digest: string) {
  const existing = commandKeys.get(digest)
  if (existing) return existing

  const created = rememberTechnicalMapping(`command:${digest}`, randomUuid)
  commandKeys.set(digest, created)
  return created
}

export async function withIdempotencyKey<T>(
  scope: string,
  canonicalCommand: unknown,
  operation: (idempotencyKey: string) => Promise<T>
) {
  const digest = await commandDigest(scope, canonicalCommand)
  const result = await operation(pendingIdempotencyKey(digest))
  commandKeys.delete(digest)
  forgetTechnicalMapping(`command:${digest}`)
  return result
}

export async function canonicalClientUuid(clientId: string) {
  if (UUID_PATTERN.test(clientId)) return clientId

  const digest = await cryptographicDigest(clientId)
  const existing = clientIds.get(digest)
  if (existing) return existing

  const created = rememberTechnicalMapping(`client:${digest}`, randomUuid)
  clientIds.set(digest, created)
  return created
}

export function resetMaintenanceRuntimeForTests(clearSessionStorage = true) {
  commandKeys.clear()
  clientIds.clear()
  if (!clearSessionStorage) return
  const storage = getSessionStorage()
  if (!storage) return
  const keys = Array.from({ length: storage.length }, (_, index) =>
    storage.key(index)
  ).filter((key): key is string =>
    Boolean(key?.startsWith(TECHNICAL_MAPPING_PREFIX))
  )
  keys.forEach((key) => storage.removeItem(key))
}
