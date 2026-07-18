import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import {
  DOSSIER_ACTIVITY_CODES,
  DOSSIER_MEDIA_STATES,
  DOSSIER_SOURCE_PRODUCERS,
  type CabinDossierPage,
  type DossierActivity,
  type DossierActivityCode,
  type DossierActorReference,
  type DossierMediaProjection,
  type DossierMediaState,
  type DossierSourceProducer,
  type DossierSourceReference,
  type DossierVisibility,
  type GetCabinDossierQuery,
} from "@/features/rental-items/dossier/model/dossier-service"
import type { RentalItemDossierClient } from "@/features/rental-items/dossier/ports/rental-item-dossier-client"

const INVALID_RESPONSE_MESSAGE =
  "Сервис досье вернул некорректный ответ об истории бытовки."
const MISSING_ACCESS_TOKEN_MESSAGE = "Не получен токен доступа к сервису досье."

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const PRINCIPAL_TYPE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/
const AGGREGATE_TYPE_PATTERN = /^[A-Z][A-Z0-9_]{0,127}$/
const PROFILE_REVISION_PATTERN =
  /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})$/

const activityCodes = new Set<string>(DOSSIER_ACTIVITY_CODES)
const sourceProducers = new Set<string>(DOSSIER_SOURCE_PRODUCERS)
const mediaStates = new Set<string>(DOSSIER_MEDIA_STATES)

type UnknownRecord = Record<string, unknown>

function requireAccessToken(accessToken: string | null) {
  if (accessToken === null || accessToken.trim() === "") {
    throw new Error(MISSING_ACCESS_TOKEN_MESSAGE)
  }

  return accessToken
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function hasOnlyKeys(value: UnknownRecord, keys: readonly string[]) {
  return Object.keys(value).every((key) => keys.includes(key))
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value)
}

function isDateTime(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.trim() !== "" &&
    Number.isFinite(Date.parse(value))
  )
}

function parseActorReference(value: unknown): DossierActorReference | null {
  if (value === null) return null
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["subjectId", "principalType", "profileRevision"])
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { subjectId, principalType, profileRevision } = value
  if (
    !isUuid(subjectId) ||
    typeof principalType !== "string" ||
    !PRINCIPAL_TYPE_PATTERN.test(principalType) ||
    (profileRevision !== null &&
      (typeof profileRevision !== "string" ||
        !PROFILE_REVISION_PATTERN.test(profileRevision)))
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { subjectId, principalType, profileRevision }
}

function parseSourceReference(value: unknown): DossierSourceReference {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, [
      "producer",
      "aggregateType",
      "aggregateId",
      "secondaryId",
    ])
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { producer, aggregateType, aggregateId, secondaryId } = value
  if (
    typeof producer !== "string" ||
    !sourceProducers.has(producer) ||
    typeof aggregateType !== "string" ||
    !AGGREGATE_TYPE_PATTERN.test(aggregateType) ||
    typeof aggregateId !== "string" ||
    aggregateId.length < 1 ||
    aggregateId.length > 256 ||
    (secondaryId !== undefined && !isUuid(secondaryId))
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    producer: producer as DossierSourceProducer,
    aggregateType,
    aggregateId,
    ...(secondaryId === undefined ? {} : { secondaryId }),
  }
}

function parseMediaProjection(value: unknown): DossierMediaProjection {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["mediaId", "findingId", "generation", "state"])
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { mediaId, findingId, generation, state } = value
  if (
    !isUuid(mediaId) ||
    !isUuid(findingId) ||
    typeof generation !== "number" ||
    !Number.isSafeInteger(generation) ||
    generation < 0 ||
    typeof state !== "string" ||
    !mediaStates.has(state)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    mediaId,
    findingId,
    generation,
    state: state as DossierMediaState,
  }
}

function parseActivity(
  value: unknown,
  responseCabinId: string
): DossierActivity {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, [
      "activityId",
      "cabinId",
      "warehouseId",
      "activityCode",
      "occurredAt",
      "recordedAt",
      "actorRef",
      "sourceRef",
      "media",
    ])
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const {
    activityId,
    cabinId,
    warehouseId,
    activityCode,
    occurredAt,
    recordedAt,
    actorRef,
    sourceRef,
    media,
  } = value
  if (
    !isUuid(activityId) ||
    !isUuid(cabinId) ||
    cabinId !== responseCabinId ||
    !isUuid(warehouseId) ||
    typeof activityCode !== "string" ||
    !activityCodes.has(activityCode) ||
    (occurredAt !== null && !isDateTime(occurredAt)) ||
    !isDateTime(recordedAt) ||
    !Array.isArray(media)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    activityId,
    cabinId,
    warehouseId,
    activityCode: activityCode as DossierActivityCode,
    occurredAt,
    recordedAt,
    actorRef: parseActorReference(actorRef),
    sourceRef: parseSourceReference(sourceRef),
    media: media.map(parseMediaProjection),
  }
}

function parsePage(value: unknown, requestedCabinId: string): CabinDossierPage {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["cabinId", "activities", "nextCursor", "visibility"])
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { cabinId, activities, nextCursor, visibility } = value
  if (
    !isUuid(cabinId) ||
    cabinId !== requestedCabinId ||
    !Array.isArray(activities) ||
    activities.length > 100 ||
    (nextCursor !== null &&
      (typeof nextCursor !== "string" ||
        nextCursor.length < 1 ||
        nextCursor.length > 4096)) ||
    (visibility !== "COMPLETE" && visibility !== "PARTIAL")
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    cabinId,
    activities: activities.map((activity) => parseActivity(activity, cabinId)),
    nextCursor,
    visibility: visibility as DossierVisibility,
  }
}

function endpoint(cabinId: string, query: GetCabinDossierQuery) {
  const { dossierApiBaseUrl } = getGatewayRuntimeConfig()
  const url = new URL(
    `${dossierApiBaseUrl}/v1/cabins/${encodeURIComponent(cabinId)}`
  )

  if (query.limit !== undefined)
    url.searchParams.set("limit", String(query.limit))
  if (query.after) url.searchParams.set("after", query.after)
  if (query.occurredFrom)
    url.searchParams.set("occurredFrom", query.occurredFrom)
  if (query.occurredBefore)
    url.searchParams.set("occurredBefore", query.occurredBefore)
  query.activityCodes?.forEach((code) =>
    url.searchParams.append("activityCode", code)
  )
  query.sourceTypes?.forEach((sourceType) =>
    url.searchParams.append("sourceType", sourceType)
  )
  if (query.actorSubjectId)
    url.searchParams.set("actorSubjectId", query.actorSubjectId)

  return url.toString()
}

export class HttpRentalItemDossierAdapter implements RentalItemDossierClient {
  private readonly accessToken: string | null

  constructor(accessToken: string | null) {
    this.accessToken = accessToken
  }

  async getPage(cabinId: string, query: GetCabinDossierQuery = {}) {
    const response = await bearerRequest<unknown>(
      requireAccessToken(this.accessToken),
      endpoint(cabinId, query)
    )
    return parsePage(response, cabinId)
  }
}
