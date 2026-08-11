import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import type { AdditionalContact } from "@/features/clients/domain/clients"
import type { DesiredDeliveryWindow } from "@/features/orders/domain/orders"

export type PresentationPhoto = {
  mediaId: string
  generation: number
  sortOrder: number
  availableVariants: string[]
  thumbnailUrl: string
  contentUrl: string
}

export type PresentationCabin = {
  id: string
  number: string
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string | null
  linoleum: boolean | null
  passport: Record<string, unknown>
  tags: string[]
  currentContents: PresentationEquipmentContent[]
  photos: PresentationPhoto[]
}

export type PresentationEquipmentContent = {
  equipmentId: string
  equipmentName: string | null
  quantity: number
  locationKind: string
}

export type PresentationEquipmentAvailability = {
  equipmentId: string
  equipmentName: string
  availableQuantity: number
  maximumPerCabin: number | null
}

export type PresentationMode = "NORMAL" | "REPLACEMENT"

export type PresentationGroup = {
  key: string
  label: string
  cabins: PresentationCabin[]
}

export type ClientPresentation = {
  id: string
  version: number
  revision: number
  inquiryId: string
  warehouseId: string
  state: "ACTIVE" | "BOOKING_PENDING" | "BOOKED" | "REVOKED"
  expiresAt: string
  viewUntil: string
  canConfirm: boolean
  publicPath: string
  bookedOrderId: string | null
  mode: PresentationMode
  replacementUnitIds: string[]
  requiredSelectionCount: number | null
  requiresDesiredDeliveryWindows: boolean
  desiredDeliveryWindows: DesiredDeliveryWindow[]
  equipmentAvailability: PresentationEquipmentAvailability[]
  groups: PresentationGroup[]
}

export type PublicClientPresentation = {
  id: string
  revision: number
  state: "ACTIVE" | "BOOKING_PENDING" | "BOOKED" | "REVOKED"
  expiresAt: string
  viewUntil: string
  viewOnly: boolean
  mode: PresentationMode
  requiredSelectionCount: number | null
  requiresDesiredDeliveryWindows: boolean
  desiredDeliveryWindows: DesiredDeliveryWindow[]
  equipmentAvailability: PresentationEquipmentAvailability[]
  groups: PresentationGroup[]
  bookedOrderId: string | null
}

export type PresentationCabinSelection = {
  rentalItemId: string
  equipment: Array<{ equipmentId: string; quantity: number }>
}

export type PresentationBooking = {
  bookingId: string
  state: "PENDING" | "COMPLETED" | "REJECTED"
  orderId: string | null
  statusPath: string
  errorCode: string | null
}

export type RentalSettings = {
  version: number
  chatSelectionHoldMinutes: number
  manualBookingHoldMinutes: number
  presentationHoldMinutes: number
  draftReservationHoldMinutes: number
  updatedBy: string | null
  updatedAt: string
}

export type RentalBookingAlertClient = {
  id: string
  version: number
  type: string
  displayName: string
  phone: string | null
  email: string | null
  createdAt: string
  updatedAt: string
}

export type RentalBookingAlertCabin = {
  id: string
  number: string
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
}

export type RentalBookingAlert = {
  bookingId: string
  version: number
  inquiryId: string
  orderId: string
  client: RentalBookingAlertClient
  confirmedAt: string
  cabins: RentalBookingAlertCabin[]
}

export type RentalBookingAlertAction = "CONTINUE" | "KEEP_DRAFT"

function logisticsV1(path: string) {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1${path}`
}

function publicPresentationEndpoint(token: string, path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/public/v1/client-presentations/${encodeURIComponent(token)}${path}`
}

export function publishClientPresentation(params: {
  accessToken: string
  inquiryId: string
  warehouseId: string
  idempotencyKey: string
  manualBookingDraftId?: string
  mode?: PresentationMode
  replacementUnitIds?: string[]
  groups: Array<{
    key: string
    label: string
    rentalItemIds: string[]
  }>
}) {
  return bearerRequest<ClientPresentation>(
    params.accessToken,
    logisticsV1(
      `/rental-inquiries/${encodeURIComponent(params.inquiryId)}/client-presentation`
    ),
    {
      method: "PUT",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        warehouseId: params.warehouseId,
        manualBookingDraftId: params.manualBookingDraftId,
        mode: params.mode,
        replacementUnitIds: params.replacementUnitIds,
        groups: params.groups,
      }),
    }
  )
}

