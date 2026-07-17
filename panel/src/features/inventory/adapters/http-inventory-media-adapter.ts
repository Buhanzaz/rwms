import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import { requireInventoryAccessToken } from "@/features/inventory/inventory-runtime"
import type {
  InventoryMediaAsset,
  InventoryMediaScope,
} from "@/features/inventory/model/inventory-service"

type UploadSession = {
  uploadSessionId: string
  mediaId: string
  expiresAt: string
  uploadUrl: string
  formFields: Record<string, string>
}

function endpoint(path: string) {
  return `${getGatewayRuntimeConfig().mediaApiBaseUrl}/v1${path}`
}

function ownerQuery(scope: InventoryMediaScope) {
  return new URLSearchParams({
    ownerType: "INVENTORY_FINDING",
    ownerId: scope.ownerId,
    warehouseId: scope.warehouseId,
    context: "INSPECTION",
  }).toString()
}

async function checksumSha256(file: File) {
  const bytes = await file.arrayBuffer()
  const digest = await crypto.subtle.digest("SHA-256", bytes)
  return Array.from(new Uint8Array(digest), (value) =>
    value.toString(16).padStart(2, "0")
  ).join("")
}

export function listInventoryMedia(
  accessToken: string | null,
  scope: InventoryMediaScope
) {
  return bearerRequest<{ items: InventoryMediaAsset[]; next: string | null }>(
    requireInventoryAccessToken(accessToken),
    endpoint(`/assets?${ownerQuery(scope)}&limit=100`)
  )
}

export async function uploadInventoryMedia(input: {
  accessToken: string | null
  scope: InventoryMediaScope
  file: File
  sortOrder: number
}) {
  const accessToken = requireInventoryAccessToken(input.accessToken)
  const checksum = await checksumSha256(input.file)
  const session = await bearerRequest<UploadSession>(
    accessToken,
    endpoint("/upload-sessions"),
    {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({
        ownerType: "INVENTORY_FINDING",
        ownerId: input.scope.ownerId,
        warehouseId: input.scope.warehouseId,
        context: "INSPECTION",
        fileName: input.file.name,
        contentType: input.file.type,
        contentLength: input.file.size,
        checksumSha256: checksum,
        sortOrder: input.sortOrder,
      }),
    }
  )
  const body = new FormData()
  Object.entries(session.formFields).forEach(([name, value]) =>
    body.append(name, value)
  )
  body.append("file", input.file)
  const uploaded = await fetch(session.uploadUrl, { method: "POST", body })
  if (!uploaded.ok) {
    throw new Error(`Загрузка файла не удалась (${uploaded.status})`)
  }
  const objectVersionId = uploaded.headers.get("x-amz-version-id")
  const etag = uploaded.headers.get("etag")
  if (!objectVersionId || !etag) {
    throw new Error("Хранилище не подтвердило неизменяемую версию файла")
  }
  return bearerRequest<InventoryMediaAsset>(
    accessToken,
    endpoint(
      `/upload-sessions/${encodeURIComponent(session.uploadSessionId)}/complete`
    ),
    {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({ objectVersionId, etag, checksumSha256: checksum }),
    }
  )
}

export function getInventoryMediaOriginal(
  accessToken: string | null,
  scope: InventoryMediaScope,
  mediaId: string
) {
  return bearerRequest<{ url: string; expiresAt: string }>(
    requireInventoryAccessToken(accessToken),
    endpoint(
      `/assets/${encodeURIComponent(mediaId)}/original?${ownerQuery(scope)}`
    )
  )
}
