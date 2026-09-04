import {
  apiErrorFromRequestFailure,
  apiErrorFromResponse,
  invalidApiResponseError,
} from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type ContractorRouteEntryStatus =
  "WAITING" | "IN_PROGRESS" | "PAUSED" | "DONE" | "CANCELLED"

export type ContractorEvidenceState =
  "RESERVED" | "UPLOADING" | "READY" | "REVIEW_REQUIRED" | "REJECTED"

export type PublicContractorMedia = {
  mediaId: string
  generation: number
  contentType: string | null
  capturedAt: string | null
  recordedAt: string
  contentPath: string
  thumbnailPath: string
}

export type PublicContractorEvidence = {
  evidenceId: string
  version: number
  capturedAt: string
  recordedAt: string
  state: ContractorEvidenceState
  mediaId: string | null
  mediaGeneration: number | null
  contentType: "image/jpeg" | "image/webp"
  contentPath: string | null
  thumbnailPath: string | null
}

export type PublicContractorRouteEntry = {
  entryId: string
  version: number
  routeIndex: number
  routeStepIndex: number
  routeStepCount: number
  queueName: string
  taskText: string | null
  status: ContractorRouteEntryStatus
  plannedDurationMinutes: number | null
  works: Array<{
    id: string
    name: string
    quantity: number
    unit: string | null
    durationMinutes: number | null
    comment: string | null
    sourceMediaIds: string[]
  }>
  materials: Array<{
    id: string
    name: string
    quantity: number
    unit: string | null
  }>
  comments: Array<{
    id: string
    text: string
    authorDisplayName: string | null
    createdAt: string
  }>
  sourceMedia: PublicContractorMedia[]
  resultPhotoMinCount: number
  evidence: PublicContractorEvidence[]
  completionAllowed: boolean
}

export type PublicContractorRouteTask = {
  externalTaskId: string
  taskId: string
  taskVersion: number
  title: string
  description: string | null
  unitNumber: string | null
  scheduledDate: string
  deadlineAt: string | null
  priority: number
  status: "ACTIVE" | "DONE" | "CANCELLED"
  address: string | null
  latitude: number | null
  longitude: number | null
  contactPhone: string | null
  logisticsComment: string | null
  cargo: Array<{
    kind: "WORK" | "MATERIAL"
    name: string
    quantity: number
    unit: string | null
    comment: string | null
  }>
  sourceMedia: PublicContractorMedia[]
  route: PublicContractorRouteEntry[]
}

export type PublicContractorRouteShare = {
  id: string
  expiresAt: string
  tasks: PublicContractorRouteTask[]
}

export type PublicContractorEvidenceUpload = {
  evidenceId: string
  version: number
  state: "UPLOADING" | "READY"
  mediaId: string
  mediaGeneration: number | null
  contentType: "image/jpeg" | "image/webp"
  contentPath: string | null
  thumbnailPath: string | null
}

const JPEG_MAX_BYTES = 15 * 1024 * 1024
const WEBP_MAX_BYTES = 1024 * 1024

function contractorShareEndpoint(token: string, suffix = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/public/v1/contractor-route-shares/${encodeURIComponent(token)}${suffix}`
}

function routeEntryPath(
  externalTaskId: string,
  entryId: string,
  suffix: string
) {
  return `/tasks/${encodeURIComponent(externalTaskId)}/entries/${encodeURIComponent(entryId)}${suffix}`
}

async function publicJson<T>(input: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(input, init)
  } catch (error) {
    throw apiErrorFromRequestFailure(error)
  }
  if (!response.ok) throw await apiErrorFromResponse(response)
  try {
    return (await response.json()) as T
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

/** Loads one live contractor route without requiring an RWMS user session. */
export function getPublicContractorRouteShare(token: string) {
  return publicJson<PublicContractorRouteShare>(
    contractorShareEndpoint(token),
    {
      headers: { Accept: "application/json" },
      cache: "no-store",
    }
  )
}

/** Applies one exact entry transition behind the task-board version fence. */
export function applyPublicContractorRouteAction(params: {
  token: string
  externalTaskId: string
  entryId: string
  idempotencyKey: string
  action: "START" | "COMPLETE"
  expectedVersion: number
  evidenceId: string | null
}) {
  return publicJson<{
    currentVersion: number
    task: PublicContractorRouteTask
  }>(
    contractorShareEndpoint(
      params.token,
      routeEntryPath(params.externalTaskId, params.entryId, "/actions")
    ),
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Idempotency-Key": params.idempotencyKey,
      },
      body: JSON.stringify({
        action: params.action,
        expectedVersion: params.expectedVersion,
        evidenceId: params.evidenceId,
      }),
    }
  )
}

/** Uploads one immutable JPEG/WebP evidence blob through the scoped route capability. */
export async function uploadPublicContractorEvidence(params: {
  token: string
  externalTaskId: string
  entryId: string
  evidenceId: string
  capturedAt: string
  file: File
}) {
  const contentType = params.file.type
  if (contentType !== "image/jpeg" && contentType !== "image/webp") {
    throw new Error("Выберите фотографию JPEG или WebP.")
  }
  const maximum = contentType === "image/webp" ? WEBP_MAX_BYTES : JPEG_MAX_BYTES
  if (params.file.size < 1 || params.file.size > maximum) {
    throw new Error(
      contentType === "image/webp"
        ? "Фотография WebP должна быть не больше 1 МБ."
        : "Фотография JPEG должна быть не больше 15 МБ."
    )
  }
  const checksum = await calculateContractorEvidenceSha256(params.file)
  return publicJson<PublicContractorEvidenceUpload>(
    contractorShareEndpoint(
      params.token,
      routeEntryPath(
        params.externalTaskId,
        params.entryId,
        `/evidence/${encodeURIComponent(params.evidenceId)}`
      )
    ),
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": contentType,
        "Idempotency-Key": params.evidenceId,
        "X-Captured-At": params.capturedAt,
        "X-Content-SHA256": checksum,
      },
      body: params.file,
    }
  )
}

/** Calculates the lowercase checksum required by task-board and media-service. */
export async function calculateContractorEvidenceSha256(blob: Blob) {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), (value) =>
    value.toString(16).padStart(2, "0")
  ).join("")
}