export function getClientPresentation(params: {
  accessToken: string
  inquiryId: string
}) {
  return bearerRequest<ClientPresentation>(
    params.accessToken,
    logisticsV1(
      `/rental-inquiries/${encodeURIComponent(params.inquiryId)}/client-presentation`
    )
  )
}

export function getRentalSettings(accessToken: string) {
  return bearerRequest<RentalSettings>(
    accessToken,
    logisticsV1("/settings/rental")
  )
}

export function updateRentalSettings(params: {
  accessToken: string
  expectedVersion: number
  chatSelectionHoldMinutes: number
  manualBookingHoldMinutes: number
  presentationHoldMinutes: number
  draftReservationHoldMinutes: number
}) {
  return bearerRequest<RentalSettings>(
    params.accessToken,
    logisticsV1("/settings/rental"),
    {
      method: "PUT",
      body: JSON.stringify({
        expectedVersion: params.expectedVersion,
        chatSelectionHoldMinutes: params.chatSelectionHoldMinutes,
        manualBookingHoldMinutes: params.manualBookingHoldMinutes,
        presentationHoldMinutes: params.presentationHoldMinutes,
        draftReservationHoldMinutes: params.draftReservationHoldMinutes,
      }),
    }
  )
}

export function getRentalBookingAlerts(accessToken: string) {
  return bearerRequest<RentalBookingAlert[]>(
    accessToken,
    logisticsV1("/rental-booking-alerts")
  )
}

export function actOnRentalBookingAlert(params: {
  accessToken: string
  bookingId: string
  expectedVersion: number
  action: RentalBookingAlertAction
  idempotencyKey: string
}) {
  return bearerRequest<void>(
    params.accessToken,
    logisticsV1(
      `/rental-booking-alerts/${encodeURIComponent(params.bookingId)}/actions`
    ),
    {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        expectedVersion: params.expectedVersion,
        action: params.action,
      }),
    }
  )
}

async function publicJson<T>(input: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  headers.set("Accept", "application/json")
  if (init?.body !== undefined) headers.set("Content-Type", "application/json")
  const response = await fetch(input, { ...init, headers })
  if (!response.ok) {
    let detail = `Запрос завершился с ошибкой ${response.status}.`
    try {
      const body = (await response.json()) as { detail?: unknown }
      if (typeof body.detail === "string") detail = body.detail
    } catch {
      // The status remains enough to render a safe public error.
    }
    throw new ApiError(detail, response.status)
  }
  return (await response.json()) as T
}

async function publicBookingJson(
  input: string,
  init?: RequestInit
): Promise<PresentationBooking> {
  const headers = new Headers(init?.headers)
  headers.set("Accept", "application/json")
  if (init?.body !== undefined) headers.set("Content-Type", "application/json")
  const response = await fetch(input, { ...init, headers })
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    // A safe status-based error is rendered below.
  }
  if (
    (response.ok || response.status === 409) &&
    body !== null &&
    typeof body === "object" &&
    typeof (body as Partial<PresentationBooking>).bookingId === "string" &&
    ["PENDING", "COMPLETED", "REJECTED"].includes(
      String((body as Partial<PresentationBooking>).state)
    )
  ) {
    return body as PresentationBooking
  }
  const detail =
    body !== null &&
    typeof body === "object" &&
    typeof (body as { detail?: unknown }).detail === "string"
      ? String((body as { detail: string }).detail)
      : `Запрос завершился с ошибкой ${response.status}.`
  throw new ApiError(detail, response.status)
}

export function getPublicPresentation(token: string) {
  return publicJson<PublicClientPresentation>(publicPresentationEndpoint(token))
}

export function confirmPublicPresentation(params: {
  token: string
  selections: PresentationCabinSelection[]
  desiredDeliveryWindows?: DesiredDeliveryWindow[]
  rentalMonths?: number
  deliveryAddress?: string
  latitude?: number
  longitude?: number
  additionalContacts?: AdditionalContact[]
  idempotencyKey: string
}) {
  return publicBookingJson(
    publicPresentationEndpoint(params.token, "/bookings"),
    {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        selections: params.selections,
        desiredDeliveryWindows: params.desiredDeliveryWindows,
        rentalMonths: params.rentalMonths,
        deliveryAddress: params.deliveryAddress,
        latitude: params.latitude,
        longitude: params.longitude,
        additionalContacts: params.additionalContacts,
      }),
    }
  )
}

export function getPublicPresentationBooking(params: {
  token: string
  bookingId: string
}) {
  return publicBookingJson(
    publicPresentationEndpoint(
      params.token,
      `/bookings/${encodeURIComponent(params.bookingId)}`
    )
  )
}
